/**
 * MambaDoc JNI bridge – production-oriented, post-llama.cpp 1.0 API.
 *
 * Design points for Mamba-2 hybrid models (nemotron_h / granitemoehybrid):
 *   - n_seq_max = 1 always (prevents SSM state cross-talk)
 *   - llama_memory_clear(..., true) between isolated RAG turns
 *   - use_mmap = true, pure CPU (n_gpu_layers = 0)
 *   - streaming tokens via JNI callback + atomic cancel flag
 *   - embeddings with mean pooling + L2 normalisation (nomic-embed-text)
 */

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "MambaDocJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_mu;
std::atomic<bool> g_cancel{false};

llama_model   * g_llm_model  = nullptr;
llama_context * g_llm_ctx    = nullptr;
llama_model   * g_emb_model  = nullptr;
llama_context * g_emb_ctx    = nullptr;

struct Callback {
    JavaVM  * jvm      = nullptr;
    jobject   obj      = nullptr;   // global ref
    jmethodID onToken  = nullptr;
    jmethodID onDone   = nullptr;
    jmethodID onError  = nullptr;
} g_cb;

void free_llm() {
    if (g_llm_ctx)   { llama_free(g_llm_ctx);           g_llm_ctx   = nullptr; }
    if (g_llm_model) { llama_model_free(g_llm_model);   g_llm_model = nullptr; }
}

void free_emb() {
    if (g_emb_ctx)   { llama_free(g_emb_ctx);           g_emb_ctx   = nullptr; }
    if (g_emb_model) { llama_model_free(g_emb_model);   g_emb_model = nullptr; }
}

void l2_normalize(float * v, int n) {
    double ss = 0.0;
    for (int i = 0; i < n; ++i) ss += (double)v[i] * (double)v[i];
    const double nrm = std::sqrt(ss);
    if (nrm > 1e-12) {
        const float inv = (float)(1.0 / nrm);
        for (int i = 0; i < n; ++i) v[i] *= inv;
    }
}

/** Attach if needed, call Java, detach if we attached. */
void call_java_token(const std::string & piece) {
    if (!g_cb.obj || !g_cb.onToken || !g_cb.jvm) return;
    JNIEnv * env = nullptr;
    bool attached = false;
    if (g_cb.jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_cb.jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jstring j = env->NewStringUTF(piece.c_str());
    if (j) {
        env->CallVoidMethod(g_cb.obj, g_cb.onToken, j);
        env->DeleteLocalRef(j);
    }
    if (attached) g_cb.jvm->DetachCurrentThread();
}

void call_java_done() {
    if (!g_cb.obj || !g_cb.onDone || !g_cb.jvm) return;
    JNIEnv * env = nullptr;
    bool attached = false;
    if (g_cb.jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_cb.jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallVoidMethod(g_cb.obj, g_cb.onDone);
    if (attached) g_cb.jvm->DetachCurrentThread();
}

void call_java_error(const char * msg) {
    if (!g_cb.obj || !g_cb.onError || !g_cb.jvm) return;
    JNIEnv * env = nullptr;
    bool attached = false;
    if (g_cb.jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_cb.jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jstring j = env->NewStringUTF(msg);
    if (j) {
        env->CallVoidMethod(g_cb.obj, g_cb.onError, j);
        env->DeleteLocalRef(j);
    }
    if (attached) g_cb.jvm->DetachCurrentThread();
}

/** Tokenize helper that works with the post-1.0 vocab API. */
int tokenize(
    const llama_model * model,
    const char * text,
    int text_len,
    std::vector<llama_token> & out,
    bool add_special)
{
    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (!vocab) return -1;

    // First call to discover required size (negative return = needed size)
    int n = llama_tokenize(vocab, text, text_len, nullptr, 0, add_special, true);
    if (n < 0) {
        out.resize(-n);
        n = llama_tokenize(vocab, text, text_len, out.data(), (int)out.size(), add_special, true);
    } else {
        out.resize(n > 0 ? n : 0);
        if (n > 0) {
            n = llama_tokenize(vocab, text, text_len, out.data(), (int)out.size(), add_special, true);
        }
    }
    if (n < 0) return -1;
    out.resize(n);
    return n;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    g_cb.jvm = vm;
    llama_backend_init();
    LOGI("llama_backend_init ok");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *, void *) {
    std::lock_guard<std::mutex> lock(g_mu);
    free_llm();
    free_emb();
    llama_backend_free();
    // Global ref cleanup is best-effort; process is exiting.
    g_cb.obj = nullptr;
}

// ---------------------------------------------------------------------------
// Model load / unload
// ---------------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_mambadoc_app_native_LlamaNative_loadLlmModel(
        JNIEnv * env, jclass,
        jstring pathJ, jint n_ctx, jint n_threads, jboolean low_ram_swap)
{
    std::lock_guard<std::mutex> lock(g_mu);
    g_cancel = false;

    if (low_ram_swap) {
        free_emb();
        LOGI("Low-RAM swap: embedder unloaded");
    }
    free_llm();

    const char * path = env->GetStringUTFChars(pathJ, nullptr);
    if (!path) return JNI_FALSE;

    llama_model_params mp = llama_model_default_params();
    mp.use_mmap  = true;
    mp.use_mlock = false;
    mp.n_gpu_layers = 0;          // pure CPU on this target

    g_llm_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(pathJ, path);

    if (!g_llm_model) {
        LOGE("llama_model_load_from_file failed");
        return JNI_FALSE;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx          = n_ctx > 0 ? (uint32_t)n_ctx : 16384u;
    cp.n_batch        = 512;
    cp.n_ubatch       = 512;
    cp.n_seq_max      = 1;        // critical for Mamba-2 SSM
    cp.n_threads      = n_threads > 0 ? n_threads : 6;
    cp.n_threads_batch = n_threads > 0 ? (n_threads + 2) : 8;
    cp.embeddings     = false;

    g_llm_ctx = llama_init_from_model(g_llm_model, cp);
    if (!g_llm_ctx) {
        LOGE("llama_init_from_model failed");
        free_llm();
        return JNI_FALSE;
    }

    LOGI("LLM ready  n_ctx=%u  n_threads=%d  n_embd=%d",
         cp.n_ctx, cp.n_threads, llama_model_n_embd(g_llm_model));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_mambadoc_app_native_LlamaNative_loadEmbedModel(
        JNIEnv * env, jclass, jstring pathJ, jint n_threads)
{
    std::lock_guard<std::mutex> lock(g_mu);
    free_emb();

    const char * path = env->GetStringUTFChars(pathJ, nullptr);
    if (!path) return JNI_FALSE;

    llama_model_params mp = llama_model_default_params();
    mp.use_mmap = true;
    mp.n_gpu_layers = 0;

    g_emb_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(pathJ, path);

    if (!g_emb_model) {
        LOGE("embed model load failed");
        return JNI_FALSE;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx          = 2048;                 // nomic-embed-text-v1.5
    cp.n_batch        = 512;
    cp.n_seq_max      = 1;
    cp.n_threads      = n_threads > 0 ? n_threads : 4;
    cp.embeddings     = true;
    cp.pooling_type   = LLAMA_POOLING_TYPE_MEAN;

    g_emb_ctx = llama_init_from_model(g_emb_model, cp);
    if (!g_emb_ctx) {
        LOGE("embed context failed");
        free_emb();
        return JNI_FALSE;
    }

    LOGI("Embedder ready  n_embd=%d", llama_model_n_embd(g_emb_model));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_mambadoc_app_native_LlamaNative_unloadLlm(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mu);
    free_llm();
}

JNIEXPORT void JNICALL
Java_com_mambadoc_app_native_LlamaNative_unloadEmbed(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mu);
    free_emb();
}

JNIEXPORT jboolean JNICALL
Java_com_mambadoc_app_native_LlamaNative_isLlmLoaded(JNIEnv *, jclass) {
    return (g_llm_model && g_llm_ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mambadoc_app_native_LlamaNative_isEmbedLoaded(JNIEnv *, jclass) {
    return (g_emb_model && g_emb_ctx) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Embeddings (caller must already prepend "search_document: " / "search_query: ")
// ---------------------------------------------------------------------------

JNIEXPORT jfloatArray JNICALL
Java_com_mambadoc_app_native_LlamaNative_embed(JNIEnv * env, jclass, jstring textJ)
{
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_emb_ctx || !g_emb_model) {
        LOGE("embed model not loaded");
        return nullptr;
    }

    const char * text = env->GetStringUTFChars(textJ, nullptr);
    if (!text) return nullptr;
    const int text_len = (int)strlen(text);

    std::vector<llama_token> tokens;
    if (tokenize(g_emb_model, text, text_len, tokens, /*add_special=*/true) < 0) {
        env->ReleaseStringUTFChars(textJ, text);
        LOGE("tokenize failed (embed)");
        return nullptr;
    }
    env->ReleaseStringUTFChars(textJ, text);

    if (tokens.empty()) return nullptr;

    // Clear any previous state (also good hygiene for hybrid models)
    llama_memory_clear(llama_get_memory(g_emb_ctx), true);

    const int n = (int)tokens.size();
    const int n_batch = 512;
    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    for (int start = 0; start < n; start += n_batch) {
        const int chunk = std::min(n_batch, n - start);
        for (int i = 0; i < chunk; ++i) {
            batch.token   [i] = tokens[start + i];
            batch.pos     [i] = start + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id  [i][0] = 0;
            batch.logits  [i] = (start + i == n - 1);
        }
        batch.n_tokens = chunk;
        if (llama_decode(g_emb_ctx, batch) != 0) {
            LOGE("llama_decode failed (embed)");
            llama_batch_free(batch);
            return nullptr;
        }
    }
    llama_batch_free(batch);

    const float * emb = llama_get_embeddings_seq(g_emb_ctx, 0);
    if (!emb) {
        emb = llama_get_embeddings_ith(g_emb_ctx, n - 1);
    }
    if (!emb) {
        LOGE("no embedding vector returned");
        return nullptr;
    }

    const int n_embd = llama_model_n_embd(g_emb_model);
    std::vector<float> out(emb, emb + n_embd);
    l2_normalize(out.data(), n_embd);

    jfloatArray arr = env->NewFloatArray(n_embd);
    if (arr) env->SetFloatArrayRegion(arr, 0, n_embd, out.data());
    return arr;
}

// ---------------------------------------------------------------------------
// Generation
// ---------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_mambadoc_app_native_LlamaNative_setCallback(JNIEnv * env, jclass, jobject cb)
{
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_cb.obj) {
        env->DeleteGlobalRef(g_cb.obj);
        g_cb.obj = nullptr;
    }
    if (cb) {
        g_cb.obj = env->NewGlobalRef(cb);
        jclass cls = env->GetObjectClass(cb);
        g_cb.onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        g_cb.onDone  = env->GetMethodID(cls, "onDone",  "()V");
        g_cb.onError = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
    }
}

JNIEXPORT void JNICALL
Java_com_mambadoc_app_native_LlamaNative_requestCancel(JNIEnv *, jclass) {
    g_cancel = true;
}

JNIEXPORT void JNICALL
Java_com_mambadoc_app_native_LlamaNative_clearMemory(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_llm_ctx) {
        llama_memory_clear(llama_get_memory(g_llm_ctx), true);
    }
}

JNIEXPORT void JNICALL
Java_com_mambadoc_app_native_LlamaNative_generate(
        JNIEnv * env, jclass,
        jstring promptJ,
        jfloat temperature, jfloat top_p, jfloat min_p,
        jint max_tokens, jboolean clear_memory)
{
    std::lock_guard<std::mutex> lock(g_mu);
    g_cancel = false;

    if (!g_llm_ctx || !g_llm_model) {
        call_java_error("LLM not loaded");
        return;
    }

    if (clear_memory) {
        // Clears both KV cache and Mamba-2 recurrent SSM state
        llama_memory_clear(llama_get_memory(g_llm_ctx), true);
    }

    const char * prompt = env->GetStringUTFChars(promptJ, nullptr);
    if (!prompt) {
        call_java_error("null prompt");
        return;
    }
    const int prompt_len = (int)strlen(prompt);

    std::vector<llama_token> tokens;
    if (tokenize(g_llm_model, prompt, prompt_len, tokens, /*add_special=*/true) < 0) {
        env->ReleaseStringUTFChars(promptJ, prompt);
        call_java_error("tokenize failed");
        return;
    }
    env->ReleaseStringUTFChars(promptJ, prompt);

    const int n_prompt = (int)tokens.size();
    if (n_prompt == 0) {
        call_java_error("empty tokenization");
        return;
    }

    // --- prompt evaluation (sliced to n_batch so long RAG prompts don't assert) ---
    const int n_batch = 512;
    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    for (int start = 0; start < n_prompt; start += n_batch) {
        if (g_cancel.load()) {
            llama_batch_free(batch);
            call_java_done();
            return;
        }
        const int chunk = std::min(n_batch, n_prompt - start);
        for (int i = 0; i < chunk; ++i) {
            batch.token   [i] = tokens[start + i];
            batch.pos     [i] = start + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id  [i][0] = 0;
            batch.logits  [i] = (start + i == n_prompt - 1);
        }
        batch.n_tokens = chunk;
        if (llama_decode(g_llm_ctx, batch) != 0) {
            LOGE("prompt decode failed");
            llama_batch_free(batch);
            call_java_error("prompt decode failed");
            return;
        }
    }
    llama_batch_free(batch);

    // --- sampler chain (modern API) ---
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    if (min_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(min_p, 1));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(/*seed=*/42));

    const llama_vocab * vocab = llama_model_get_vocab(g_llm_model);
    const int n_ctx = (int)llama_n_ctx(g_llm_ctx);
    int n_cur = n_prompt;
    int n_gen = 0;

    while (n_gen < max_tokens && n_cur < n_ctx) {
        if (g_cancel.load()) {
            LOGI("generation cancelled");
            break;
        }

        const llama_token id = llama_sampler_sample(smpl, g_llm_ctx, -1);
        llama_sampler_accept(smpl, id);

        if (llama_vocab_is_eog(vocab, id)) break;

        // piece → Java (resize if token piece > fixed buffer)
        char buf[256];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n < 0) {
            // negative = needed size; allocate and retry
            const int needed = -n;
            std::vector<char> big(needed + 1);
            n = llama_token_to_piece(vocab, id, big.data(), needed, 0, true);
            if (n > 0) {
                call_java_token(std::string(big.data(), n));
            }
        } else if (n > 0) {
            call_java_token(std::string(buf, n));
        }

        // feed the new token
        llama_batch next = llama_batch_init(1, 0, 1);
        next.token   [0] = id;
        next.pos     [0] = n_cur;
        next.n_seq_id[0] = 1;
        next.seq_id  [0][0] = 0;
        next.logits  [0] = true;
        next.n_tokens = 1;

        if (llama_decode(g_llm_ctx, next) != 0) {
            LOGE("decode failed mid-generation");
            llama_batch_free(next);
            break;
        }
        llama_batch_free(next);

        ++n_cur;
        ++n_gen;
    }

    llama_sampler_free(smpl);
    call_java_done();
}

} // extern "C"

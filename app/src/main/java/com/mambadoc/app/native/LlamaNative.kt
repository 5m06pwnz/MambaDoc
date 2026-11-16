package com.mambadoc.app.native

/**
 * JNI bridge to llama.cpp (Mamba-2 hybrid + nomic embeddings).
 * All model files live in app-private storage after import.
 */
object LlamaNative {

    init {
        System.loadLibrary("mambadoc_jni")
    }

    interface TokenCallback {
        fun onToken(token: String)
        fun onDone()
        fun onError(message: String)
    }

    external fun loadLlmModel(path: String, nCtx: Int, nThreads: Int, lowRamSwap: Boolean): Boolean
    external fun loadEmbedModel(path: String, nThreads: Int): Boolean
    external fun unloadLlm()
    external fun unloadEmbed()
    external fun isLlmLoaded(): Boolean
    external fun isEmbedLoaded(): Boolean

    /** Returns L2-normalized 768-dim float array or null on failure. */
    external fun embed(text: String): FloatArray?

    external fun setCallback(cb: TokenCallback?)
    external fun requestCancel()
    external fun generate(
        prompt: String,
        temperature: Float,
        topP: Float,
        minP: Float,
        maxTokens: Int,
        clearMemory: Boolean
    )
    external fun clearMemory()
}

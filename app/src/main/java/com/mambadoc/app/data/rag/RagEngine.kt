package com.mambadoc.app.data.rag

import com.mambadoc.app.data.db.DocDatabase
import com.mambadoc.app.data.model.AppSettings
import com.mambadoc.app.data.model.ChatMessage
import com.mambadoc.app.data.model.Citation
import com.mambadoc.app.data.model.RetrievedChunk
import com.mambadoc.app.native.LlamaNative
import com.mambadoc.app.domain.AgentTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RagEngine(
    private val db: DocDatabase,
    private val retriever: HybridRetriever = HybridRetriever(db)
) {

    /**
     * Build a grounded prompt for Nemotron-3 / Granite hybrid models.
     * Supports thinking / non-thinking system tags.
     */
    fun buildPrompt(
        userQuery: String,
        chunks: List<RetrievedChunk>,
        settings: AppSettings,
        docNames: Map<Long, String>
    ): String {
        val contextBlock = chunks.mapIndexed { i, rc ->
            val name = docNames[rc.chunk.docId] ?: "Doc"
            val page = rc.chunk.pageOrSection.ifBlank { "?" }
            "[${i + 1}] ($name, $page)\n${rc.chunk.text}"
        }.joinToString("\n\n")

        val system = if (settings.thinkingMode) {
            // Nemotron-3 style switchable reasoning
            """You are a careful document analyst. Think step by step inside <think>...</think> tags before answering.
Always cite sources using the exact format [DocName, Page X]. If the answer is not in the provided context, say so clearly.

${AgentTools.TOOL_PROMPT_FRAGMENT}
"""
        } else {
            """You are a precise document Q&A assistant. Answer only from the provided context.
Cite sources as [DocName, Page X]. If the context does not contain the answer, say "I cannot find that information in the selected documents."

${AgentTools.TOOL_PROMPT_FRAGMENT}
"""
        }

        return """
$system

### Context
$contextBlock

### Question
$userQuery

### Answer
""".trimIndent()
    }

    suspend fun answer(
        query: String,
        settings: AppSettings,
        selectedDocIds: List<Long>? = null,
        onToken: (String) -> Unit,
        onThinking: (String) -> Unit = {},
        onComplete: (List<Citation>) -> Unit
    ) = withContext(Dispatchers.Default) {

        val chunks = retriever.retrieve(query, settings.ragTopK, selectedDocIds)
        if (chunks.isEmpty()) {
            onToken("No relevant passages found in the vault.")
            onComplete(emptyList())
            return@withContext
        }

        val docs = db.getAllDocuments().associateBy { it.id }
        val docNames = docs.mapValues { it.value.filename }

        val prompt = buildPrompt(query, chunks, settings, docNames)

        val citations = chunks.map { rc ->
            val name = docNames[rc.chunk.docId] ?: "Document"
            Citation(
                docName = name,
                pageOrSection = rc.chunk.pageOrSection,
                chunkText = rc.chunk.text,
                score = rc.score
            )
        }

        val cb = object : LlamaNative.TokenCallback {
            private val thinkingBuf = StringBuilder()
            private var inThinking = false

            override fun onToken(token: String) {
                if (settings.thinkingMode) {
                    // simple state machine for <think> tags
                    thinkingBuf.append(token)
                    val s = thinkingBuf.toString()
                    if (!inThinking && s.contains("<think>")) {
                        inThinking = true
                    }
                    if (inThinking) {
                        onThinking(token)
                        if (s.contains("</think>")) {
                            inThinking = false
                        }
                    } else {
                        onToken(token)
                    }
                } else {
                    onToken(token)
                }
            }
            override fun onDone() {
                onComplete(citations)
            }
            override fun onError(message: String) {
                onToken("\n[Error: $message]")
                onComplete(citations)
            }
        }

        LlamaNative.setCallback(cb)
        LlamaNative.generate(
            prompt = prompt,
            temperature = settings.temperature,
            topP = settings.topP,
            minP = settings.minP,
            maxTokens = settings.maxGenTokens,
            clearMemory = true          // isolate every RAG turn for Mamba-2 safety
        )
    }

    /**
     * Hierarchical map-reduce summarization for large documents.
     */
    suspend fun summarizeDocument(
        docId: Long,
        settings: AppSettings,
        onProgress: (String) -> Unit,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) = withContext(Dispatchers.Default) {

        val chunks = db.getChunksForDoc(docId)
        if (chunks.isEmpty()) {
            onComplete("No content to summarize.")
            return@withContext
        }

        val totalChars = chunks.sumOf { it.text.length }
        val ctxBudget = (settings.nCtx * 0.7).toInt() * 4  // rough chars

        if (totalChars < ctxBudget) {
            // Single pass
            onProgress("Single-pass summary…")
            val text = chunks.joinToString("\n\n") { it.text }
            val prompt = """
You are an expert summarizer. Produce a dense, factual executive summary with:
- Key takeaways (bullet list)
- Important entities / parties
- Section references where possible

Document:
$text

Executive Summary:
""".trimIndent()
            streamGenerate(prompt, settings, onToken) { final ->
                db.updateSummary(docId, final)
                onComplete(final)
            }
        } else {
            // Map-Reduce
            val windowSize = 6000 // chars
            val windows = mutableListOf<String>()
            val buf = StringBuilder()
            for (c in chunks) {
                if (buf.length + c.text.length > windowSize && buf.isNotEmpty()) {
                    windows.add(buf.toString())
                    buf.clear()
                }
                buf.append(c.text).append("\n\n")
            }
            if (buf.isNotEmpty()) windows.add(buf.toString())

            val intermediate = mutableListOf<String>()
            windows.forEachIndexed { i, w ->
                onProgress("Batch ${i + 1}/${windows.size}")
                val mapPrompt = """
Summarize the following section into dense factual bullets. Keep entities, numbers, and section cues.

Section:
$w

Bullets:
""".trimIndent()
                val result = StringBuilder()
                streamGenerate(mapPrompt, settings, { result.append(it) }, {})
                intermediate.add(result.toString())
                LlamaNative.clearMemory() // critical between batches
            }

            onProgress("Synthesizing final summary…")
            val reducePrompt = """
You are synthesizing intermediate summaries of a long document into one executive summary.
Produce:
1. Key takeaways
2. Important entities
3. Notable risks / decisions
4. Section references

Intermediate summaries:
${intermediate.joinToString("\n\n---\n\n")}

Final Executive Summary:
""".trimIndent()
            streamGenerate(reducePrompt, settings, onToken) { final ->
                db.updateSummary(docId, final)
                onComplete(final)
            }
        }
    }

    private fun streamGenerate(
        prompt: String,
        settings: AppSettings,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        val buf = StringBuilder()
        val cb = object : LlamaNative.TokenCallback {
            override fun onToken(token: String) {
                buf.append(token)
                onToken(token)
            }
            override fun onDone() {
                onComplete(buf.toString())
            }
            override fun onError(message: String) {
                onToken("\n[Error: $message]")
                onComplete(buf.toString())
            }
        }
        LlamaNative.setCallback(cb)
        LlamaNative.generate(
            prompt, settings.temperature, settings.topP, settings.minP,
            settings.maxGenTokens, clearMemory = true
        )
    }
}

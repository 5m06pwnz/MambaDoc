package com.mambadoc.app.data.model

data class DocumentMeta(
    val id: Long = 0,
    val filename: String,
    val fileType: String,          // pdf, docx, txt, md
    val pageCount: Int = 0,
    val totalChars: Int = 0,
    val chunkCount: Int = 0,
    val ingestedAt: Long = System.currentTimeMillis(),
    val summaryCache: String? = null,
    val isScannedPdf: Boolean = false
)

data class Chunk(
    val id: Long = 0,
    val docId: Long,
    val chunkIndex: Int,
    val pageOrSection: String,     // e.g. "p.4" or "§2.1"
    val text: String,
    val tokenEst: Int,
    val embedding: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Chunk
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

data class RetrievedChunk(
    val chunk: Chunk,
    val score: Float,
    val source: String             // "vector" | "fts" | "rrf"
)

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val thinking: String? = null,
    val citations: List<Citation> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

data class Citation(
    val docName: String,
    val pageOrSection: String,
    val chunkText: String,
    val score: Float
)

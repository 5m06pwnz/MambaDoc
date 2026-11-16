package com.mambadoc.app.data.rag

import com.mambadoc.app.data.db.DocDatabase
import com.mambadoc.app.data.model.Chunk
import com.mambadoc.app.data.model.RetrievedChunk
import com.mambadoc.app.native.LlamaNative
import kotlin.math.sqrt

/**
 * Hybrid retrieval: exact cosine on 768-dim vectors + FTS5 BM25,
 * fused with Reciprocal Rank Fusion (RRF).
 */
class HybridRetriever(private val db: DocDatabase) {

    fun retrieve(
        query: String,
        topK: Int = 6,
        docIds: List<Long>? = null
    ): List<RetrievedChunk> {
        // 1. Vector search
        val qPrefixed = "search_query: $query"
        val qEmb = LlamaNative.embed(qPrefixed) ?: return emptyList()

        val candidates = db.getAllChunksWithEmbeddings(docIds)
        val vectorScores = candidates.mapNotNull { c ->
            val emb = c.embedding ?: return@mapNotNull null
            val sim = cosine(qEmb, emb)
            c to sim
        }.sortedByDescending { it.second }

        // 2. Lexical FTS5 BM25
        val ftsResults = db.ftsSearch(query, limit = topK * 3, docIds = docIds)
        // BM25 returns lower = better; convert to rank

        // 3. RRF
        val rrfScores = mutableMapOf<Long, Float>()
        val k = 60f // classic RRF constant

        vectorScores.take(topK * 3).forEachIndexed { rank, (chunk, _) ->
            rrfScores[chunk.id] = rrfScores.getOrDefault(chunk.id, 0f) + 1f / (k + rank + 1)
        }
        ftsResults.forEachIndexed { rank, (chunkId, _) ->
            rrfScores[chunkId] = rrfScores.getOrDefault(chunkId, 0f) + 1f / (k + rank + 1)
        }

        val sortedIds = rrfScores.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { it.key }

        return sortedIds.mapNotNull { id ->
            val chunk = db.getChunkById(id) ?: return@mapNotNull null
            val score = rrfScores[id] ?: 0f
            RetrievedChunk(chunk, score, "rrf")
        }
    }

    /** Unrolled cosine for 768-dim – fast on Snapdragon. */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        // simple loop; compiler + NEON will vectorize well
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom < 1e-12) 0f else (dot / denom).toFloat()
    }
}

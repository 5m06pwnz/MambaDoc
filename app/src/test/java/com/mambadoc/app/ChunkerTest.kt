package com.mambadoc.app

import com.mambadoc.app.data.rag.Chunker
import org.junit.Assert.*
import org.junit.Test

class ChunkerTest {

    @Test
    fun emptyInput_returnsEmpty() {
        assertTrue(Chunker.chunk("").isEmpty())
    }

    @Test
    fun shortText_singleChunk() {
        val text = "Hello world. This is a short paragraph."
        val chunks = Chunker.chunk(text, targetTokens = 400)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].contains("Hello world"))
    }

    @Test
    fun longText_multipleChunksWithOverlap() {
        val para = (1..50).joinToString(" ") { "Sentence number $it is here." }
        val text = (1..20).joinToString("\n\n") { "Paragraph $it. $para" }
        val chunks = Chunker.chunk(text, targetTokens = 100, overlapRatio = 0.15f)
        assertTrue(chunks.size > 1)
        // rough size check
        chunks.forEach { c ->
            assertTrue("chunk too large: ${c.length}", c.length < 512 * 5)
        }
    }
}

package com.mambadoc.app.data.rag

/**
 * Smart paragraph/sentence-aware chunker.
 * Target ~350-450 tokens (~1400-1800 chars) with ~15% overlap.
 * Never exceeds ~512 tokens so nomic-embed quality stays high.
 */
object Chunker {

    fun chunk(
        fullText: String,
        targetTokens: Int = 400,
        overlapRatio: Float = 0.15f
    ): List<String> {
        val targetChars = targetTokens * 4          // rough
        val overlapChars = (targetChars * overlapRatio).toInt()
        val maxChars = 512 * 4

        // Prefer splitting on double newlines (paragraphs), then sentences
        val paragraphs = fullText
            .split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush(force: Boolean = false) {
            if (current.isNotEmpty() && (force || current.length >= targetChars * 0.6)) {
                var text = current.toString().trim()
                if (text.length > maxChars) {
                    // hard split
                    var start = 0
                    while (start < text.length) {
                        val end = minOf(start + maxChars, text.length)
                        chunks.add(text.substring(start, end).trim())
                        start = end - overlapChars
                        if (start < 0) start = 0
                        if (start >= text.length) break
                    }
                } else {
                    chunks.add(text)
                }
                // keep overlap
                if (current.length > overlapChars) {
                    val keep = current.substring(current.length - overlapChars)
                    current.clear()
                    current.append(keep)
                } else {
                    current.clear()
                }
            }
        }

        for (p in paragraphs) {
            if (current.length + p.length + 2 > targetChars && current.isNotEmpty()) {
                flush()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(p)

            // If a single paragraph is huge, split by sentences
            if (current.length > maxChars) {
                val sentences = current.toString().split(Regex("(?<=[.!?])\\s+"))
                current.clear()
                for (s in sentences) {
                    if (current.length + s.length > targetChars && current.isNotEmpty()) {
                        flush()
                    }
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(s)
                }
            }
        }
        flush(force = true)
        return chunks.filter { it.isNotBlank() }
    }
}

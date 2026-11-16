package com.mambadoc.app.data.parser

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Plain-text family parser (.txt, .md, .csv, .json, .log, .xml, …).
 * Tries UTF-8 first, then falls back to a small set of common encodings.
 * No third-party libraries.
 */
object TextParser {

    private val CANDIDATE_CHARSETS = listOf(
        StandardCharsets.UTF_8,
        Charset.forName("UTF-16LE"),
        Charset.forName("UTF-16BE"),
        Charset.forName("ISO-8859-1"),
        Charset.forName("Windows-1252")
    )

    fun parse(input: InputStream): String {
        // We may need to re-read, so buffer everything once.
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return ""

        // Fast path: UTF-8 with optional BOM
        val utf8 = decodeUtf8(bytes)
        if (utf8 != null) return utf8

        // Fallback encodings
        for (cs in CANDIDATE_CHARSETS.drop(1)) {
            try {
                val text = String(bytes, cs)
                // Heuristic: if the result contains lots of replacement chars, skip
                if (text.count { it == '\uFFFD' } < text.length / 50) {
                    return text
                }
            } catch (_: Exception) { /* next */ }
        }
        // Last resort
        return String(bytes, StandardCharsets.ISO_8859_1)
    }

    private fun decodeUtf8(bytes: ByteArray): String? {
        return try {
            // Strip UTF-8 BOM if present
            val offset = if (bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
            ) 3 else 0
            val s = String(bytes, offset, bytes.size - offset, StandardCharsets.UTF_8)
            // If the decoder produced many replacement characters the file is probably not UTF-8
            if (s.count { it == '\uFFFD' } > s.length / 20) null else s
        } catch (_: Exception) {
            null
        }
    }

    /** Supported plain-text extensions (case-insensitive). */
    val SUPPORTED_EXTENSIONS = setOf(
        "txt", "md", "markdown", "mdown", "mkd",
        "csv", "tsv", "json", "jsonl", "ndjson",
        "log", "text", "xml", "html", "htm",
        "yaml", "yml", "toml", "ini", "cfg", "conf",
        "rtf" // RTF is mostly plain text; we don't interpret control words yet
    )

    fun isSupported(filename: String, mime: String?): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext in SUPPORTED_EXTENSIONS) return true
        if (mime == null) return false
        return mime.startsWith("text/") ||
                mime == "application/json" ||
                mime == "application/xml" ||
                mime == "application/x-yaml"
    }
}

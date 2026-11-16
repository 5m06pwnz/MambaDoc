package com.mambadoc.app.data.parser

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Inflater
import kotlin.math.max

/**
 * Zero external-dependency PDF text extractor.
 *
 * Primary path (API 35+ / Android 15+, native on S26 Ultra Android 16):
 *   PdfRenderer.Page.getTextContents() → structured text with page markers.
 *
 * Fallback (API < 35 or when getTextContents throws):
 *   Lightweight pure-Kotlin scanner that:
 *     - locates stream…endstream blocks
 *     - attempts FlateDecode via java.util.zip.Inflater
 *     - extracts literal strings from Tj / TJ operators
 *
 * Scanned / image-only detection:
 *   If average characters per page < 20 the document is flagged.
 */
object PdfParser {

    data class PageText(
        val pageIndex: Int,   // 1-based
        val text: String
    )

    data class ParseResult(
        val pages: List<PageText>,
        val isScanned: Boolean,
        val totalChars: Int
    )

    fun parse(pfd: ParcelFileDescriptor): ParseResult {
        return if (Build.VERSION.SDK_INT >= 35) {
            try {
                parseWithRenderer(pfd)
            } catch (_: Exception) {
                parseFallback(pfd)
            }
        } else {
            parseFallback(pfd)
        }
    }

    fun parse(file: File): ParseResult {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            return parse(pfd)
        }
    }

    private fun parseWithRenderer(pfd: ParcelFileDescriptor): ParseResult {
        val pages = mutableListOf<PageText>()
        var totalChars = 0

        PdfRenderer(pfd).use { renderer ->
            val count = renderer.pageCount
            for (i in 0 until count) {
                renderer.openPage(i).use { page ->
                    val contents = try {
                        page.textContents
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val text = contents
                        .mapNotNull { it?.text?.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
                        .trim()
                    pages.add(PageText(pageIndex = i + 1, text = text))
                    totalChars += text.length
                }
            }
        }

        val avg = if (pages.isEmpty()) 0 else totalChars / pages.size
        return ParseResult(
            pages = pages,
            isScanned = avg < 20,
            totalChars = totalChars
        )
    }

    private fun parseFallback(pfd: ParcelFileDescriptor): ParseResult {
        val tmp = File.createTempFile("mambadoc_pdf_", ".bin")
        try {
            FileOutputStream(tmp).use { out ->
                FileInputStream(pfd.fileDescriptor).use { ins ->
                    ins.copyTo(out)
                }
            }
            val raw = tmp.readBytes()
            val text = extractTextFromPdfBytes(raw)
            val isScanned = text.length < 40
            return ParseResult(
                pages = listOf(PageText(1, text)),
                isScanned = isScanned,
                totalChars = text.length
            )
        } finally {
            tmp.delete()
        }
    }

    private fun extractTextFromPdfBytes(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        val limit = data.size - 12

        while (i < limit) {
            if (matchAscii(data, i, "stream")) {
                i += 6
                while (i < data.size && (data[i] == '\r'.code.toByte() || data[i] == '\n'.code.toByte() || data[i] == ' '.code.toByte())) {
                    i++
                }
                val start = i
                var end = start
                while (end < data.size - 9 && !matchAscii(data, end, "endstream")) {
                    end++
                }
                if (end > start) {
                    val compressed = data.copyOfRange(start, end)
                    val inflated = tryInflate(compressed)
                    if (inflated != null && inflated.isNotEmpty()) {
                        extractOperators(inflated, sb)
                    }
                }
                i = end + 9
            } else {
                i++
            }
        }
        return sb.toString()
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun matchAscii(data: ByteArray, pos: Int, s: String): Boolean {
        if (pos + s.length > data.size) return false
        for (k in s.indices) {
            if (data[pos + k].toInt().toChar() != s[k]) return false
        }
        return true
    }

    private fun tryInflate(src: ByteArray): ByteArray? {
        val attempts = listOf(
            src,
            if (src.size > 2) src.copyOfRange(2, src.size) else src
        )
        for (candidate in attempts) {
            try {
                val inflater = Inflater(true)
                inflater.setInput(candidate)
                val out = ByteArray(max(candidate.size * 8, 4096))
                val n = inflater.inflate(out)
                inflater.end()
                if (n > 16) return out.copyOf(n)
            } catch (_: Exception) { }

            try {
                val inflater = Inflater(false)
                inflater.setInput(candidate)
                val out = ByteArray(max(candidate.size * 8, 4096))
                val n = inflater.inflate(out)
                inflater.end()
                if (n > 16) return out.copyOf(n)
            } catch (_: Exception) { }
        }
        return null
    }

    private fun extractOperators(data: ByteArray, sb: StringBuilder) {
        val s = String(data, Charsets.ISO_8859_1)
        val tj = Regex("""\((?:\\.|[^\\)])*\)\s*Tj""")
        val tjArray = Regex("""\[(?:[^\]]*)\]\s*TJ""")

        tj.findAll(s).forEach { m ->
            val raw = m.value
            val inner = raw.removePrefix("(").substringBeforeLast(")")
            val cleaned = inner.removeSuffix(" Tj").removeSuffix("Tj")
            appendDecoded(cleaned, sb)
        }

        tjArray.findAll(s).forEach { m ->
            Regex("""\((?:\\.|[^\\)])*\)""").findAll(m.value).forEach { lit ->
                val inner = lit.value.removeSurrounding("(", ")")
                appendDecoded(inner, sb)
            }
        }
    }

    private fun appendDecoded(raw: String, sb: StringBuilder) {
        val decoded = raw
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\(", "(")
            .replace("\\)", ")")
            .replace("\\\\", "\\")
            .replace(Regex("""\\([0-7]{1,3})""")) { mr ->
                val v = mr.groupValues[1].toIntOrNull(8) ?: return@replace mr.value
                v.toChar().toString()
            }
        if (decoded.isNotBlank()) {
            sb.append(decoded).append(' ')
        }
    }
}

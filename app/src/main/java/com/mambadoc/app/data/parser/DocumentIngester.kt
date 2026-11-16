package com.mambadoc.app.data.parser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mambadoc.app.data.db.DocDatabase
import com.mambadoc.app.data.model.Chunk
import com.mambadoc.app.data.model.DocumentMeta
import com.mambadoc.app.data.rag.Chunker
import com.mambadoc.app.native.LlamaNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Central import path for every supported document type.
 * MIME + extension detection is deliberately broad so markdown, text,
 * CSV, JSON, etc. are first-class citizens alongside PDF/DOCX.
 */
class DocumentIngester(
    private val context: Context,
    private val db: DocDatabase
) {

    data class Progress(
        val stage: String,
        val current: Int,
        val total: Int,
        val message: String = ""
    )

    suspend fun ingest(
        uri: Uri,
        onProgress: (Progress) -> Unit
    ): DocumentMeta = withContext(Dispatchers.IO) {

        val filename = queryName(uri) ?: "document"
        val mime = context.contentResolver.getType(uri) ?: ""
        val type = detectType(filename, mime)

        onProgress(Progress("extract", 0, 1, "Extracting text from $type…"))

        val (rawText, pageCount, isScanned) = when (type) {
            "pdf" -> {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Cannot open PDF")
                pfd.use {
                    val result = PdfParser.parse(it)
                    val text = result.pages.joinToString("\n\n") { p ->
                        "[Page ${p.pageIndex}]\n${p.text}"
                    }
                    Triple(text, result.pages.size, result.isScanned)
                }
            }
            "docx" -> {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    val r = DocxParser.parse(ins)
                    Triple(r.text, 0, false)
                } ?: throw IllegalStateException("Cannot open DOCX")
            }
            else -> {
                // txt / md / csv / json / yaml / log / …
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    Triple(TextParser.parse(ins), 0, false)
                } ?: throw IllegalStateException("Cannot open text file")
            }
        }

        if (rawText.isBlank()) {
            throw IllegalStateException("No text extracted from $filename")
        }

        onProgress(Progress("chunk", 0, 1, "Chunking…"))
        val chunks = Chunker.chunk(rawText, targetTokens = 400, overlapRatio = 0.15f)

        val meta = DocumentMeta(
            filename = filename,
            fileType = type,
            pageCount = pageCount,
            totalChars = rawText.length,
            isScannedPdf = isScanned
        )
        val docId = db.insertDocument(meta)

        onProgress(Progress("embed", 0, chunks.size, "Embedding chunks…"))

        chunks.forEachIndexed { idx, text ->
            val prefixed = "search_document: $text"
            val emb = LlamaNative.embed(prefixed)
            val pageHint = extractPageHint(text)
            db.insertChunk(
                Chunk(
                    docId = docId,
                    chunkIndex = idx,
                    pageOrSection = pageHint,
                    text = text,
                    tokenEst = text.length / 4,
                    embedding = emb
                )
            )
            if (idx % 5 == 0 || idx == chunks.lastIndex) {
                onProgress(Progress("embed", idx + 1, chunks.size, "Embedded ${idx + 1}/${chunks.size}"))
            }
        }

        meta.copy(id = docId, chunkCount = chunks.size)
    }

    private fun detectType(filename: String, mime: String): String {
        val lower = filename.lowercase()
        return when {
            mime.contains("pdf") || lower.endsWith(".pdf") -> "pdf"
            mime.contains("wordprocessingml") || lower.endsWith(".docx") -> "docx"
            TextParser.isSupported(filename, mime) -> {
                when {
                    lower.endsWith(".md") || lower.endsWith(".markdown") ||
                    lower.endsWith(".mdown") || lower.endsWith(".mkd") -> "md"
                    lower.endsWith(".csv") || lower.endsWith(".tsv") -> "csv"
                    lower.endsWith(".json") || lower.endsWith(".jsonl") ||
                    lower.endsWith(".ndjson") -> "json"
                    else -> "txt"
                }
            }
            else -> "txt" // still attempt text extraction
        }
    }

    private fun extractPageHint(text: String): String {
        val m = Regex("""\[Page\s+(\d+)\]""").find(text)
        return if (m != null) "p.${m.groupValues[1]}" else ""
    }

    private fun queryName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment
    }
}

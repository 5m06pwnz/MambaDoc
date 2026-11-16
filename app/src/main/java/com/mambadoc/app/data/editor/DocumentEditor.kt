package com.mambadoc.app.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Dependency-free document editing surface.
 *
 * Capability matrix (zero third-party libraries):
 *
 * | Format | Full text edit + save | Append / annotate | "Sign" / stamp | Notes |
 * |--------|-----------------------|-------------------|----------------|-------|
 * | .txt / .md / .csv / .json / … | Yes | Yes | n/a | Lossless UTF-8 rewrite |
 * | .docx | Limited | Limited | n/a | Rebuilds a minimal valid DOCX (formatting may be simplified) |
 * | .pdf  | No (vector text) | Yes (page overlay) | Yes (bitmap stamp) | Uses Android PdfRenderer + PdfDocument; output is a new PDF with pages rasterised + overlays |
 *
 * "Signing" here means placing a user-supplied signature bitmap (or drawn path)
 * as an image stamp on a chosen page — not cryptographic digital signatures
 * (those require certificates / KeyStore integration and are out of scope for
 * a pure air-gapped zero-dep path).
 *
 * All edit operations produce a *new* file under the app's private storage so
 * the original import is never mutated.
 */
class DocumentEditor(private val context: Context) {

    data class TextEditResult(
        val outputFile: File,
        val bytesWritten: Long
    )

    data class PdfAnnotateRequest(
        val pageIndex: Int,          // 0-based
        val text: String? = null,
        val textX: Float = 72f,      // points from left
        val textY: Float = 72f,      // points from bottom (PDF coord)
        val textSizeSp: Float = 12f,
        val signatureBitmap: Bitmap? = null,
        val sigX: Float = 72f,
        val sigY: Float = 72f,
        val sigWidth: Float = 180f,
        val sigHeight: Float = 60f
    )

    // ------------------------------------------------------------------
    // Plain text family (txt, md, csv, json, …) — full fidelity
    // ------------------------------------------------------------------

    fun savePlainText(
        content: String,
        suggestedName: String = "edited.txt"
    ): TextEditResult {
        val dir = File(context.filesDir, "edits").apply { mkdirs() }
        val out = File(dir, safeName(suggestedName))
        OutputStreamWriter(FileOutputStream(out), StandardCharsets.UTF_8).use { w ->
            w.write(content)
        }
        return TextEditResult(out, out.length())
    }

    // ------------------------------------------------------------------
    // DOCX — minimal rebuild (readable by Word / LibreOffice / Google Docs)
    // ------------------------------------------------------------------

    /**
     * Creates a minimal but valid DOCX containing the given plain text.
     * Complex original formatting is *not* preserved; this is intentionally
     * a clean "save as DOCX" path after the agent has rewritten content.
     */
    fun saveAsDocx(
        plainText: String,
        suggestedName: String = "edited.docx"
    ): TextEditResult {
        val dir = File(context.filesDir, "edits").apply { mkdirs() }
        val out = File(dir, safeName(suggestedName.ensureExt("docx")))

        ZipOutputStream(FileOutputStream(out)).use { zip ->
            // [Content_Types].xml
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zip.closeEntry()

            // _rels/.rels
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zip.closeEntry()

            // word/document.xml — one paragraph per blank-line block
            val paragraphs = plainText
                .split(Regex("\n{2,}"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("") { p ->
                    val escaped = p
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\n", "</w:t><w:br/><w:t>")
                    """
                    <w:p>
                      <w:r><w:t xml:space="preserve">$escaped</w:t></w:r>
                    </w:p>
                    """.trimIndent()
                }

            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    $paragraphs
                    <w:sectPr/>
                  </w:body>
                </w:document>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zip.closeEntry()
        }
        return TextEditResult(out, out.length())
    }

    // ------------------------------------------------------------------
    // PDF — annotate / stamp via Android framework only
    // ------------------------------------------------------------------

    /**
     * Renders every page of the source PDF, optionally draws text and/or a
     * signature bitmap on the requested page, then writes a new PDF.
     *
     * Trade-off: the result is a *rasterised* PDF (text is no longer selectable
     * on the stamped page). This is the only robust zero-dependency way to
     * "edit" arbitrary PDFs on Android without a full PDF library.
     */
    fun annotatePdf(
        sourceUri: Uri,
        request: PdfAnnotateRequest,
        suggestedName: String = "annotated.pdf"
    ): TextEditResult {
        val dir = File(context.filesDir, "edits").apply { mkdirs() }
        val out = File(dir, safeName(suggestedName.ensureExt("pdf")))

        val pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
            ?: throw IllegalStateException("Cannot open source PDF")

        pfd.use { src ->
            PdfRenderer(src).use { renderer ->
                val doc = PdfDocument()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = request.textSizeSp * 2.5f // rough pt → px for 72 dpi canvas
                    typeface = Typeface.DEFAULT
                }

                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val w = page.width
                        val h = page.height
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        if (i == request.pageIndex) {
                            // Text overlay (Canvas Y is top-down; PDF Y is bottom-up)
                            request.text?.let { t ->
                                val yFromTop = h - request.textY
                                canvas.drawText(t, request.textX, yFromTop, paint)
                            }
                            // Signature stamp
                            request.signatureBitmap?.let { sig ->
                                val scaled = Bitmap.createScaledBitmap(
                                    sig,
                                    request.sigWidth.toInt().coerceAtLeast(1),
                                    request.sigHeight.toInt().coerceAtLeast(1),
                                    true
                                )
                                val yFromTop = h - request.sigY - request.sigHeight
                                canvas.drawBitmap(scaled, request.sigX, yFromTop, null)
                                if (scaled !== sig) scaled.recycle()
                            }
                        }

                        val pageInfo = PdfDocument.PageInfo.Builder(w, h, i + 1).create()
                        val pdfPage = doc.startPage(pageInfo)
                        pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        doc.finishPage(pdfPage)
                        bitmap.recycle()
                    }
                }

                FileOutputStream(out).use { fos -> doc.writeTo(fos) }
                doc.close()
            }
        }
        return TextEditResult(out, out.length())
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        return if (cleaned.isBlank()) "edit_${System.currentTimeMillis()}" else cleaned
    }

    private fun String.ensureExt(ext: String): String {
        return if (this.lowercase().endsWith(".$ext")) this else "$this.$ext"
    }
}

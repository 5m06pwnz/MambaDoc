package com.mambadoc.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mambadoc.app.data.editor.DocumentEditor
import com.mambadoc.app.data.crypto.CryptoSigner
import com.mambadoc.app.data.parser.DocumentIngester
import com.mambadoc.app.data.db.DocDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tool surface the local agent (LLM) can invoke.
 *
 * Because we run fully offline with Mamba-2 hybrids that may not have
 * native function-calling, tools are exposed both as:
 *   1. Direct Kotlin API for the UI / future structured callers
 *   2. A simple XML-ish / JSON-ish action protocol the model can emit
 *      inside its reply, which ChatScreen / RagEngine can detect and run.
 *
 * Supported actions (model can emit one of these blocks):
 *
 *   <tool name="save_text">
 *   filename: notes.md
 *   content: |
 *     ...markdown or plain text...
 *   </tool>
 *
 *   <tool name="save_docx">
 *   filename: draft.docx
 *   content: |
 *     ...plain text that becomes paragraphs...
 *   </tool>
 *
 *   <tool name="annotate_pdf">
 *   doc_id: 42
 *   page: 1
 *   text: Approved 2026-11-16
 *   x: 72
 *   y: 120
 *   </tool>
 *
 *   <tool name="reingest">
 *   path: /data/.../edits/notes.md
 *   </tool>
 */
class AgentTools(
    private val context: Context,
    private val db: DocDatabase
) {
    private val editor = DocumentEditor(context)
    private val ingester = DocumentIngester(context, db)
    private val signer = CryptoSigner(context)

    data class ToolResult(
        val success: Boolean,
        val message: String,
        val outputPath: String? = null,
        val newDocId: Long? = null
    )

    // ------------------------------------------------------------------
    // Direct API (UI + agent)
    // ------------------------------------------------------------------

    suspend fun saveText(content: String, filename: String = "edited.md"): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val r = editor.savePlainText(content, filename)
                ToolResult(true, "Saved plain text: ${r.outputFile.name}", r.outputFile.absolutePath)
            } catch (e: Exception) {
                ToolResult(false, "save_text failed: ${e.message}")
            }
        }

    suspend fun saveDocx(content: String, filename: String = "edited.docx"): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val r = editor.saveAsDocx(content, filename)
                ToolResult(true, "Saved DOCX: ${r.outputFile.name}", r.outputFile.absolutePath)
            } catch (e: Exception) {
                ToolResult(false, "save_docx failed: ${e.message}")
            }
        }

    suspend fun annotatePdf(
        sourceUri: Uri,
        pageIndex: Int,
        text: String? = null,
        textX: Float = 72f,
        textY: Float = 72f,
        signatureBitmap: Bitmap? = null,
        suggestedName: String = "annotated.pdf"
    ): ToolResult = withContext(Dispatchers.IO) {
        try {
            val req = DocumentEditor.PdfAnnotateRequest(
                pageIndex = pageIndex,
                text = text,
                textX = textX,
                textY = textY,
                signatureBitmap = signatureBitmap
            )
            val r = editor.annotatePdf(sourceUri, req, suggestedName)
            ToolResult(true, "Annotated PDF: ${r.outputFile.name}", r.outputFile.absolutePath)
        } catch (e: Exception) {
            ToolResult(false, "annotate_pdf failed: ${e.message}")
        }
    }

    suspend fun reingestFile(path: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) return@withContext ToolResult(false, "File not found: $path")
            val uri = Uri.fromFile(file)
            val meta = ingester.ingest(uri) { /* progress ignored for tool path */ }
            ToolResult(true, "Re-ingested as doc #${meta.id} (${meta.filename})", path, meta.id)
        } catch (e: Exception) {
            ToolResult(false, "reingest failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Parse model-emitted tool blocks from a reply string
    // ------------------------------------------------------------------

    /**
     * Scans assistant text for <tool name="...">...</tool> blocks and
     * executes them in order. Returns the human-readable results so the
     * UI can append them as system notes.
     */
    suspend fun executeToolsInReply(reply: String): List<ToolResult> {
        val results = mutableListOf<ToolResult>()
        val regex = Regex(
            """<tool\s+name="([a-z_]+)"\s*>(.*?)</tool>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (m in regex.findAll(reply)) {
            val name = m.groupValues[1].lowercase()
            val body = m.groupValues[2].trim()
            val fields = parseFields(body)
            results += when (name) {
                "save_text" -> {
                    val content = fields["content"] ?: body
                    val filename = fields["filename"] ?: "agent_edit.md"
                    saveText(content, filename)
                }
                "save_docx" -> {
                    val content = fields["content"] ?: body
                    val filename = fields["filename"] ?: "agent_edit.docx"
                    saveDocx(content, filename)
                }
                "annotate_pdf" -> {
                    // Requires a previously known source URI – UI must supply context.
                    // For pure agent path we only support text stamp when a doc_id
                    // maps to a file the app already has open; otherwise report guidance.
                    ToolResult(
                        false,
                        "annotate_pdf from chat needs a source document selected in the UI " +
                                "(page=${fields["page"]}, text=${fields["text"]}). " +
                                "Use the Edit screen for PDF stamps, or pass a file path via reingest after manual annotate."
                    )
                }
                "reingest" -> fields["path"]?.let { reingestFile(it) }
                    ?: ToolResult(false, "Missing path")
                "sign_file" -> fields["path"]?.let { signFile(it) }
                    ?: ToolResult(false, "Missing path")
                "verify_file" -> fields["path"]?.let { verifyFile(it) }
                    ?: ToolResult(false, "Missing path")
                else -> ToolResult(false, "Unknown tool: $name")
            }
        }
        return results
    }

    /** Very small key: value / content: | block parser. Only known tool keys are recognized. */
    private fun parseFields(body: String): Map<String, String> {
        val knownKeys = setOf("filename", "content", "path", "page", "text", "doc_id", "x", "y")
        val map = mutableMapOf<String, String>()
        val lines = body.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase()
                if (key !in knownKeys) {
                    i++
                    continue
                }
                var value = line.substring(colon + 1).trim()
                if (value == "|") {
                    // literal block until next known key or end
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size) {
                        val next = lines[i]
                        val nextColon = next.indexOf(':')
                        if (nextColon > 0) {
                            val nextKey = next.substring(0, nextColon).trim().lowercase()
                            if (nextKey in knownKeys && !next.trimStart().startsWith(" ")) break
                        }
                        if (buf.isNotEmpty()) buf.append('\n')
                        buf.append(next)
                        i++
                    }
                    value = buf.toString().trim()
                    map[key] = value
                    continue
                } else {
                    map[key] = value
                }
            }
            i++
        }
        return map
    }


    suspend fun signFile(path: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val file = java.io.File(path)
            val r = signer.signFile(file)
            if (r.success) {
                ToolResult(true, r.message, r.signatureFile?.absolutePath)
            } else {
                ToolResult(false, r.message)
            }
        } catch (e: Exception) {
            ToolResult(false, "sign_file failed: ${e.message}")
        }
    }

    suspend fun verifyFile(path: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val file = java.io.File(path)
            val r = signer.verifyFile(file)
            ToolResult(r.valid, r.message + (r.signedAt?.let { " (signed_at=$it)" } ?: ""))
        } catch (e: Exception) {
            ToolResult(false, "verify_file failed: ${e.message}")
        }
    }

    companion object {
        /** Inject into system / tool prompt so the model knows the protocol. */
        val TOOL_PROMPT_FRAGMENT = """
You can request document edits by emitting tool blocks. The app will execute them offline.

Examples:

<tool name="save_text">
filename: summary.md
content: |
  # Summary
  - Point one
  - Point two
</tool>

<tool name="save_docx">
filename: rewrite.docx
content: |
  Paragraph one.

  Paragraph two.
</tool>

<tool name="reingest">
path: /absolute/path/returned/by/previous/save
</tool>

Rules:
- Only emit a tool block when the user asked you to create or save a file.
- Prefer markdown (.md) for notes and drafts.
- Use save_docx only when a Word-compatible file is required.
- PDF visual stamps are done in the Edit screen.
- Cryptographic detached signatures:

<tool name="sign_file">
path: /absolute/path/to/file.pdf
</tool>

<tool name="verify_file">
path: /absolute/path/to/file.pdf
</tool>

Produces file.sig + file.sig.json (ECDSA-SHA256, Android KeyStore).
""".trimIndent()
    }
}

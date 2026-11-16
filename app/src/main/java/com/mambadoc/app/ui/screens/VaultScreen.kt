package com.mambadoc.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mambadoc.app.MambaDocApp
import com.mambadoc.app.data.model.DocumentMeta
import com.mambadoc.app.data.parser.DocumentIngester
import com.mambadoc.app.data.rag.RagEngine
import com.mambadoc.app.domain.AgentTools
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as MambaDocApp
    val db = app.database
    val scope = rememberCoroutineScope()

    var docs by remember { mutableStateOf(emptyList<DocumentMeta>()) }
    var ingesting by remember { mutableStateOf(false) }
    var progressMsg by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }

    // Edit flow
    var editingDoc by remember { mutableStateOf<DocumentMeta?>(null) }
    var editText by remember { mutableStateOf("") }
    var editSourceUri by remember { mutableStateOf<Uri?>(null) }

    fun refresh() {
        docs = db.getAllDocuments()
    }

    LaunchedEffect(Unit) { refresh() }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            ingesting = true
            val ingester = DocumentIngester(context, db)
            uris.forEach { uri ->
                try {
                    ingester.ingest(uri) { p ->
                        progressMsg = p.message.ifBlank { p.stage }
                        progress = if (p.total > 0) p.current.toFloat() / p.total else 0f
                    }
                } catch (e: Exception) {
                    progressMsg = "Error: ${e.message}"
                }
            }
            ingesting = false
            progressMsg = ""
            refresh()
        }
    }

    // If user is editing, show the edit screen full-screen
    editingDoc?.let { doc ->
        EditDocumentScreen(
            doc = doc,
            sourceUri = editSourceUri,
            initialText = editText,
            onBack = { editingDoc = null },
            onSaved = { path ->
                progressMsg = "Saved: $path"
                // Offer re-ingest
                scope.launch {
                    val tools = AgentTools(context, db)
                    val r = tools.reingestFile(path)
                    progressMsg = r.message
                    refresh()
                }
                editingDoc = null
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Document Vault") },
                actions = {
                    IconButton(onClick = {
                        picker.launch(
                            arrayOf(
                                "application/pdf",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "text/plain",
                                "text/markdown",
                                "text/*",
                                "application/json",
                                "*/*"
                            )
                        )
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Import")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (ingesting) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    progressMsg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp)
                )
            } else if (progressMsg.isNotBlank()) {
                Text(
                    progressMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(8.dp)
                )
            }

            if (docs.isEmpty() && !ingesting) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Import PDF, DOCX, Markdown, TXT, CSV, JSON…\nEverything stays on-device.")
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(docs, key = { it.id }) { doc ->
                        DocumentCard(
                            doc = doc,
                            onDelete = {
                                scope.launch {
                                    db.deleteDocument(doc.id)
                                    refresh()
                                }
                            },
                            onSummarize = {
                                scope.launch {
                                    val engine = RagEngine(db)
                                    val settings = app.settingsRepo.settingsFlow.value
                                    engine.summarizeDocument(
                                        doc.id,
                                        settings,
                                        onProgress = { progressMsg = it },
                                        onToken = {},
                                        onComplete = { refresh() }
                                    )
                                }
                            },
                            onEdit = {
                                // Load full text from chunks for editing
                                scope.launch {
                                    val chunks = db.getChunksForDoc(doc.id)
                                    editText = chunks.sortedBy { it.chunkIndex }
                                        .joinToString("\n\n") { it.text }
                                    editSourceUri = null // original SAF URI not retained; user can re-pick for PDF stamp
                                    editingDoc = doc
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentCard(
    doc: DocumentMeta,
    onDelete: () -> Unit,
    onSummarize: () -> Unit,
    onEdit: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(doc.filename, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "${doc.fileType.uppercase()} · ${doc.pageCount} pages · ${doc.chunkCount} chunks · ${doc.totalChars} chars",
                style = MaterialTheme.typography.bodySmall
            )
            if (doc.isScannedPdf) {
                Text(
                    "⚠ Scanned/Image-only PDF (No selectable text layer)",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!doc.summaryCache.isNullOrBlank()) {
                Text(
                    "Summary available",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
                TextButton(onClick = onSummarize) {
                    Icon(Icons.Default.Summarize, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Summarize")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

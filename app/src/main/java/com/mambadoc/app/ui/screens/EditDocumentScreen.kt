package com.mambadoc.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mambadoc.app.MambaDocApp
import com.mambadoc.app.data.editor.DocumentEditor
import com.mambadoc.app.data.model.DocumentMeta
import com.mambadoc.app.domain.AgentTools
import com.mambadoc.app.data.crypto.CryptoSigner
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Thin edit / annotate / stamp surface.
 *
 * - Plain text family & DOCX: full text editor → Save as .md / .txt / .docx
 * - PDF: choose page, type overlay text, draw signature, stamp → new PDF
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDocumentScreen(
    doc: DocumentMeta,
    sourceUri: Uri?,
    initialText: String,
    onBack: () -> Unit,
    onSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as MambaDocApp
    val scope = rememberCoroutineScope()
    val tools = remember { AgentTools(context, app.database) }

    var text by remember { mutableStateOf(initialText) }
    var status by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    // PDF-specific
    var pageIndex by remember { mutableIntStateOf(0) }
    var stampText by remember { mutableStateOf("") }
    var showSignaturePad by remember { mutableStateOf(false) }
    var signatureBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val isPdf = doc.fileType == "pdf"
    val isDocx = doc.fileType == "docx"
    val isPlain = !isPdf && !isDocx

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit · ${doc.filename}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isPdf) {
                        IconButton(
                            enabled = !saving && text.isNotBlank(),
                            onClick = {
                                saving = true
                                scope.launch {
                                    val name = when {
                                        isDocx -> doc.filename.removeSuffix(".docx") + "_edited.docx"
                                        doc.filename.endsWith(".md", true) -> doc.filename
                                        else -> doc.filename.substringBeforeLast('.') + "_edited.md"
                                    }
                                    val result = if (isDocx) {
                                        tools.saveDocx(text, name)
                                    } else {
                                        tools.saveText(text, name)
                                    }
                                    status = result.message
                                    saving = false
                                    if (result.success && result.outputPath != null) {
                                        onSaved(result.outputPath)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (status.isNotBlank()) {
                Text(status, color = MaterialTheme.colorScheme.primary)
            }

            when {
                isPlain || isDocx -> {
                    Text(
                        if (isDocx) "DOCX will be saved as a clean minimal document (formatting simplified)."
                        else "Plain-text / Markdown — lossless save.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 320.dp),
                        label = { Text("Content") },
                        maxLines = Int.MAX_VALUE
                    )
                    if (isDocx) {
                        TextButton(
                            onClick = {
                                saving = true
                                scope.launch {
                                    val r = tools.saveDocx(text, doc.filename.removeSuffix(".docx") + "_edited.docx")
                                    status = r.message
                                    saving = false
                                    r.outputPath?.let { onSaved(it) }
                                }
                            },
                            enabled = !saving
                        ) { Text("Save as DOCX") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    saving = true
                                    scope.launch {
                                        val r = tools.saveText(text, doc.filename.substringBeforeLast('.') + ".md")
                                        status = r.message
                                        saving = false
                                        r.outputPath?.let { onSaved(it) }
                                    }
                                },
                                enabled = !saving
                            ) { Text("Save as Markdown") }
                            TextButton(
                                onClick = {
                                    saving = true
                                    scope.launch {
                                        val r = tools.saveText(text, doc.filename.substringBeforeLast('.') + ".txt")
                                        status = r.message
                                        saving = false
                                        r.outputPath?.let { onSaved(it) }
                                    }
                                },
                                enabled = !saving
                            ) { Text("Save as TXT") }
                        }
                    }
                }

                isPdf -> {
                    Text(
                        "PDF annotation is raster-based (framework only). " +
                                "Text and signature are stamped onto the chosen page; " +
                                "a new PDF is written. Original is never modified.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (sourceUri == null) {
                        Text(
                            "No source URI available for this document. Re-import the PDF to annotate.",
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        OutlinedTextField(
                            value = pageIndex.toString(),
                            onValueChange = { v -> pageIndex = v.toIntOrNull()?.coerceAtLeast(0) ?: 0 },
                            label = { Text("Page index (0-based)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = stampText,
                            onValueChange = { stampText = it },
                            label = { Text("Overlay text (optional)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showSignaturePad = true }) {
                                Text(if (signatureBitmap != null) "Redraw signature" else "Draw signature")
                            }
                            if (signatureBitmap != null) {
                                TextButton(onClick = { signatureBitmap = null }) {
                                    Text("Clear signature")
                                }
                            }
                        }
                        Button(
                            onClick = {
                                if (sourceUri == null) return@Button
                                saving = true
                                scope.launch {
                                    val r = tools.annotatePdf(
                                        sourceUri = sourceUri,
                                        pageIndex = pageIndex,
                                        text = stampText.ifBlank { null },
                                        signatureBitmap = signatureBitmap,
                                        suggestedName = doc.filename.removeSuffix(".pdf") + "_signed.pdf"
                                    )
                                    status = r.message
                                    saving = false
                                    r.outputPath?.let { onSaved(it) }
                                }
                            },
                            enabled = !saving && (stampText.isNotBlank() || signatureBitmap != null)
                        ) {
                            Text(if (saving) "Stamping…" else "Stamp & Save PDF")
                        }
                    }
                }
            }
        }
    }

    if (showSignaturePad) {
        SignaturePadDialog(
            onDismiss = { showSignaturePad = false },
            onDone = { bmp ->
                signatureBitmap = bmp
                showSignaturePad = false
            }
        )
    }
}

@Composable
private fun SignaturePadDialog(
    onDismiss: () -> Unit,
    onDone: (Bitmap) -> Unit
) {
    val paths = remember { mutableStateListOf<Path>() }
    var currentPath by remember { mutableStateOf(Path()) }
    // Parallel android.graphics.Path list so we can rasterize the real strokes
    val androidPaths = remember { mutableStateListOf<android.graphics.Path>() }
    var currentAndroidPath by remember { mutableStateOf(android.graphics.Path()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw signature") },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(Color.White)
            ) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentPath = Path().apply { moveTo(offset.x, offset.y) }
                                    currentAndroidPath = android.graphics.Path().apply {
                                        moveTo(offset.x, offset.y)
                                    }
                                },
                                onDrag = { change, _ ->
                                    currentPath.lineTo(change.position.x, change.position.y)
                                    currentAndroidPath.lineTo(change.position.x, change.position.y)
                                    // force recomposition by replacing list entry
                                    if (paths.isNotEmpty()) paths.removeAt(paths.lastIndex)
                                    paths.add(Path().apply { addPath(currentPath) })
                                },
                                onDragEnd = {
                                    paths.add(Path().apply { addPath(currentPath) })
                                    androidPaths.add(android.graphics.Path(currentAndroidPath))
                                    currentPath = Path()
                                    currentAndroidPath = android.graphics.Path()
                                }
                            )
                        }
                ) {
                    paths.forEach { p ->
                        drawPath(p, color = Color.Black, style = Stroke(width = 4f))
                    }
                    drawPath(currentPath, color = Color.Black, style = Stroke(width = 4f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Rasterise the pad into a Bitmap
                val bmp = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    strokeWidth = 4f
                    style = android.graphics.Paint.Style.STROKE
                    isAntiAlias = true
                }
                // Scale Compose pad coords into the 600×200 bitmap if sizes differ
                val scaleX = 600f / 600f  // pad width is fillMaxWidth; approximate 1:1 for now
                val scaleY = 200f / 180f
                canvas.save()
                canvas.scale(scaleX, scaleY)
                for (p in androidPaths) {
                    canvas.drawPath(p, paint)
                }
                if (!currentAndroidPath.isEmpty) {
                    canvas.drawPath(currentAndroidPath, paint)
                }
                canvas.restore()
                onDone(bmp)
            }) { Text("Use signature") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

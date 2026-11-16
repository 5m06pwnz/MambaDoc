package com.mambadoc.app.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mambadoc.app.MambaDocApp
import com.mambadoc.app.native.LlamaNative
import com.mambadoc.app.data.crypto.CryptoSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as MambaDocApp
    val settings by app.settingsRepo.settingsFlow.collectAsState()
    val scope = rememberCoroutineScope()

    var llmLoaded by remember { mutableStateOf(LlamaNative.isLlmLoaded()) }
    var embedLoaded by remember { mutableStateOf(LlamaNative.isEmbedLoaded()) }
    var status by remember { mutableStateOf("") }
    var ramInfo by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        ramInfo = "Avail: ${mi.availMem / 1_048_576} MB · Total: ${mi.totalMem / 1_048_576} MB"
    }

    fun copyModelToPrivate(uri: Uri, subdir: String): String? {
        val modelsDir = File(context.filesDir, "models/$subdir").apply { mkdirs() }
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
        val dest = File(modelsDir, name)
        context.contentResolver.openInputStream(uri)?.use { ins ->
            FileOutputStream(dest).use { outs -> ins.copyTo(outs) }
        } ?: return null
        return dest.absolutePath
    }

    val llmPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "Copying LLM…"
            val path = withContext(Dispatchers.IO) { copyModelToPrivate(uri, "llm") }
            if (path != null) {
                app.settingsRepo.update { it.copy(llmModelPath = path) }
                status = "Loading LLM (this may take a while)…"
                val ok = withContext(Dispatchers.IO) {
                    LlamaNative.loadLlmModel(
                        path,
                        settings.nCtx,
                        settings.nThreads,
                        settings.lowRamSwap
                    )
                }
                llmLoaded = ok
                status = if (ok) "LLM ready" else "Failed to load LLM"
            } else {
                status = "Copy failed"
            }
        }
    }

    val embedPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "Copying embedder…"
            val path = withContext(Dispatchers.IO) { copyModelToPrivate(uri, "embed") }
            if (path != null) {
                app.settingsRepo.update { it.copy(embedModelPath = path) }
                status = "Loading nomic-embed…"
                val ok = withContext(Dispatchers.IO) {
                    LlamaNative.loadEmbedModel(path, 4)
                }
                embedLoaded = ok
                status = if (ok) "Embedder ready" else "Failed to load embedder"
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Models & Settings") }) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Device RAM", style = MaterialTheme.typography.titleSmall)
            Text(ramInfo, style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()

            Text("Models", style = MaterialTheme.typography.titleMedium)
            Text(
                "LLM: ${if (llmLoaded) "Loaded" else "Not loaded"}\n${settings.llmModelPath.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { llmPicker.launch(arrayOf("*/*")) }) {
                Text("Import LLM .gguf (Nemotron-3 / Granite-4)")
            }

            Text(
                "Embedder: ${if (embedLoaded) "Loaded" else "Not loaded"}\n${settings.embedModelPath.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { embedPicker.launch(arrayOf("*/*")) }) {
                Text("Import nomic-embed-text-v1.5 .gguf")
            }

            if (status.isNotBlank()) {
                Text(status, color = MaterialTheme.colorScheme.primary)
            }

            HorizontalDivider()

            Text("Generation", style = MaterialTheme.typography.titleMedium)

            Text("Context length: ${settings.nCtx}")
            Slider(
                value = settings.nCtx.toFloat(),
                onValueChange = { v ->
                    app.settingsRepo.update { it.copy(nCtx = v.toInt().coerceIn(4096, 32768)) }
                },
                valueRange = 4096f..32768f,
                steps = 6
            )

            Text("Threads (decode): ${settings.nThreads}")
            Slider(
                value = settings.nThreads.toFloat(),
                onValueChange = { v ->
                    app.settingsRepo.update { it.copy(nThreads = v.toInt().coerceIn(2, 12)) }
                },
                valueRange = 2f..12f,
                steps = 9
            )

            Text("Temperature: ${"%.2f".format(settings.temperature)}")
            Slider(
                value = settings.temperature,
                onValueChange = { v -> app.settingsRepo.update { it.copy(temperature = v) } },
                valueRange = 0f..1.5f
            )

            Text("RAG top-K: ${settings.ragTopK}")
            Slider(
                value = settings.ragTopK.toFloat(),
                onValueChange = { v ->
                    app.settingsRepo.update { it.copy(ragTopK = v.toInt().coerceIn(2, 16)) }
                },
                valueRange = 2f..16f,
                steps = 13
            )

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Deep Reasoning (thinking mode)", Modifier.weight(1f))
                Switch(
                    checked = settings.thinkingMode,
                    onCheckedChange = { app.settingsRepo.update { s -> s.copy(thinkingMode = it) } }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Low-RAM Swap Mode", Modifier.weight(1f))
                Switch(
                    checked = settings.lowRamSwap,
                    onCheckedChange = { app.settingsRepo.update { s -> s.copy(lowRamSwap = it) } }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Prevent Screenshots (FLAG_SECURE)", Modifier.weight(1f))
                Switch(
                    checked = settings.flagSecure,
                    onCheckedChange = { app.settingsRepo.update { s -> s.copy(flagSecure = it) } }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Force dark theme", Modifier.weight(1f))
                Switch(
                    checked = settings.forceDark,
                    onCheckedChange = { app.settingsRepo.update { s -> s.copy(forceDark = it) } }
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Text("Cryptographic signing", style = MaterialTheme.typography.titleMedium)
            val fingerprint = remember {
                val s = CryptoSigner(context)
                s.ensureKey()
                s.keyFingerprint() ?: "Key not ready"
            }
            Text("KeyStore ECDSA P-256 (SHA-256)", style = MaterialTheme.typography.bodySmall)
            Text("Fingerprint: $fingerprint", style = MaterialTheme.typography.bodySmall)
            Text(
                "Detached signatures (.sig) are created on-device. No third-party crypto libraries.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "Air-gapped · No INTERNET permission · Models & documents never leave the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

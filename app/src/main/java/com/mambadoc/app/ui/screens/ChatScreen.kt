package com.mambadoc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mambadoc.app.MambaDocApp
import com.mambadoc.app.data.model.ChatMessage
import com.mambadoc.app.data.model.Citation
import com.mambadoc.app.data.rag.RagEngine
import com.mambadoc.app.native.LlamaNative
import com.mambadoc.app.domain.AgentTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as MambaDocApp
    val db = app.database
    val settings by app.settingsRepo.settingsFlow.collectAsState()
    val scope = rememberCoroutineScope()

    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var input by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var selectedDocIds by remember { mutableStateOf<List<Long>?>(null) }
    var showDocPicker by remember { mutableStateOf(false) }
    var expandedCitation by remember { mutableStateOf<Citation?>(null) }

    var docs by remember { mutableStateOf(db.getAllDocuments()) }
    LaunchedEffect(Unit) {
        docs = db.getAllDocuments()
    }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RAG Chat") },
                actions = {
                    TextButton(onClick = { showDocPicker = true }) {
                        Text(
                            if (selectedDocIds == null) "All Docs"
                            else "${selectedDocIds!!.size} selected"
                        )
                    }
                }
            )
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask about your documents…") },
                    maxLines = 4,
                    enabled = !generating
                )
                Spacer(Modifier.width(8.dp))
                if (generating) {
                    IconButton(onClick = { LlamaNative.requestCancel() }) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (input.isBlank()) return@IconButton
                            if (!LlamaNative.isLlmLoaded()) {
                                messages = messages + ChatMessage(
                                    role = ChatMessage.Role.SYSTEM,
                                    content = "Load an LLM model in the Models tab first."
                                )
                                return@IconButton
                            }
                            val q = input
                            input = ""
                            messages = messages + ChatMessage(role = ChatMessage.Role.USER, content = q)
                            generating = true
                            val assistantId = java.util.UUID.randomUUID().toString()
                            messages = messages + ChatMessage(
                                id = assistantId,
                                role = ChatMessage.Role.ASSISTANT,
                                content = ""
                            )
                            scope.launch {
                                val engine = RagEngine(db)
                                val buf = StringBuilder()
                                val thinkBuf = StringBuilder()
                                engine.answer(
                                    query = q,
                                    settings = settings,
                                    selectedDocIds = selectedDocIds,
                                    onToken = { t ->
                                        buf.append(t)
                                        val snapshot = buf.toString()
                                        scope.launch(Dispatchers.Main) {
                                            messages = messages.map {
                                                if (it.id == assistantId) it.copy(content = snapshot)
                                                else it
                                            }
                                        }
                                    },
                                    onThinking = { t ->
                                        thinkBuf.append(t)
                                        val snapshot = thinkBuf.toString()
                                        scope.launch(Dispatchers.Main) {
                                            messages = messages.map {
                                                if (it.id == assistantId) it.copy(thinking = snapshot)
                                                else it
                                            }
                                        }
                                    },
                                    onComplete = { cites ->
                                        scope.launch(Dispatchers.Main) {
                                            messages = messages.map {
                                                if (it.id == assistantId) it.copy(citations = cites)
                                                else it
                                            }
                                            generating = false
                                        }
                                        // Execute any <tool> blocks the model emitted
                                        scope.launch {
                                            val reply = messages.find { it.id == assistantId }?.content ?: ""
                                            val tools = AgentTools(context, db)
                                            val results = tools.executeToolsInReply(reply)
                                            if (results.isNotEmpty()) {
                                                val note = results.joinToString("\n") { r ->
                                                    (if (r.success) "✓ " else "✗ ") + r.message
                                                }
                                                withContext(Dispatchers.Main) {
                                                    messages = messages + ChatMessage(
                                                        role = ChatMessage.Role.SYSTEM,
                                                        content = "Tool results:\n$note"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        },
                        enabled = input.isNotBlank()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, onCitationClick = { expandedCitation = it })
            }
        }
    }

    if (showDocPicker) {
        AlertDialog(
            onDismissRequest = { showDocPicker = false },
            title = { Text("Scope documents") },
            text = {
                Column {
                    TextButton(onClick = {
                        selectedDocIds = null
                        showDocPicker = false
                    }) { Text("All Documents") }
                    docs.forEach { d ->
                        val selected = selectedDocIds?.contains(d.id) == true
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedDocIds = if (selected) {
                                    (selectedDocIds ?: emptyList()) - d.id
                                } else {
                                    (selectedDocIds ?: emptyList()) + d.id
                                }.ifEmpty { null }
                            },
                            label = { Text(d.filename) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDocPicker = false }) { Text("Done") }
            }
        )
    }

    expandedCitation?.let { c ->
        AlertDialog(
            onDismissRequest = { expandedCitation = null },
            title = { Text("${c.docName} · ${c.pageOrSection}") },
            text = { Text(c.chunkText) },
            confirmButton = {
                TextButton(onClick = { expandedCitation = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, onCitationClick: (Citation) -> Unit) {
    val isUser = msg.role == ChatMessage.Role.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                if (!msg.thinking.isNullOrBlank()) {
                    var expanded by remember { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide thinking" else "Show thinking process")
                    }
                    if (expanded) {
                        Text(
                            msg.thinking,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    }
                }
                Text(msg.content)
                if (msg.citations.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    msg.citations.forEach { c ->
                        AssistChip(
                            onClick = { onCitationClick(c) },
                            label = { Text("[${c.docName} · ${c.pageOrSection}]") }
                        )
                    }
                }
            }
        }
    }
}

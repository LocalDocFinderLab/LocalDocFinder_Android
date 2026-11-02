package com.example.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.llm.ChatEngineStatus
import com.example.engine.llm.ChatMessage
import com.example.engine.llm.ChatModel
import com.example.engine.llm.ChatSource
import com.example.engine.llm.DocumentChatController
import com.example.engine.llm.LlmDownloadWorker.DownloadState

/**
 * Full-screen "Chat with your files": ask questions, get answers from an on-device model with the excerpts
 * they came from. Models are picked, downloaded and deleted from the model sheet (chip in the top bar).
 */
@Composable
fun DocumentChatScreen(
    controller: DocumentChatController,
    onOpenSource: (ChatSource) -> Unit,
    onDismiss: () -> Unit
) {
    val messages by controller.messages.collectAsState()
    val model by controller.selectedModel.collectAsState()
    val installed by controller.installedModels.collectAsState()
    val downloads by controller.downloads.collectAsState()
    val status by controller.status.collectAsState()
    val focus by controller.focusFile.collectAsState()
    var showModels by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val modelReady = model in installed
    val busy = messages.lastOrNull()?.streaming == true

    LaunchedEffect(Unit) { controller.refreshInstalled() }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize().testTag("document_chat"), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                // Top bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close chat")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Chat with your files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            statusLine(status, model, modelReady),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    AssistChip(
                        onClick = { showModels = true },
                        label = { Text(model.shortName, maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("chat_model_chip")
                    )
                    IconButton(onClick = { controller.clear() }, enabled = messages.isNotEmpty()) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear chat")
                    }
                }
                HorizontalDivider()

                focus?.let { (_, name) ->
                    InputChip(
                        selected = true,
                        onClick = { controller.setFocusFile(null, null) },
                        label = { Text("Only this file: $name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Ask about all files", modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                if (!modelReady) {
                    ModelDownloadCard(
                        model = model,
                        state = downloads[model] ?: DownloadState.Idle,
                        onDownload = { controller.download(model) },
                        onCancel = { controller.cancelDownload(model) },
                        onUseMobileData = { controller.setAllowMobileData(true) },
                        onChooseModel = { showModels = true }
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        ChatEmptyState(onAsk = { q -> if (modelReady) controller.send(q) else input = q })
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(messages, key = { it.id }) { message ->
                                if (message.fromUser) UserBubble(message) else AnswerBubble(message, onOpenSource)
                            }
                        }
                    }
                }

                // Input
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).testTag("chat_input"),
                        placeholder = { Text(if (modelReady) "Ask about your documents…" else "Waiting for the model…") },
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    if (busy) {
                        IconButton(onClick = { controller.stop() }, modifier = Modifier.testTag("chat_stop")) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop answering", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(
                            onClick = {
                                controller.send(input)
                                input = ""
                            },
                            enabled = modelReady && input.isNotBlank(),
                            modifier = Modifier.testTag("chat_send")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }

    if (showModels) {
        ChatModelSheet(controller = controller, onDismiss = { showModels = false })
    }
}

private fun statusLine(status: ChatEngineStatus, model: ChatModel, ready: Boolean): String = when (status) {
    is ChatEngineStatus.Loading -> "Loading ${status.model.shortName}…"
    ChatEngineStatus.Searching -> "Searching your files…"
    is ChatEngineStatus.Answering -> "${status.model.shortName} is answering…"
    is ChatEngineStatus.Error -> status.message
    ChatEngineStatus.NoModel -> "No model downloaded"
    ChatEngineStatus.Idle -> if (ready) "${model.shortName} · on-device, offline" else "Model not downloaded yet"
}

@Composable
private fun ModelDownloadCard(
    model: ChatModel,
    state: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUseMobileData: () -> Unit,
    onChooseModel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("${model.displayName} · ${model.sizeLabel}", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            when (state) {
                is DownloadState.Running -> {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Downloading ${ChatModel.formatSize(state.doneBytes)} of ${ChatModel.formatSize(state.totalBytes)} · " +
                            "continues in the background",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                is DownloadState.Waiting -> {
                    Text(
                        if (state.retrying) "Connection lost, retrying soon…" else "Waiting for Wi-Fi to download.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row {
                        TextButton(onClick = onUseMobileData) { Text("Use mobile data") }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                is DownloadState.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row {
                        Button(onClick = onDownload) { Text("Try again") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onChooseModel) { Text("Other models") }
                    }
                }
                DownloadState.Done, DownloadState.Idle -> {
                    Text(
                        "Answers are written on your phone by an open model. It's downloaded once from Hugging Face; " +
                            "your files and questions never leave the phone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Button(onClick = onDownload) { Text("Download") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onChooseModel) { Text("Other models") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatEmptyState(onAsk: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(8.dp))
        Text("Ask anything about your files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "The answer is written by a model running on this phone, from the passages the search finds. " +
                "Nothing is sent to the internet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        listOf(
            "What do my documents say about the contract end date?",
            "How much did I spend on travel?",
            "Summarize my latest medical report",
            "Which files mention my passport number?"
        ).forEach { q ->
            AssistChip(onClick = { onAsk(q) }, label = { Text(q) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            SelectionContainer {
                Text(message.text, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun AnswerBubble(message: ChatMessage, onOpenSource: (ChatSource) -> Unit) {
    var showThinking by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (message.thinking.isNotBlank()) {
                Row(
                    modifier = Modifier.clickable { showThinking = !showThinking },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (message.streaming && message.text.isBlank()) "Thinking…" else "Reasoning",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(if (showThinking) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                if (showThinking) {
                    Text(
                        message.thinking,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
            when {
                message.text.isNotBlank() -> SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyMedium) }
                message.streaming && message.thinking.isBlank() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Reading your files…", style = MaterialTheme.typography.bodySmall)
                }
            }
            message.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
            if (message.sources.isNotEmpty() && !message.streaming) {
                Spacer(Modifier.height(8.dp))
                Text("Sources", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                message.sources.forEach { source ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenSource(source) }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(20.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Text("${source.number}", style = MaterialTheme.typography.labelSmall) }
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            source.fileName + (source.pageLabel?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (!message.streaming && message.modelName != null && message.error == null) {
                Text(
                    "${message.modelName} · on-device",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** Pick, download and delete chat models. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatModelSheet(controller: DocumentChatController, onDismiss: () -> Unit) {
    val selected by controller.selectedModel.collectAsState()
    val installed by controller.installedModels.collectAsState()
    val downloads by controller.downloads.collectAsState()
    val mobileData by controller.allowMobileData.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Chat models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Open models that run entirely on this phone. Downloaded once from Hugging Face.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Download on mobile data", style = MaterialTheme.typography.bodyMedium)
                    Text("Off: downloads wait for Wi-Fi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = mobileData, onCheckedChange = { controller.setAllowMobileData(it) })
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ChatModel.entries.forEach { model ->
                ChatModelRow(
                    model = model,
                    isSelected = model == selected,
                    isInstalled = model in installed,
                    state = downloads[model] ?: DownloadState.Idle,
                    onSelect = { controller.selectModel(model) },
                    onDownload = { controller.download(model) },
                    onCancel = { controller.cancelDownload(model) },
                    onDelete = { controller.deleteModel(model) }
                )
                HorizontalDivider()
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ChatModelRow(
    model: ChatModel,
    isSelected: Boolean,
    isInstalled: Boolean,
    state: DownloadState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(enabled = isInstalled) { onSelect() }.padding(vertical = 10.dp)
            .testTag("chat_model_${model.id}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSelected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(model.displayName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (model == ChatModel.DEFAULT) {
                Text("Default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(model.description, style = MaterialTheme.typography.bodySmall)
        Text(
            "${model.sizeLabel} · ${model.licence} · ${model.minRamGb}+ GB RAM",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        when {
            isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isSelected) TextButton(onClick = onSelect) { Text("Use") } else Text("In use", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete ${model.shortName}") }
            }
            state is DownloadState.Running -> Column {
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${(state.fraction * 100).toInt()}% · ${ChatModel.formatSize(state.doneBytes)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            state is DownloadState.Waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.retrying) "Retrying soon…" else "Waiting for Wi-Fi", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (state is DownloadState.Failed) {
                    Text(state.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                OutlinedButton(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state is DownloadState.Failed) "Retry" else "Download")
                }
            }
        }
    }
}

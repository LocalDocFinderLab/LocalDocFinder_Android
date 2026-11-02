package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Release highlights for the installed version, shown once after an install or update. */
object WhatsNew {

    data class Item(val title: String, val description: String)

    val features: List<Item> = listOf(
        Item(
            "Chat with your files",
            "Ask questions in plain language (\"How much was my last electricity bill?\") and get an answer written by an AI model running on your phone, with the files and pages it came from. Open it from the chat button at the top or from the menu. Nothing is sent to the internet."
        ),
        Item(
            "Choose your model",
            "Qwen2.5 0.5B is downloaded automatically on Wi-Fi after install. You can also pick Qwen2.5 1.5B, DeepSeek-R1 1.5B (reasons step by step), TinyLlama 1.1B or SmolLM 135M (for older phones) — all open models."
        ),
        Item(
            "Ask about one file",
            "In a document's details, tap the chat button to ask questions about that file only, for example \"summarize this\"."
        )
    )

    val development: List<String> = listOf(
        "New engine/llm: ChatModel (catalog of open litert-community .task models), LlmModelStore (storage, resumable Range downloads, Hugging Face file-list fallback), LlmDownloadWorker (WorkManager, Wi-Fi by default, foreground notification with Cancel), LocalLlmEngine (MediaPipe LLM Inference, CPU, streaming, cancel) and DocumentChatController (RAG over HybridSearchEngine).",
        "ChatPromptBuilder fits excerpts, the question and the previous turn into the model's KV-cache window (read from the ekvNNNN file name), applies the ChatML / Zephyr / DeepSeek-R1 template and splits <think> reasoning from the answer.",
        "The default model is queued on every app start until a chat model is installed, unless the user cancelled it or deleted all models.",
        "Model downloads still go through privacy/NetworkGuard (Hugging Face only, GET/HEAD only).",
        "versionCode 20000 (2.0.0)."
    )
}

@Composable
fun WhatsNewDialog(
    versionName: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("What's new in $versionName") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("whats_new_content"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WhatsNew.features.forEach { item ->
                    Column {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.size(2.dp))
                Text(
                    text = "Under the hood",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                WhatsNew.development.forEach { line ->
                    Row {
                        Text("•", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(end = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("whats_new_done")) {
                Text("Got it")
            }
        }
    )
}

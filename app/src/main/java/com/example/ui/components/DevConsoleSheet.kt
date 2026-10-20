package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.dev.DevLog
import com.example.ui.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A snapshot of app state for the console, filled in by the screen from the view model. */
data class DevConsoleInfo(
    val indexingState: String,
    val totalFiles: Int,
    val totalChunks: Int,
    val staleChunks: Int,
    val activeModel: String,
    val runningModel: String,
    val installedModels: String,
    val backend: String,
    val quarantined: Int,
    val safeMode: Boolean
)

/** Test-data and diagnostic actions that only exist in the dev build. */
data class DevConsoleActions(
    val onLoadSample: () -> Unit,
    val onLoad100Samples: () -> Unit,
    val onSeedTestDocuments: () -> Unit,
    val onSeedSampleChats: () -> Unit,
    val onScanMonitoredFolder: () -> Unit,
    val onOpenHardwareHud: () -> Unit,
    val onShowWhatsNew: () -> Unit
)

/**
 * The dev build's Developer console: what the app sends over the network, the logs, the current state and
 * the sample-data tools. Not reachable in the prod build.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevConsoleSheet(info: DevConsoleInfo, actions: DevConsoleActions, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Network", "Logs", "State", "Tools")
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.92f).testTag("dev_console")) {
            Text(
                text = "Developer console · ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }, modifier = Modifier.testTag("dev_tab_$t"))
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { PrivacyReportContent(detailed = true) }
                    1 -> LogsTab()
                    2 -> StateTab(info)
                    else -> ToolsTab(actions)
                }
            }
        }
    }
}

private enum class LogLevel(val label: String, val spec: String) {
    ALL("All", "*:V"), INFO("Info+", "*:I"), WARN("Warn+", "*:W"), ERROR("Errors", "*:E")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogsTab() {
    val context = LocalContext.current
    var level by remember { mutableStateOf(LogLevel.INFO) }
    var events by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("Reading…") }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(level, events, reload) {
        text = withContext(Dispatchers.IO) {
            if (events) DevLog.snapshot().joinToString("\n").ifBlank { "No app events yet." } else readLogcat(level)
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = events, onClick = { events = true }, label = { Text("App events") })
            FilterChip(selected = !events, onClick = { events = false }, label = { Text("System log") })
            if (!events) LogLevel.entries.forEach { l ->
                FilterChip(selected = level == l, onClick = { level = l }, label = { Text(l.label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            OutlinedButton(onClick = { reload++ }) { Text("Refresh") }
            OutlinedButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("LocalDoc log", text))
            }) { Text("Copy") }
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                        "Share log"
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("Share") }
            if (events) OutlinedButton(onClick = { DevLog.clear(); reload++ }) { Text("Clear") }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
        ) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, softWrap = false)
        }
    }
}

/** This app's own log lines (the system only lets an app read its own), newest last, capped. */
private fun readLogcat(level: LogLevel): String = try {
    val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "600", "--pid=${Process.myPid()}", level.spec)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().use { it.readText() }.ifBlank { "Nothing logged at this level yet." }
} catch (e: Exception) {
    "Could not read the system log: ${e.message}"
}

@Composable
private fun StateTab(info: DevConsoleInfo) {
    val context = LocalContext.current
    val rows = listOf(
        "Build" to "${BuildConfig.APPLICATION_ID} · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()}",
        "All files access" to StorageAccess.hasAccess(context).toString(),
        "Missing runtime permissions" to StorageAccess.missingRuntimePermissions(context).joinToString().ifBlank { "none" },
        "Indexing" to info.indexingState,
        "Index size" to "${info.totalFiles} files · ${info.totalChunks} chunks",
        "Chunks needing re-index" to info.staleChunks.toString(),
        "Selected model" to info.activeModel,
        "Running model" to info.runningModel,
        "Installed models" to info.installedModels,
        "Execution backend" to info.backend,
        "Quarantined files" to info.quarantined.toString(),
        "Safe mode" to info.safeMode.toString()
    )
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(v, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ToolsTab(actions: DevConsoleActions) {
    @Composable
    fun Tool(label: String, hint: String, tag: String, onClick: () -> Unit) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(label) }
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Sample data", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Tool("Load sample knowledge base", "A handful of technical papers, indexed with the running model.", "dev_load_sample", actions.onLoadSample)
        Tool("Load 100 sample files", "Generates and embeds 100 research papers; good for timing the indexer.", "dev_load_100", actions.onLoad100Samples)
        Tool("Seed 100 test files on disk", "Writes text, Markdown and PDF files to the app's external files folder, then indexes them.", "dev_seed_files", actions.onSeedTestDocuments)
        Tool("Load sample chat backups", "SMS and WhatsApp style backups for the chat importer.", "dev_seed_chats", actions.onSeedSampleChats)
        Spacer(Modifier.height(8.dp))
        Text("Diagnostics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Tool("Scan monitored folder now", "Runs the folder monitor immediately instead of waiting for its schedule.", "dev_scan_monitored", actions.onScanMonitoredFolder)
        Tool("Hardware & thermal HUD", "Live CPU, memory and thermal state while indexing.", "dev_hardware_hud", actions.onOpenHardwareHud)
        Tool("Show What's new", "Opens the release notes dialog again.", "dev_whats_new", actions.onShowWhatsNew)
    }
}

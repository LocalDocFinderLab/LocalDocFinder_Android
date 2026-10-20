package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privacy.NetworkAudit
import com.example.privacy.NetworkGuard
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everyone's view of "what leaves my phone": plain totals, no jargon. */
@Composable
fun PrivacyReportDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Color(0xFF10B981)) },
        title = { Text("Privacy report") },
        text = { PrivacyReportContent(detailed = false) },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("btn_privacy_close")) { Text("Close") } }
    )
}

/**
 * What the app has sent to and received from the internet, counted by the app's own network gate
 * ([NetworkGuard]). With [detailed] (dev build) it adds Android's own count for the app and every request.
 */
@Composable
fun PrivacyReportContent(detailed: Boolean) {
    // Live numbers while a model or update downloads.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        NetworkAudit.flush()
        while (true) {
            delay(1000)
            tick++
        }
    }
    val totals = remember(tick) { NetworkAudit.totals() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Your documents, your searches and your index never leave this phone. " +
                "The app has no way to upload them: its network gate only allows plain downloads from a short list of hosts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ReportRow("Uploaded: documents / searches / index", "0 bytes", highlight = true)
        ReportRow("Downloaded (models, app updates)", NetworkAudit.formatBytes(totals.receivedBytes))
        ReportRow("Download requests made", "${totals.requests}")
        ReportRow("Requests the gate refused", "${totals.blocked}")
        ReportRow("Sent in those requests", "${NetworkAudit.formatBytes(totals.sentBytes)} (addresses only)")
        Text(
            text = "Allowed hosts: ${NetworkGuard.allowedHostsDescription}. Totals are since you installed the app.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (detailed) DetailedNetwork(tick)
    }
}

@Composable
private fun DetailedNetwork(tick: Int) {
    val (ledgerSent, ledgerReceived) = remember(tick) { NetworkAudit.sessionTotals() }
    val os = remember(tick) { NetworkAudit.osTrafficSinceLaunch() }
    Spacer(modifier = Modifier.height(4.dp))
    Text("This run: the app's gate vs Android's own count", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    ReportRow("Gate: sent / received", "${NetworkAudit.formatBytes(ledgerSent)} / ${NetworkAudit.formatBytes(ledgerReceived)}")
    ReportRow(
        "Android: sent / received",
        if (os == null) "not reported by this device" else "${NetworkAudit.formatBytes(os.first)} / ${NetworkAudit.formatBytes(os.second)}"
    )
    Text(
        text = "Android counts TLS handshakes and packet overhead too, so its numbers are a little higher. A large gap " +
            "(many KB while nothing was downloading) would mean a library is talking on its own, outside the gate.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = "Voice search is handled by Android's speech service, not by this app, so it does not show up here.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(4.dp))
    Text("Requests this run (newest first)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    val entries = remember(tick) { NetworkAudit.recent() }
    if (entries.isEmpty()) {
        Text("None yet. The app has not touched the network since it started.", style = MaterialTheme.typography.bodySmall)
    }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    entries.forEach { e ->
        val ok = !e.blocked && e.error == null && e.status in 200..399
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    (if (e.blocked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant).copy(alpha = 0.5f),
                    RoundedCornerShape(10.dp)
                )
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${clock.format(Date(e.time))}  ${e.method}  ${e.host}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = when {
                        e.blocked -> "BLOCKED"
                        e.error != null -> "FAILED"
                        e.status == 0 -> "…"
                        else -> "${e.status}"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (ok) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                )
            }
            Text(e.path.take(90), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "${e.purpose.label} · sent ${NetworkAudit.formatBytes(e.sentBytes.get())} · received ${NetworkAudit.formatBytes(e.receivedBytes.get())}" +
                    (e.error?.let { " · $it" } ?: ""),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReportRow(label: String, value: String, highlight: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = if (highlight) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
        )
    }
}

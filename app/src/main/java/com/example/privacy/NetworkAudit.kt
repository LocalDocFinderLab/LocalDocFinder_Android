package com.example.privacy

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * A ledger of everything the app sends to and receives from the internet, filled by [NetworkGuard].
 * Lifetime totals survive restarts; the list of individual requests is kept for the current run only.
 *
 * "Sent" counts what leaves the phone: the request line and headers of downloads. The app never sends a
 * request body, so no document text, search or index can be part of it.
 */
object NetworkAudit {

    enum class Purpose(val label: String) {
        MODEL_DOWNLOAD("Model download"),
        APP_UPDATE("App update")
    }

    class Entry(
        val time: Long,
        val purpose: Purpose,
        val method: String,
        val host: String,
        val path: String,
        val blockedReason: String?
    ) {
        @Volatile var status: Int = 0
        @Volatile var error: String? = null
        val sentBytes = AtomicLong(0)
        val receivedBytes = AtomicLong(0)
        val blocked: Boolean get() = blockedReason != null
    }

    data class Totals(val sentBytes: Long, val receivedBytes: Long, val requests: Long, val blocked: Long)

    private const val PREFS = "network_audit"
    private const val MAX_ENTRIES = 300

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()
    private val unsavedReceived = AtomicLong(0)
    private var prefs: android.content.SharedPreferences? = null

    @Volatile private var baseline = Totals(0, 0, 0, 0)
    private var sessionSent = 0L
    private var sessionReceived = 0L
    private var sessionRequests = 0L
    private var sessionBlocked = 0L

    private var osSentAtStart = TrafficStats.UNSUPPORTED.toLong()
    private var osReceivedAtStart = TrafficStats.UNSUPPORTED.toLong()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            osSentAtStart = TrafficStats.getUidTxBytes(Process.myUid())
            osReceivedAtStart = TrafficStats.getUidRxBytes(Process.myUid())
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            baseline = Totals(
                p.getLong("sent", 0), p.getLong("received", 0), p.getLong("requests", 0), p.getLong("blocked", 0)
            )
        }
    }

    internal fun record(
        purpose: Purpose,
        method: String,
        host: String,
        path: String,
        sentBytes: Long,
        blockedReason: String? = null
    ): Entry {
        val e = Entry(System.currentTimeMillis(), purpose, method, host, path, blockedReason)
        if (blockedReason != null) e.error = blockedReason else e.sentBytes.set(sentBytes)
        if (blockedReason != null) com.example.dev.DevLog.w("Network", "$method $host$path -> $blockedReason")
        else com.example.dev.DevLog.i("Network", "${purpose.label}: $method $host$path")
        synchronized(lock) {
            entries.addFirst(e)
            while (entries.size > MAX_ENTRIES) entries.removeLast()
            sessionRequests++
            if (blockedReason != null) sessionBlocked++ else sessionSent += sentBytes
        }
        persist()
        return e
    }

    internal fun responded(entry: Entry, status: Int) {
        entry.status = status
    }

    internal fun failed(entry: Entry, e: Exception) {
        entry.error = e.message ?: e.javaClass.simpleName
    }

    internal fun received(entry: Entry, bytes: Long) {
        entry.receivedBytes.addAndGet(bytes)
        synchronized(lock) { sessionReceived += bytes }
        // Saving after every 8 KB chunk would hammer the disk; save about once per megabyte instead.
        if (unsavedReceived.addAndGet(bytes) >= 1_000_000) persist()
    }

    private fun persist() {
        val p = prefs ?: return
        val t = totals()
        p.edit()
            .putLong("sent", t.sentBytes).putLong("received", t.receivedBytes)
            .putLong("requests", t.requests).putLong("blocked", t.blocked)
            .apply()
        unsavedReceived.set(0)
    }

    /** Flush counters (called when the user opens the privacy report so it is exact). */
    fun flush() = persist()

    fun totals(): Totals = synchronized(lock) {
        Totals(
            baseline.sentBytes + sessionSent,
            baseline.receivedBytes + sessionReceived,
            baseline.requests + sessionRequests,
            baseline.blocked + sessionBlocked
        )
    }

    /**
     * What Android itself counted for this app since it started (sent, received), over every socket in the
     * process: our own downloads and anything a library might do on its own. Null when the device does not
     * report it. It is higher than the ledger by TLS handshakes and packet overhead.
     */
    fun osTrafficSinceLaunch(): Pair<Long, Long>? {
        val sent = TrafficStats.getUidTxBytes(Process.myUid())
        val received = TrafficStats.getUidRxBytes(Process.myUid())
        if (sent == TrafficStats.UNSUPPORTED.toLong() || osSentAtStart < 0) return null
        return Pair((sent - osSentAtStart).coerceAtLeast(0), (received - osReceivedAtStart).coerceAtLeast(0))
    }

    /** Bytes the ledger counted for this run only. */
    fun sessionTotals(): Pair<Long, Long> = synchronized(lock) { Pair(sessionSent, sessionReceived) }

    /** Requests of this run, newest first. */
    fun recent(): List<Entry> = synchronized(lock) { entries.toList() }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

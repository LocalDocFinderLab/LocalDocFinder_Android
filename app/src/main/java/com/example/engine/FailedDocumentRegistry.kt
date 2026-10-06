package com.example.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class QuarantinedDocument(
    val fileUri: String,
    val fileName: String,
    val failureCount: Int,
    val lastFailedTimestamp: Long,
    val reason: String,
    val isHardCrash: Boolean = false
)

/**
 * Persistent quarantine and crash-loop prevention registry for DocuVector.
 *
 * Ensures that if a problematic document (e.g., corrupted PDF, malformed structure,
 * memory exhaustion / OOM, parser hang) causes a crash or failure:
 * 1. The in-progress canary detects abnormal process termination on next launch.
 * 2. Problematic files are automatically quarantined after failures or hard crashes.
 * 3. Background workers (FolderMonitor, PdfSync, ContinuousSync, DownloadsFileObserver)
 *    and startup crawlers NEVER re-process quarantined files, eliminating crash loops.
 * 4. Safe Mode is engaged if a startup crash loop is detected.
 * 5. Users can view skipped files and manually retry or clear them when ready.
 */
object FailedDocumentRegistry {

    private const val TAG = "FailedDocRegistry"
    private const val PREFS_NAME = "docuvector_failed_docs_registry"

    private const val KEY_FAILED_DOCS = "failed_documents_json"
    private const val KEY_IN_PROGRESS_URI = "in_flight_uri"
    private const val KEY_IN_PROGRESS_NAME = "in_flight_name"
    private const val KEY_IN_PROGRESS_TIME = "in_flight_timestamp"
    private const val KEY_SAFE_MODE = "safe_mode_engaged"
    private const val KEY_CRASH_AVERTED_MSG = "crash_averted_message"

    const val MAX_FAILURES_BEFORE_QUARANTINE = 2

    private val _quarantinedCount = MutableStateFlow(0)
    val quarantinedCount: StateFlow<Int> = _quarantinedCount.asStateFlow()

    private val _safeModeActive = MutableStateFlow(false)
    val safeModeActive: StateFlow<Boolean> = _safeModeActive.asStateFlow()

    private val _crashAvertedNotice = MutableStateFlow<String?>(null)
    val crashAvertedNotice: StateFlow<String?> = _crashAvertedNotice.asStateFlow()

    @Volatile
    private var isInitialized = false

    /**
     * Initializes the registry, checks for in-flight crashes from previous process runs,
     * and quarantines any document that caused an abnormal termination.
     */
    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Crash canary check: Was the app in the middle of processing a document when it died?
        val inFlightUri = prefs.getString(KEY_IN_PROGRESS_URI, null)
        val inFlightName = prefs.getString(KEY_IN_PROGRESS_NAME, null) ?: "Document"
        val inFlightTime = prefs.getLong(KEY_IN_PROGRESS_TIME, 0L)

        if (!inFlightUri.isNullOrBlank()) {
            val elapsed = System.currentTimeMillis() - inFlightTime
            Log.w(
                TAG,
                "Crash canary triggered! Process died while processing '$inFlightName' ($inFlightUri). " +
                        "Elapsed: ${elapsed}ms. Quarantining file to prevent crash loop."
            )

            // Immediately quarantine this file as a hard crash
            recordFailureDirect(
                app,
                inFlightUri,
                inFlightName,
                "Process terminated unexpectedly while indexing this document (OOM or native crash averted)",
                isFatalCrash = true
            )

            // Clear in-flight markers
            prefs.edit()
                .remove(KEY_IN_PROGRESS_URI)
                .remove(KEY_IN_PROGRESS_NAME)
                .remove(KEY_IN_PROGRESS_TIME)
                .putBoolean(KEY_SAFE_MODE, false)
                .putString(
                    KEY_CRASH_AVERTED_MSG,
                    "Crash averted: '$inFlightName' caused an unexpected termination and was quarantined. Continuing with other files."
                )
                .apply()

            _safeModeActive.value = false
            _crashAvertedNotice.value = "Quarantined '$inFlightName' to prevent crashes. Indexing can proceed safely."
        } else {
            _safeModeActive.value = false
            _crashAvertedNotice.value = prefs.getString(KEY_CRASH_AVERTED_MSG, null)
        }

        refreshQuarantinedCount(app)
        isInitialized = true
    }

    /**
     * Returns true if [fileUri] has been quarantined and must be skipped.
     */
    fun isQuarantined(context: Context, fileUri: String): Boolean {
        val app = context.applicationContext ?: context
        val map = loadFailedMap(app)
        val doc = map[fileUri] ?: return false
        return doc.failureCount >= MAX_FAILURES_BEFORE_QUARANTINE || doc.isHardCrash
    }

    /**
     * Checks if Safe Mode is currently active to avoid aggressive startup indexing.
     */
    fun isSafeModeActive(context: Context): Boolean {
        val app = context.applicationContext ?: context
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SAFE_MODE, false)
    }

    fun dismissSafeMode(context: Context) {
        val app = context.applicationContext ?: context
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SAFE_MODE, false)
            .remove(KEY_CRASH_AVERTED_MSG)
            .apply()
        _safeModeActive.value = false
        _crashAvertedNotice.value = null
    }

    /**
     * Marks the beginning of indexing for [fileUri] (in-flight crash canary).
     * Synchronously committed so it is recorded immediately even if an immediate OOM or native crash occurs.
     */
    fun markProcessingStart(context: Context, fileUri: String, fileName: String) {
        val app = context.applicationContext ?: context
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_IN_PROGRESS_URI, fileUri)
            .putString(KEY_IN_PROGRESS_NAME, fileName)
            .putLong(KEY_IN_PROGRESS_TIME, System.currentTimeMillis())
            .commit()
    }

    /**
     * Marks completion of indexing for [fileUri] (clearing in-flight crash canary).
     */
    fun markProcessingEnd(context: Context, fileUri: String) {
        val app = context.applicationContext ?: context
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_IN_PROGRESS_URI, null)
        if (current == fileUri) {
            prefs.edit()
                .remove(KEY_IN_PROGRESS_URI)
                .remove(KEY_IN_PROGRESS_NAME)
                .remove(KEY_IN_PROGRESS_TIME)
                .commit()
        }
    }

    /**
     * Called by global uncaught exception handler when an unhandled crash or OOM occurs.
     * Quarantines the current in-flight file and engages Safe Mode immediately.
     */
    fun handleUncaughtCrash(context: Context, throwable: Throwable) {
        val app = context.applicationContext ?: context
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val inFlightUri = prefs.getString(KEY_IN_PROGRESS_URI, null)
        val inFlightName = prefs.getString(KEY_IN_PROGRESS_NAME, null) ?: "Document"
        if (!inFlightUri.isNullOrBlank()) {
            recordFailureDirect(
                app,
                inFlightUri,
                inFlightName,
                "Fatal crash: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}",
                isFatalCrash = true
            )
            prefs.edit()
                .remove(KEY_IN_PROGRESS_URI)
                .remove(KEY_IN_PROGRESS_NAME)
                .remove(KEY_IN_PROGRESS_TIME)
                .putBoolean(KEY_SAFE_MODE, true)
                .putString(
                    KEY_CRASH_AVERTED_MSG,
                    "Crash loop averted: '$inFlightName' crashed (${throwable.javaClass.simpleName}) and was quarantined."
                )
                .commit()
            _safeModeActive.value = true
            _crashAvertedNotice.value = "Safe Mode: '$inFlightName' crashed (${throwable.javaClass.simpleName}) and was quarantined."
        }
    }

    /**
     * Records a successful index for [fileUri], removing it from the failure registry.
     */
    @Synchronized
    fun recordSuccess(context: Context, fileUri: String) {
        val app = context.applicationContext ?: context
        markProcessingEnd(app, fileUri)
        val map = loadFailedMap(app)
        if (map.containsKey(fileUri)) {
            map.remove(fileUri)
            saveFailedMap(app, map)
            refreshQuarantinedCount(app)
        }
    }

    /**
     * Records an indexing failure for [fileUri].
     */
    @Synchronized
    fun recordFailure(
        context: Context,
        fileUri: String,
        fileName: String,
        reason: String,
        isFatalCrash: Boolean = false
    ) {
        val app = context.applicationContext ?: context
        markProcessingEnd(app, fileUri)
        recordFailureDirect(app, fileUri, fileName, reason, isFatalCrash)
    }

    private fun recordFailureDirect(
        context: Context,
        fileUri: String,
        fileName: String,
        reason: String,
        isFatalCrash: Boolean
    ) {
        val map = loadFailedMap(context)
        val existing = map[fileUri]
        val newCount = (existing?.failureCount ?: 0) + 1
        val updated = QuarantinedDocument(
            fileUri = fileUri,
            fileName = fileName,
            failureCount = newCount,
            lastFailedTimestamp = System.currentTimeMillis(),
            reason = reason,
            isHardCrash = isFatalCrash || existing?.isHardCrash == true || newCount >= MAX_FAILURES_BEFORE_QUARANTINE
        )
        map[fileUri] = updated
        saveFailedMap(context, map)
        refreshQuarantinedCount(context)
        Log.w(TAG, "Recorded failure for $fileName (fails=$newCount, quarantined=${updated.isHardCrash}): $reason")
    }

    /**
     * Returns all currently quarantined documents.
     */
    fun getQuarantinedList(context: Context): List<QuarantinedDocument> {
        val app = context.applicationContext ?: context
        return loadFailedMap(app).values.filter {
            it.failureCount >= MAX_FAILURES_BEFORE_QUARANTINE || it.isHardCrash
        }.sortedByDescending { it.lastFailedTimestamp }
    }

    /**
     * Removes a specific document from quarantine so it can be re-tried.
     */
    @Synchronized
    fun unquarantineFile(context: Context, fileUri: String) {
        val app = context.applicationContext ?: context
        val map = loadFailedMap(app)
        map.remove(fileUri)
        saveFailedMap(app, map)
        refreshQuarantinedCount(app)
    }

    /**
     * Clears all quarantined documents.
     */
    @Synchronized
    fun clearAll(context: Context) {
        val app = context.applicationContext ?: context
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_FAILED_DOCS)
            .remove(KEY_IN_PROGRESS_URI)
            .remove(KEY_IN_PROGRESS_NAME)
            .remove(KEY_IN_PROGRESS_TIME)
            .remove(KEY_SAFE_MODE)
            .remove(KEY_CRASH_AVERTED_MSG)
            .apply()
        _quarantinedCount.value = 0
        _safeModeActive.value = false
        _crashAvertedNotice.value = null
    }

    private fun refreshQuarantinedCount(context: Context) {
        val list = getQuarantinedList(context)
        _quarantinedCount.value = list.size
    }

    private fun loadFailedMap(context: Context): MutableMap<String, QuarantinedDocument> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_FAILED_DOCS, null) ?: return mutableMapOf()
        val map = mutableMapOf<String, QuarantinedDocument>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val uri = obj.getString("uri")
                val name = obj.optString("name", "Document")
                val count = obj.optInt("count", 1)
                val time = obj.optLong("time", 0L)
                val reason = obj.optString("reason", "Unknown error")
                val hardCrash = obj.optBoolean("hardCrash", false)
                map[uri] = QuarantinedDocument(uri, name, count, time, reason, hardCrash)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading failed documents map: ${e.message}")
        }
        return map
    }

    private fun saveFailedMap(context: Context, map: Map<String, QuarantinedDocument>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val array = JSONArray()
            for ((_, doc) in map) {
                val obj = JSONObject().apply {
                    put("uri", doc.fileUri)
                    put("name", doc.fileName)
                    put("count", doc.failureCount)
                    put("time", doc.lastFailedTimestamp)
                    put("reason", doc.reason)
                    put("hardCrash", doc.isHardCrash)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_FAILED_DOCS, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving failed documents map: ${e.message}")
        }
    }
}

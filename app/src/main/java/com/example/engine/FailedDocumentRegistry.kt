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
    /** Every file being indexed right now (several run in parallel), as a JSON object uri -> name. */
    private const val KEY_IN_FLIGHT = "in_flight_documents_json"
    private const val KEY_SAFE_MODE = "safe_mode_engaged"
    private const val KEY_CRASH_AVERTED_MSG = "crash_averted_message"
    private const val KEY_REGISTRY_VERSION = "registry_version"

    /**
     * Version 2 stopped quarantining a file after a single timeout or a single process death. Upgrading
     * releases the files that older versions quarantined for only those reasons, so they get retried.
     */
    private const val REGISTRY_VERSION = 2
    private val RETRYABLE_REASON_PREFIXES = listOf("Processing timed out", "Process terminated unexpectedly")

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
     * In-memory copy of the stored failure map. [isQuarantined] runs for every candidate file of every scan, and
     * parsing the JSON from SharedPreferences each time made large scans quadratic.
     */
    @Volatile
    private var cachedMap: Map<String, QuarantinedDocument>? = null

    /**
     * Initializes the registry, checks for in-flight crashes from previous process runs,
     * and quarantines any document that caused an abnormal termination.
     */
    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (prefs.getInt(KEY_REGISTRY_VERSION, 1) < REGISTRY_VERSION) {
            val map = loadFailedMap(app)
            val released = map.values.filter { doc -> RETRYABLE_REASON_PREFIXES.any { doc.reason.startsWith(it) } }
            if (released.isNotEmpty()) {
                released.forEach { map.remove(it.fileUri) }
                saveFailedMap(app, map)
                Log.i(TAG, "Released ${released.size} file(s) quarantined after a timeout or process death")
            }
            prefs.edit().putInt(KEY_REGISTRY_VERSION, REGISTRY_VERSION).apply()
        }

        // 1. Crash canary check: Was the app in the middle of processing documents when it died?
        val inFlight = loadInFlight(prefs)
        if (inFlight.isNotEmpty()) {
            Log.w(TAG, "Crash canary triggered! Process died while processing ${inFlight.values}.")

            // The process can also die for reasons that have nothing to do with the file (the user swiped the app
            // away, the system reclaimed memory, an update was installed), and large files are the ones most
            // likely to be in flight then. So a death counts as one failure; a second one quarantines the file.
            for ((uri, name) in inFlight) {
                recordFailureDirect(
                    app,
                    uri,
                    name,
                    "Process terminated unexpectedly while indexing this document (OOM or native crash averted)",
                    isFatalCrash = false
                )
            }
            val names = inFlight.values.joinToString(", ") { "'$it'" }

            prefs.edit()
                .remove(KEY_IN_FLIGHT)
                .putBoolean(KEY_SAFE_MODE, false)
                .putString(
                    KEY_CRASH_AVERTED_MSG,
                    "Indexing of $names was interrupted. It will be retried once, then skipped if it fails again."
                )
                .apply()

            _safeModeActive.value = false
            _crashAvertedNotice.value = "Indexing of $names was interrupted and will be retried once."
        } else {
            // A file that crashed the app is quarantined on its own; it must never leave the whole app in safe mode.
            prefs.edit().putBoolean(KEY_SAFE_MODE, false).apply()
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
        val map = cachedMap ?: loadFailedMap(app)
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
    @Synchronized
    fun markProcessingStart(context: Context, fileUri: String, fileName: String) {
        val prefs = prefs(context)
        val inFlight = loadInFlight(prefs)
        inFlight[fileUri] = fileName
        saveInFlight(prefs, inFlight)
    }

    /**
     * Marks completion of indexing for [fileUri] (clearing in-flight crash canary).
     */
    @Synchronized
    fun markProcessingEnd(context: Context, fileUri: String) {
        val prefs = prefs(context)
        val inFlight = loadInFlight(prefs)
        if (inFlight.remove(fileUri) != null) {
            saveInFlight(prefs, inFlight)
        }
    }

    /**
     * Called by global uncaught exception handler when an unhandled crash or OOM occurs.
     * Counts a failure for each in-flight file; auto-scan of every other file continues.
     */
    @Synchronized
    fun handleUncaughtCrash(context: Context, throwable: Throwable) {
        val app = context.applicationContext ?: context
        val prefs = prefs(app)
        val inFlight = loadInFlight(prefs)
        if (inFlight.isEmpty()) return
        // With several files in flight the culprit is unknown, and the crash may not be any file's fault.
        // Only an OutOfMemoryError during a single file is blamed on that file outright.
        val blameOne = inFlight.size == 1 && throwable is OutOfMemoryError
        for ((uri, name) in inFlight) {
            recordFailureDirect(
                app,
                uri,
                name,
                "Fatal crash: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}",
                isFatalCrash = blameOne
            )
        }
        val names = inFlight.values.joinToString(", ") { "'$it'" }
        prefs.edit()
            .remove(KEY_IN_FLIGHT)
            .putBoolean(KEY_SAFE_MODE, false)
            .putString(
                KEY_CRASH_AVERTED_MSG,
                "Indexing of $names crashed (${throwable.javaClass.simpleName}). Other files keep indexing."
            )
            .commit()
    }

    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadInFlight(prefs: android.content.SharedPreferences): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            prefs.getString(KEY_IN_FLIGHT, null)?.let { json ->
                val obj = JSONObject(json)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next().toString()
                    map[key] = obj.optString(key, "Document")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading in-flight documents: ${e.message}")
        }
        // Single-file marker written by older versions.
        prefs.getString(KEY_IN_PROGRESS_URI, null)?.takeIf { it.isNotBlank() }?.let { uri ->
            map[uri] = prefs.getString(KEY_IN_PROGRESS_NAME, null) ?: "Document"
        }
        return map
    }

    private fun saveInFlight(prefs: android.content.SharedPreferences, inFlight: Map<String, String>) {
        val editor = prefs.edit()
            .remove(KEY_IN_PROGRESS_URI)
            .remove(KEY_IN_PROGRESS_NAME)
            .remove(KEY_IN_PROGRESS_TIME)
        if (inFlight.isEmpty()) {
            editor.remove(KEY_IN_FLIGHT)
        } else {
            editor.putString(KEY_IN_FLIGHT, JSONObject(inFlight).toString())
        }
        editor.commit()
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
            .remove(KEY_IN_FLIGHT)
            .remove(KEY_IN_PROGRESS_URI)
            .remove(KEY_IN_PROGRESS_NAME)
            .remove(KEY_IN_PROGRESS_TIME)
            .remove(KEY_SAFE_MODE)
            .remove(KEY_CRASH_AVERTED_MSG)
            .apply()
        cachedMap = emptyMap()
        _quarantinedCount.value = 0
        _safeModeActive.value = false
        _crashAvertedNotice.value = null
    }

    private fun refreshQuarantinedCount(context: Context) {
        val list = getQuarantinedList(context)
        _quarantinedCount.value = list.size
    }

    private fun loadFailedMap(context: Context): MutableMap<String, QuarantinedDocument> {
        cachedMap?.let { return it.toMutableMap() }
        val map = readFailedMap(context)
        cachedMap = map
        return map.toMutableMap()
    }

    private fun readFailedMap(context: Context): MutableMap<String, QuarantinedDocument> {
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
            cachedMap = HashMap(map)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving failed documents map: ${e.message}")
        }
    }
}

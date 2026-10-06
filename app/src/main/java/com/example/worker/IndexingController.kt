package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.example.service.ChatBackupIndexingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single place that decides whether any background indexing is allowed to run, and that can
 * start/stop all of it at once.
 *
 * "Stop" is remembered across app restarts (a user preference), so periodic scans, the
 * first-launch crawl and the permission-granted crawl all stay off until the user explicitly
 * starts indexing again. Workers check [isStoppedByUser] when they begin.
 */
object IndexingController {

    private const val TAG = "IndexingController"
    private const val PREFS_NAME = "indexing_control_prefs"
    private const val KEY_STOPPED_BY_USER = "indexing_stopped_by_user"
    private const val KEY_FULL_CRAWL_DONE = "full_storage_crawl_done"

    const val UNIQUE_DOCUMENT_INDEX = "document_indexing_work"
    const val UNIQUE_SAMPLE_INDEX = "document_indexing_sample_work"

    private val ALL_WORK_TAGS = listOf(
        DocumentIndexWorker.TAG,
        FolderMonitorWorker.TAG,
        PdfSyncWorker.TAG,
        DownloadsFileObserverWorker.TAG,
        ContinuousSyncWorker.TAG
    )

    private val _stoppedFlow = MutableStateFlow(false)

    @Volatile
    private var stoppedFlowInitialized = false

    fun isStoppedByUser(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STOPPED_BY_USER, false)

    /** Live "stopped by user" state, so the UI also reacts when indexing is stopped from the notification. */
    fun stoppedByUserFlow(context: Context): StateFlow<Boolean> {
        if (!stoppedFlowInitialized) {
            _stoppedFlow.value = isStoppedByUser(context)
            stoppedFlowInitialized = true
        }
        return _stoppedFlow.asStateFlow()
    }

    private fun setStoppedByUser(context: Context, stopped: Boolean) {
        prefs(context).edit().putBoolean(KEY_STOPPED_BY_USER, stopped).apply()
        _stoppedFlow.value = stopped
    }

    /** True once a whole-storage crawl has completed, so it is not repeated on every app launch. */
    fun isFullStorageCrawlDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FULL_CRAWL_DONE, false)

    fun setFullStorageCrawlDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_FULL_CRAWL_DONE, done).apply()
    }

    /**
     * Stops all indexing now: cancels every running and scheduled indexing worker, the chat-backup
     * service and the progress notification, and keeps background indexing off until [resume] is called.
     */
    fun stopAll(context: Context) {
        val app = context.applicationContext
        setStoppedByUser(app, true)
        try {
            val workManager = WorkManager.getInstance(app)
            ALL_WORK_TAGS.forEach { workManager.cancelAllWorkByTag(it) }
            workManager.cancelUniqueWork(UNIQUE_DOCUMENT_INDEX)
            workManager.cancelUniqueWork(UNIQUE_SAMPLE_INDEX)
        } catch (e: Throwable) {
            Log.w(TAG, "Cancelling indexing work failed: ${e.message}")
        }
        ChatBackupIndexingService.cancelIfRunning(app)
        IndexingNotifier.cancelAll(app)
    }

    /** Allows indexing again and restores the periodic background scan (if the user has auto-scan enabled). */
    fun resume(context: Context) {
        val app = context.applicationContext
        setStoppedByUser(app, false)
        FolderMonitorWorker.schedulePeriodicMonitor(app)
        ContinuousSyncWorker.scheduleContinuousSync(app)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

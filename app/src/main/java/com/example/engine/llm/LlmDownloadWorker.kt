package com.example.engine.llm

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.DocuVectorApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Downloads a chat model in the background (WorkManager), so a ~0.5–2 GB download survives the app being closed
 * and resumes where it stopped after a lost connection. Shows a progress notification with a Cancel button.
 *
 * Right after the app is installed, [ensureDefaultModel] queues the [ChatModel.DEFAULT] model so chat works
 * without any setup; it waits for Wi-Fi unless the user allowed mobile data.
 */
class LlmDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LlmDownloadWorker"
        const val KEY_MODEL = "model"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val NOTIFICATION_ID = 1101
        private const val MAX_ATTEMPTS = 8

        fun workName(model: ChatModel) = "llm_download_${model.id}"

        /**
         * Queues a download of [model]. A download the user asked for replaces a waiting one (e.g. to start now
         * on mobile data); the automatic one keeps an existing request.
         */
        fun enqueue(context: Context, model: ChatModel, userRequested: Boolean) {
            val store = LlmModelStore(context)
            if (store.isInstalled(model)) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (store.allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .setRequiresStorageNotLow(true)
                .build()
            val request = OneTimeWorkRequestBuilder<LlmDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL to model.id))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("llm_download")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(model),
                if (userRequested) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, model: ChatModel) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(model))
        }

        /**
         * Called on every app start (off the main thread): queues the default model until a chat model is
         * installed, unless the user cancelled that download or chose to download models only by hand.
         */
        fun ensureDefaultModel(context: Context) {
            val store = LlmModelStore(context)
            if (store.autoDownloadDisabled) return
            if (store.installedModels().isNotEmpty()) return
            val previous = try {
                WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName(ChatModel.DEFAULT)).get().lastOrNull()
            } catch (_: Exception) {
                null
            }
            if (previous?.state == WorkInfo.State.CANCELLED) {
                // Cancelled from the notification: respect it from now on.
                store.autoDownloadDisabled = true
                return
            }
            enqueue(context, ChatModel.DEFAULT, userRequested = false)
        }

        fun progressFlow(context: Context, model: ChatModel): Flow<DownloadState> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(model)).map { infos ->
                val info = infos.lastOrNull() ?: return@map DownloadState.Idle
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        val done = info.progress.getLong(KEY_DONE, 0L)
                        DownloadState.Waiting(info.runAttemptCount > 0, done)
                    }
                    WorkInfo.State.RUNNING -> DownloadState.Running(
                        info.progress.getLong(KEY_DONE, 0L),
                        info.progress.getLong(KEY_TOTAL, model.approxSizeBytes).takeIf { it > 0 } ?: model.approxSizeBytes
                    )
                    WorkInfo.State.SUCCEEDED -> DownloadState.Done
                    WorkInfo.State.FAILED -> DownloadState.Failed(info.outputData.getString(KEY_ERROR) ?: "Download failed")
                    WorkInfo.State.CANCELLED -> DownloadState.Idle
                }
            }
    }

    sealed interface DownloadState {
        data object Idle : DownloadState
        /** Queued: waiting for Wi-Fi / storage, or for the next retry after a lost connection. */
        data class Waiting(val retrying: Boolean, val doneBytes: Long) : DownloadState
        data class Running(val doneBytes: Long, val totalBytes: Long) : DownloadState {
            val fraction: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
        }
        data object Done : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val model: ChatModel? get() = ChatModel.fromId(inputData.getString(KEY_MODEL))

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(notification(model ?: ChatModel.DEFAULT, null))

    override suspend fun doWork(): Result {
        val model = model ?: return Result.failure(workDataOf(KEY_ERROR to "Unknown model"))
        val store = LlmModelStore(applicationContext)
        if (store.isInstalled(model)) return Result.success()
        try {
            setForeground(foregroundInfo(notification(model, null)))
        } catch (e: Exception) {
            // Not allowed to start a foreground service right now (Android 12+ background limits): keep going.
            Log.i(TAG, "Downloading without a foreground notification: ${e.message}")
        }
        var lastPercent = -1
        return try {
            withContext(Dispatchers.IO) { store.download(model) { done, total ->
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                val percent = if (total > 0) (done * 100 / total).toInt() else 0
                if (percent != lastPercent) {
                    lastPercent = percent
                    try {
                        setForeground(foregroundInfo(notification(model, percent)))
                    } catch (_: Exception) {
                    }
                }
            } }
            if (store.installedModels().size == 1) store.selectedModel = model
            com.example.dev.DevLog.i("Chat", "Downloaded chat model ${model.id}")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmModelStore.DownloadException) {
            Log.w(TAG, "Download of ${model.id} failed (attempt $runAttemptCount): ${e.message}")
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to e.message))
        } catch (e: Exception) {
            Log.w(TAG, "Download of ${model.id} failed: ${e.message}")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
        }
    }

    private fun notification(model: ChatModel, percent: Int?): Notification {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val builder = NotificationCompat.Builder(applicationContext, DocuVectorApp.CHANNEL_ID)
            .setContentTitle("Downloading chat model")
            .setContentText(if (percent != null) "${model.shortName} · $percent%" else "${model.shortName} · ${model.sizeLabel}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
        if (percent != null) builder.setProgress(100, percent.coerceIn(0, 100), false) else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
}

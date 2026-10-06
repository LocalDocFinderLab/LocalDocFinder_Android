package com.example.worker

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.repository.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkManager task that uses [FileObserver] to monitor the device Downloads folder
 * and automatically triggers embedding workflows when new files are detected.
 */
class DownloadsFileObserverWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "DownloadsFileObserverWorker"
        const val WORK_NAME = "downloads_folder_file_observer_work"
        const val IMMEDIATE_WORK_NAME = "downloads_folder_file_observer_immediate"

        const val KEY_NEW_FILES_INDEXED = "key_new_files_indexed"
        const val KEY_NEW_CHUNKS_INDEXED = "key_new_chunks_indexed"
        const val KEY_OBSERVER_MESSAGE = "key_observer_message"

        /**
         * Schedules periodic WorkManager task to ensure FileObserver and Downloads folder monitoring remain active.
         */
        fun scheduleDownloadsObserver(context: Context, intervalMinutes: Long = 15) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()

                val request = PeriodicWorkRequestBuilder<DownloadsFileObserverWorker>(
                    intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .addTag(TAG)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
                Log.i(TAG, "Downloads FileObserver WorkManager task scheduled every $intervalMinutes mins")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to schedule Downloads FileObserver worker: ${e.message}")
            }
        }

        /**
         * Triggers an immediate execution of the Downloads FileObserver worker.
         */
        fun triggerImmediateScan(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<DownloadsFileObserverWorker>()
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            return request.id
        }
    }

    private var activeObserver: FileObserver? = null

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (com.example.worker.IndexingController.isStoppedByUser(context)) {
            Log.i(TAG, "Indexing is stopped by the user. Skipping.")
            return@withContext Result.success(workDataOf(KEY_OBSERVER_MESSAGE to "Skipped: indexing is stopped"))
        }

        val repository = DocumentRepository(context)

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.getExternalFilesDir(null), "Downloads")

        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        Log.i(TAG, "DownloadsFileObserverWorker active. Monitoring path: ${downloadsDir.absolutePath}")

        var newlyDetectedFiles = 0
        var newlyDetectedChunks = 0

        val newlyDetectedFileList = mutableListOf<File>()

        // Initialize FileObserver on the Downloads folder
        val observerMask = FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO

        val fileObserver = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            object : FileObserver(downloadsDir, observerMask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path.isNullOrBlank()) return
                    val file = File(downloadsDir, path)
                    if (file.exists() && !file.isDirectory && !file.name.startsWith(".")) {
                        Log.i(TAG, "FileObserver detected new file event ($event): ${file.name}")
                        synchronized(newlyDetectedFileList) {
                            if (!newlyDetectedFileList.contains(file)) {
                                newlyDetectedFileList.add(file)
                            }
                        }
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(downloadsDir.absolutePath, observerMask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path.isNullOrBlank()) return
                    val file = File(downloadsDir, path)
                    if (file.exists() && !file.isDirectory && !file.name.startsWith(".")) {
                        Log.i(TAG, "FileObserver detected new file event ($event): ${file.name}")
                        synchronized(newlyDetectedFileList) {
                            if (!newlyDetectedFileList.contains(file)) {
                                newlyDetectedFileList.add(file)
                            }
                        }
                    }
                }
            }
        }

        activeObserver = fileObserver
        fileObserver.startWatching()

        try {
            // Also perform a sweep scan of existing un-indexed files in Downloads
            val parser = com.example.engine.DocumentParser(context)
            val candidateFilesMap = parser.scanDownloadDirectory().associateBy { it.uri.toString() }

            val dao = com.example.data.local.AppDatabase.getInstance(context).documentChunkDao()
            val indexedUris = dao.getIndexedFilesDirect().toSet()

            val unindexedCandidates = candidateFilesMap.filterKeys { !indexedUris.contains(it) }.values.toList()
            val totalCandidates = unindexedCandidates.size

            for ((idx, docFile) in unindexedCandidates.withIndex()) {
                val fileName = docFile.name ?: "Downloaded Document"
                val pct = if (totalCandidates > 0) (((idx + 1).toFloat() / totalCandidates.toFloat()) * 100).toInt() else 0
                setProgress(
                    workDataOf(
                        KEY_NEW_FILES_INDEXED to newlyDetectedFiles,
                        KEY_NEW_CHUNKS_INDEXED to newlyDetectedChunks,
                        "current_file" to fileName,
                        "phase" to "Downloads FileObserver: Processing downloaded file",
                        "processed_count" to (idx + 1),
                        "total_count" to totalCandidates,
                        "percent" to pct,
                        "is_running" to true
                    )
                )

                IndexingNotifier.show(
                    context,
                    IndexingNotifier.ID_DOWNLOADS_SCAN,
                    "Indexing downloads",
                    "$fileName (${idx + 1}/$totalCandidates)",
                    (idx * 100) / totalCandidates
                )
                val res = repository.indexDocumentSafely(docFile)
                if (res.isSuccess) {
                    newlyDetectedFiles++
                    newlyDetectedChunks += res.chunksIndexed
                    Log.i(TAG, "Auto-embedded downloaded file: ${docFile.name}")
                }
            }

            // Monitor active FileObserver events for a short period
            delay(2000)

            val pendingFilesToProcess: List<File>
            synchronized(newlyDetectedFileList) {
                pendingFilesToProcess = ArrayList(newlyDetectedFileList)
                newlyDetectedFileList.clear()
            }

            val totalPending = pendingFilesToProcess.size
            for ((idx, file) in pendingFilesToProcess.withIndex()) {
                val docFile = DocumentFile.fromFile(file)
                if (docFile.exists() && !indexedUris.contains(docFile.uri.toString())) {
                    val pct = if (totalPending > 0) (((idx + 1).toFloat() / totalPending.toFloat()) * 100).toInt() else 0
                    setProgress(
                        workDataOf(
                            KEY_NEW_FILES_INDEXED to newlyDetectedFiles,
                            KEY_NEW_CHUNKS_INDEXED to newlyDetectedChunks,
                            "current_file" to file.name,
                            "phase" to "FileObserver Event: Indexing ${file.name}",
                            "processed_count" to (idx + 1),
                            "total_count" to totalPending,
                            "percent" to pct,
                            "is_running" to true
                        )
                    )

                    IndexingNotifier.show(
                        context,
                        IndexingNotifier.ID_DOWNLOADS_SCAN,
                        "Indexing downloads",
                        "${file.name} (${idx + 1}/$totalPending)",
                        (idx * 100) / totalPending
                    )
                    val res = repository.indexDocumentSafely(docFile)
                    if (res.isSuccess) {
                        newlyDetectedFiles++
                        newlyDetectedChunks += res.chunksIndexed
                        Log.i(TAG, "Auto-embedded file via FileObserver event: ${file.name}")
                    }
                }
            }

            // Update app widgets
            try {
                com.example.widget.DocuVectorWidget.updateAllWidgets(context)
            } catch (_: Exception) {}

            val msg = if (newlyDetectedFiles > 0) {
                "Downloads FileObserver auto-embedded $newlyDetectedFiles new file(s) ($newlyDetectedChunks chunks)"
            } else {
                "Downloads FileObserver active. All downloaded files up-to-date."
            }

            Log.i(TAG, msg)

            Result.success(
                workDataOf(
                    KEY_NEW_FILES_INDEXED to newlyDetectedFiles,
                    KEY_NEW_CHUNKS_INDEXED to newlyDetectedChunks,
                    KEY_OBSERVER_MESSAGE to msg
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in DownloadsFileObserverWorker: ${e.message}", e)
            Result.retry()
        } finally {
            try {
                activeObserver?.stopWatching()
            } catch (_: Exception) {}
            IndexingNotifier.cancel(context, IndexingNotifier.ID_DOWNLOADS_SCAN)
        }
    }
}

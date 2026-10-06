package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.DocuVectorApp
import com.example.data.local.AppDatabase
import com.example.data.repository.DocumentRepository
import com.example.engine.DocumentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkManager background task that automatically monitors a designated device folder
 * and triggers document indexing whenever a new file is detected, providing a seamless
 * offline search experience.
 */
class FolderMonitorWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "FolderMonitorWorker"
        const val WORK_NAME = "designated_folder_monitor_work"
        const val IMMEDIATE_WORK_NAME = "designated_folder_monitor_immediate"

        const val PREFS_NAME = "folder_monitor_prefs"
        const val PREF_DESIGNATED_FOLDER_PATH = "designated_folder_path"
        const val PREF_DESIGNATED_FOLDER_URI = "designated_folder_uri"
        const val PREF_IS_MONITORING_ENABLED = "is_monitoring_enabled"
        const val PREF_LAST_SCAN_TIME = "last_scan_time"
        const val PREF_LAST_NEW_FILES_COUNT = "last_new_files_count"
        const val PREF_LAST_NEW_CHUNKS_COUNT = "last_new_chunks_count"
        const val PREF_LAST_SCAN_STATUS = "last_scan_status"

        const val KEY_MANUAL_RUN = "key_manual_run"
        const val KEY_FOLDER_URI = "key_folder_uri"
        const val KEY_FOLDER_PATH = "key_folder_path"
        const val KEY_NEW_FILES_INDEXED = "key_new_files_indexed"
        const val KEY_NEW_CHUNKS_INDEXED = "key_new_chunks_indexed"
        const val KEY_SCAN_MESSAGE = "key_scan_message"

        const val NOTIFICATION_ID = 1002

        /**
         * Resolves the designated folder for background monitoring.
         * Defaults to [context.getExternalFilesDir(null)]/monitored_documents.
         */
        fun getDesignatedFolder(context: Context): File {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val customPath = prefs.getString(PREF_DESIGNATED_FOLDER_PATH, null)
            if (!customPath.isNullOrBlank()) {
                val file = File(customPath)
                if (file.exists() && file.isDirectory) return file
            }
            val defaultDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "monitored_documents")
            if (!defaultDir.exists()) {
                defaultDir.mkdirs()
            }
            return defaultDir
        }

        fun setDesignatedFolder(context: Context, file: File) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(PREF_DESIGNATED_FOLDER_PATH, file.absolutePath)
                .apply()
        }

        fun setDesignatedFolderUri(context: Context, uri: Uri) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(PREF_DESIGNATED_FOLDER_URI, uri.toString())
                .apply()
        }

        fun getDesignatedFolderUri(context: Context): Uri? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val uriStr = prefs.getString(PREF_DESIGNATED_FOLDER_URI, null)
            return uriStr?.let { Uri.parse(it) }
        }

        fun isMonitoringEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_IS_MONITORING_ENABLED, true)
        }

        fun setMonitoringEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_IS_MONITORING_ENABLED, enabled).apply()
            if (enabled) {
                schedulePeriodicMonitor(context)
            } else {
                cancelPeriodicMonitor(context)
            }
        }

        fun getLastScanInfo(context: Context): Triple<Long, Int, String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val time = prefs.getLong(PREF_LAST_SCAN_TIME, 0L)
            val count = prefs.getInt(PREF_LAST_NEW_FILES_COUNT, 0)
            val status = prefs.getString(PREF_LAST_SCAN_STATUS, "Ready to monitor") ?: "Ready to monitor"
            return Triple(time, count, status)
        }

        /**
         * Schedules periodic folder monitoring via WorkManager.
         */
        fun schedulePeriodicMonitor(context: Context, intervalMinutes: Long = 15) {
            if (!isMonitoringEnabled(context)) return
            try {
                val constraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()

                val monitorRequest = PeriodicWorkRequestBuilder<FolderMonitorWorker>(
                    intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .addTag(TAG)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    monitorRequest
                )
                Log.i(TAG, "Designated folder monitoring scheduled every $intervalMinutes mins")
            } catch (e: Throwable) {
                Log.w(TAG, "Folder monitoring scheduling deferred: ${e.message}")
            }
        }

        /**
         * Triggers an immediate one-time background scan of the designated folder.
         */
        fun triggerImmediateScan(context: Context, folderPath: String? = null, folderUri: Uri? = null): UUID {
            val inputData = workDataOf(
                KEY_MANUAL_RUN to true,
                KEY_FOLDER_PATH to folderPath,
                KEY_FOLDER_URI to folderUri?.toString()
            )
            val request = OneTimeWorkRequestBuilder<FolderMonitorWorker>()
                .setInputData(inputData)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            return request.id
        }

        fun cancelPeriodicMonitor(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: Throwable) {
                Log.w(TAG, "Cancel folder monitoring skipped: ${e.message}")
            }
        }

        /**
         * Utility to create a test document in the designated monitored folder,
         * allowing effortless verification of automatic detection and indexing.
         */
        fun createTestDocumentInMonitoredFolder(
            context: Context,
            title: String = "Test_Offline_Doc",
            content: String = "Offline vector search test file generated for automated folder monitoring."
        ): File {
            val folder = getDesignatedFolder(context)
            val safeTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val timestamp = System.currentTimeMillis()
            val file = File(folder, "${safeTitle}_$timestamp.txt")
            FileOutputStream(file).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            return file
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (com.example.worker.IndexingController.isStoppedByUser(context)) {
            Log.i(TAG, "Indexing is stopped by the user. Skipping.")
            return@withContext Result.success(workDataOf(KEY_SCAN_MESSAGE to "Skipped: indexing is stopped"))
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isPowerSaveMode == true) {
            Log.i(TAG, "Device is in Power Saving Mode. Skipping folder monitoring scan.")
            return@withContext Result.success(workDataOf(KEY_SCAN_MESSAGE to "Skipped: Power save mode active"))
        }

        if (com.example.engine.HardwareMonitor.isIndexingPaused.value) {
            Log.i(TAG, "Gaming Mode is active. Indexing suspended to protect game performance.")
            return@withContext Result.success(workDataOf(KEY_SCAN_MESSAGE to "Skipped: Gaming mode active"))
        }

        try {
            val repository = DocumentRepository(context)
            val parser = DocumentParser(context)
            val dao = AppDatabase.getInstance(context).documentChunkDao()

            // 1. Discover files across all monitored sources (Designated folder, Downloads, WhatsApp, messaging & system storage)
            val explicitPath = inputData.getString(KEY_FOLDER_PATH)
            val explicitUriStr = inputData.getString(KEY_FOLDER_URI)

            val candidateFilesMap = HashMap<String, DocumentFile>()
            val folderDisplayName: String

            if (!explicitUriStr.isNullOrBlank()) {
                val uri = Uri.parse(explicitUriStr)
                parser.scanDirectory(uri).forEach { candidateFilesMap[it.uri.toString()] = it }
                val treeDoc = DocumentFile.fromTreeUri(context, uri)
                folderDisplayName = treeDoc?.name ?: uri.lastPathSegment ?: "Designated Folder"
            } else {
                val designatedUri = getDesignatedFolderUri(context)
                if (designatedUri != null) {
                    parser.scanDirectory(designatedUri).forEach { candidateFilesMap[it.uri.toString()] = it }
                } else {
                    val folder = if (!explicitPath.isNullOrBlank()) File(explicitPath) else getDesignatedFolder(context)
                    if (!folder.exists()) folder.mkdirs()
                    parser.scanDirectory(Uri.fromFile(folder)).forEach { candidateFilesMap[it.uri.toString()] = it }
                }
                folderDisplayName = "Downloads & Storage Folders"
            }

            // Always scan public Downloads, WhatsApp, messaging apps & system storage for auto-embedding
            try {
                parser.scanDownloadDirectory().forEach { candidateFilesMap[it.uri.toString()] = it }
            } catch (_: Exception) {}
            try {
                parser.scanAndroidDirectory().forEach { candidateFilesMap[it.uri.toString()] = it }
            } catch (_: Exception) {}

            val candidateFiles = candidateFilesMap.values.toList()

            // 2. Last-indexed timestamp per file for change detection (a tiny GROUP BY query: no chunk text or embeddings are loaded)
            val indexedTimestampMap = dao.getIndexedFileStamps().associate { it.fileUri to (it.lastIndexed) }

            // 3. Detect new files or files whose modification time is newer than saved timestamp (skipping quarantined docs)
            val filesToProcess = candidateFiles.filter { docFile ->
                val uriStr = docFile.uri.toString()
                if (com.example.engine.FailedDocumentRegistry.isQuarantined(context, uriStr)) {
                    false // Skip quarantined documents to avoid crash loops
                } else {
                    val lastIndexedTime = indexedTimestampMap[uriStr]
                    if (lastIndexedTime == null) {
                        true // Brand new file
                    } else {
                        val fileModTime = docFile.lastModified()
                        fileModTime > 0L && fileModTime > (lastIndexedTime + 1000L) // File content modified
                    }
                }
            }

            Log.i(TAG, "Monitored sources ($folderDisplayName): ${candidateFiles.size} total discovered files, ${filesToProcess.size} new or modified.")

            var newlyIndexedChunks = 0
            var newlyIndexedFiles = 0

            // 4. Trigger document indexing for each detected new or updated file
            if (filesToProcess.isNotEmpty()) {
                val totalToProcess = filesToProcess.size
                for ((idx, docFile) in filesToProcess.withIndex()) {
                    if (isStopped) {
                        Log.i(TAG, "Folder monitoring stopped by system. Will retry.")
                        return@withContext Result.retry()
                    }
                    val fileName = docFile.name ?: "Document"
                    val pct = (((idx + 1).toFloat() / totalToProcess.toFloat()) * 100).toInt()
                    setProgress(
                        workDataOf(
                            KEY_NEW_FILES_INDEXED to newlyIndexedFiles,
                            KEY_NEW_CHUNKS_INDEXED to newlyIndexedChunks,
                            "current_file" to fileName,
                            "phase" to "Monitored Folder: Indexing file ${idx + 1}/$totalToProcess",
                            "processed_count" to (idx + 1),
                            "total_count" to totalToProcess,
                            "percent" to pct,
                            "is_running" to true
                        )
                    )

                    // Only shown while there is real work: idle background checks never touch the status bar.
                    IndexingNotifier.show(
                        context,
                        IndexingNotifier.ID_FOLDER_SCAN,
                        "Indexing new documents",
                        "$fileName (${idx + 1}/$totalToProcess)",
                        (idx * 100) / totalToProcess
                    )

                    Log.i(TAG, "Auto-embedding detected new/updated file: $fileName")
                    val result = repository.indexDocumentSafely(docFile)
                    if (result.isSuccess) {
                        newlyIndexedChunks += result.chunksIndexed
                        newlyIndexedFiles++
                    } else {
                        Log.w(TAG, "Failed to auto-index $fileName: ${result.errorMessage}")
                    }
                }

                // Update widgets
                try {
                    com.example.widget.DocuVectorWidget.updateAllWidgets(context)
                } catch (_: Exception) {}
            }

            val statusMsg = if (newlyIndexedFiles > 0) {
                "Indexed $newlyIndexedFiles new file(s) ($newlyIndexedChunks chunks) in $folderDisplayName"
            } else {
                "Up to date. No new files found in $folderDisplayName."
            }

            // 5. Update preferences with scan metadata
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(PREF_LAST_SCAN_TIME, System.currentTimeMillis())
                .putInt(PREF_LAST_NEW_FILES_COUNT, newlyIndexedFiles)
                .putInt(PREF_LAST_NEW_CHUNKS_COUNT, newlyIndexedChunks)
                .putString(PREF_LAST_SCAN_STATUS, statusMsg)
                .apply()

            Log.i(TAG, "Folder monitoring check completed: $statusMsg")

            Result.success(
                workDataOf(
                    KEY_NEW_FILES_INDEXED to newlyIndexedFiles,
                    KEY_NEW_CHUNKS_INDEXED to newlyIndexedChunks,
                    KEY_SCAN_MESSAGE to statusMsg
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in folder monitor worker: ${e.message}", e)
            Result.retry()
        } finally {
            IndexingNotifier.cancel(context, IndexingNotifier.ID_FOLDER_SCAN)
        }
    }
}

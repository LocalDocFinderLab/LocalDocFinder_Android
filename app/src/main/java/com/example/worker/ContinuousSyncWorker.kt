package com.example.worker

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.repository.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ContinuousSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "ContinuousSyncWorker"
        const val WORK_NAME = "continuous_document_sync_work"

        fun scheduleContinuousSync(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true) // Pauses automatically in power-saving / low battery mode
                    .build()

                val syncRequest = PeriodicWorkRequestBuilder<ContinuousSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .addTag(TAG)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncRequest
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Continuous sync scheduling deferred or unavailable: ${e.message}")
            }
        }

        fun cancelContinuousSync(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: Throwable) {
                Log.w(TAG, "Continuous sync cancel skipped: ${e.message}")
            }
        }
    }

    private val repository = DocumentRepository(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (IndexingController.isStoppedByUser(context)) {
            Log.i(TAG, "Indexing is stopped by the user. Skipping continuous sync.")
            return@withContext Result.success(workDataOf("skipped_reason" to "stopped_by_user"))
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isPowerSaveMode == true) {
            Log.i(TAG, "Device is in Power Saving Mode. Skipping background sync to conserve battery.")
            return@withContext Result.success(workDataOf("skipped_reason" to "power_save_mode"))
        }

        try {
            val indexedFiles = repository.indexedFiles.first()
            var newlyIndexedChunks = 0

            // Check sample documents folder for changes
            val sampleFolder = java.io.File(context.filesDir, "sample_documents")
            if (sampleFolder.exists() && sampleFolder.isDirectory) {
                val files = sampleFolder.listFiles() ?: emptyArray()
                for (file in files) {
                    if (isStopped) return@withContext Result.retry()
                    val docFile = androidx.documentfile.provider.DocumentFile.fromFile(file)
                    val uriStr = docFile.uri.toString()
                    if (uriStr !in indexedFiles && !com.example.engine.FailedDocumentRegistry.isQuarantined(context, uriStr)) {
                        newlyIndexedChunks += repository.indexDocument(docFile)
                    }
                }
            }

            Log.i(TAG, "Continuous sync completed. Newly indexed chunks: $newlyIndexedChunks")
            Result.success(workDataOf("new_chunks" to newlyIndexedChunks))
        } catch (e: Exception) {
            Log.e(TAG, "Continuous sync encountered error: ${e.message}", e)
            Result.retry()
        }
    }
}

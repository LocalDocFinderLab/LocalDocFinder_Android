package com.example.worker

import android.content.Context
import android.net.Uri
import android.os.Environment
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
import com.example.data.local.AppDatabase
import com.example.data.repository.DocumentRepository
import com.example.engine.DocumentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A highly reliable WorkManager background sync task that monitors the device's
 * 'Downloads' directory using DocumentFile and triggers the offline vector
 * indexing process for un-indexed or updated PDF files.
 */
class PdfSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "PdfSyncWorker"
        const val WORK_NAME = "pdf_downloads_sync_work"
        const val IMMEDIATE_WORK_NAME = "pdf_downloads_sync_immediate"

        const val KEY_NEW_FILES_INDEXED = "key_new_files_indexed"
        const val KEY_NEW_CHUNKS_INDEXED = "key_new_chunks_indexed"
        const val KEY_SYNC_MESSAGE = "key_sync_message"

        /**
         * Schedules the periodic PDF downloads sync job in the background.
         */
        fun schedulePdfSync(context: Context, intervalMinutes: Long = 15) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()

                val request = PeriodicWorkRequestBuilder<PdfSyncWorker>(
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
                Log.i(TAG, "PdfSyncWorker scheduled periodically every $intervalMinutes minutes.")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to schedule PdfSyncWorker: ${e.message}", e)
            }
        }

        /**
         * Triggers an immediate, one-time execution of the PDF downloads sync.
         */
        fun triggerImmediateSync(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<PdfSyncWorker>()
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.i(TAG, "PdfSyncWorker immediate run triggered.")
            return request.id
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (com.example.worker.IndexingController.isStoppedByUser(context)) {
            Log.i(TAG, "Indexing is stopped by the user. Skipping.")
            return@withContext Result.success(workDataOf(KEY_SYNC_MESSAGE to "Skipped: indexing is stopped"))
        }

        Log.i(TAG, "PdfSyncWorker sync cycle started. Initializing Downloads folder scan.")
        val repository = DocumentRepository(context)
        val parser = DocumentParser(context)
        val dao = AppDatabase.getInstance(context).documentChunkDao()

        var filesIndexedCount = 0
        var chunksIndexedCount = 0

        try {
            // 1. Resolve standard public Downloads directory
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.getExternalFilesDir(null), "Downloads")

            if (!downloadsDir.exists()) {
                Log.i(TAG, "Downloads folder does not exist. Creating directory: ${downloadsDir.absolutePath}")
                downloadsDir.mkdirs()
            }

            Log.i(TAG, "Crawling Downloads directory: ${downloadsDir.absolutePath}")
            val downloadsDoc = DocumentFile.fromFile(downloadsDir)

            // 2. Discover all documents using PDF Crawler file discovery logic
            val discoveredFiles = parser.scanDownloadDirectory()
            Log.i(TAG, "PDF crawler discovered ${discoveredFiles.size} total supported files in Downloads.")

            // Filter for PDFs or other documents to index
            val pdfAndDocs = discoveredFiles.filter { doc ->
                val ext = doc.name?.substringAfterLast('.', "")?.lowercase() ?: ""
                ext == "pdf" || ext in DocumentParser.SUPPORTED_DOCUMENT_EXTENSIONS
            }
            Log.i(TAG, "Filtered ${pdfAndDocs.size} PDF & text document files for indexing consideration.")

            // 3. Fetch list of already indexed file URIs to avoid redundant embedding cycles
            val indexedUris = dao.getIndexedFilesDirect().toSet()
            Log.i(TAG, "Room Database currently contains ${indexedUris.size} unique indexed document URIs.")

            // Identify un-indexed files
            val unindexedFiles = pdfAndDocs.filter { doc ->
                !indexedUris.contains(doc.uri.toString())
            }
            Log.i(TAG, "Detected ${unindexedFiles.size} new unindexed documents to process.")

            if (unindexedFiles.isEmpty()) {
                val upToDateMsg = "All files in Downloads are already fully indexed. Sync finished."
                Log.i(TAG, upToDateMsg)
                return@withContext Result.success(
                    workDataOf(
                        KEY_NEW_FILES_INDEXED to 0,
                        KEY_NEW_CHUNKS_INDEXED to 0,
                        KEY_SYNC_MESSAGE to upToDateMsg
                    )
                )
            }

            // 4. Index each un-indexed document through the vector embedding engine
            val totalToProcess = unindexedFiles.size
            for ((idx, docFile) in unindexedFiles.withIndex()) {
                if (isStopped) {
                    Log.i(TAG, "PdfSyncWorker stopped by the operating system / WorkManager.")
                    return@withContext Result.retry()
                }

                val fileName = docFile.name ?: "Unnamed PDF"
                val percent = (((idx + 1).toFloat() / totalToProcess.toFloat()) * 100).toInt()

                Log.i(TAG, "Syncing and indexing file ${idx + 1}/$totalToProcess: $fileName")
                IndexingNotifier.show(
                    context,
                    IndexingNotifier.ID_PDF_SYNC,
                    "Indexing downloaded documents",
                    "$fileName (${idx + 1}/$totalToProcess)",
                    (idx * 100) / totalToProcess
                )

                setProgress(
                    workDataOf(
                        KEY_NEW_FILES_INDEXED to filesIndexedCount,
                        KEY_NEW_CHUNKS_INDEXED to chunksIndexedCount,
                        "current_file" to fileName,
                        "phase" to "Downloads Sync: Parsing and generating vector embeddings",
                        "processed_count" to (idx + 1),
                        "total_count" to totalToProcess,
                        "percent" to percent,
                        "is_running" to true
                    )
                )

                // Trigger indexing safely using document repository
                val result = repository.indexDocumentSafely(docFile)
                if (result.isSuccess) {
                    filesIndexedCount++
                    chunksIndexedCount += result.chunksIndexed
                    Log.i(TAG, "Successfully index-synced $fileName. Generated ${result.chunksIndexed} vector embeddings.")
                } else {
                    Log.e(TAG, "Failed to index-synced $fileName: ${result.errorMessage}")
                }
            }

            // Trigger app widget update
            try {
                com.example.widget.DocuVectorWidget.updateAllWidgets(context)
            } catch (e: Exception) {
                Log.w(TAG, "Widget update deferred: ${e.message}")
            }

            val successMsg = "PdfSyncWorker completed! Successfully processed $filesIndexedCount new file(s) generating $chunksIndexedCount vector embeddings."
            Log.i(TAG, successMsg)

            Result.success(
                workDataOf(
                    KEY_NEW_FILES_INDEXED to filesIndexedCount,
                    KEY_NEW_CHUNKS_INDEXED to chunksIndexedCount,
                    KEY_SYNC_MESSAGE to successMsg
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error inside PdfSyncWorker sync cycle: ${e.message}", e)
            Result.retry()
        } finally {
            IndexingNotifier.cancel(context, IndexingNotifier.ID_PDF_SYNC)
        }
    }
}

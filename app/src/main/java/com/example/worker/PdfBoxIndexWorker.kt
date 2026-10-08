package com.example.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.local.AppDatabase
import com.example.data.repository.DocumentRepository
import com.example.engine.extraction.PdfBoxTextExtractor
import com.example.widget.DocuVectorWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * WorkManager background task that uses the existing PDFBox library (via [PdfBoxTextExtractor]
 * and the indexing pipeline) to extract text from imported documents and populate the SQLite FTS index.
 */
class PdfBoxIndexWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "PdfBoxIndexWorker"
        const val WORK_NAME = "pdfbox_index_work"
        const val IMMEDIATE_WORK_NAME = "pdfbox_index_immediate"

        const val KEY_DOCUMENT_URIS = "key_document_uris"
        const val KEY_DOCUMENT_URI = "key_document_uri"
        const val KEY_INDEXED_CHUNKS = "key_indexed_chunks"
        const val KEY_INDEXED_FILES = "key_indexed_files"
        const val KEY_FAILED_FILES = "key_failed_files"
        const val KEY_ERROR_MESSAGE = "key_error_message"
        const val KEY_PROGRESS_PERCENT = "key_progress_percent"
        const val KEY_CURRENT_FILE = "key_current_file"
        const val KEY_CURRENT_PHASE = "key_current_phase"

        /**
         * Enqueues an immediate background task with WorkManager to process imported documents.
         * If [uris] is null or empty, processes all pending documents stored in the Room database.
         */
        fun enqueue(context: Context, uris: List<String>? = null): UUID {
            val dataBuilder = androidx.work.Data.Builder()
            if (!uris.isNullOrEmpty()) {
                dataBuilder.putStringArray(KEY_DOCUMENT_URIS, uris.toTypedArray())
            }

            val constraints = Constraints.Builder()
                .build()

            val request = OneTimeWorkRequestBuilder<PdfBoxIndexWorker>()
                .setConstraints(constraints)
                .setInputData(dataBuilder.build())
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME + "_" + System.currentTimeMillis(),
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.i(TAG, "PdfBoxIndexWorker enqueued for ${uris?.size ?: 0} imported documents.")
            return request.id
        }

        /**
         * Schedules the worker for a single imported document URI.
         */
        fun enqueueSingle(context: Context, uri: Uri): UUID {
            return enqueue(context, listOf(uri.toString()))
        }
    }

    private val repository = DocumentRepository(context)
    private val database = AppDatabase.getInstance(context)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return IndexingNotifier.foregroundInfo(
            IndexingNotifier.ID_DOCUMENT_INDEX,
            IndexingNotifier.build(
                context,
                "PDFBox Document Indexer",
                "Extracting text & updating SQLite FTS index…",
                null
            )
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val wakeLock = IndexingWakeLock.acquire(context, TAG)
        var totalChunks = 0
        var totalFiles = 0
        var failedFiles = 0
        val errorList = mutableListOf<String>()

        try {
            // Ensure PDFBox is initialized before worker starts parsing
            PdfBoxTextExtractor.ensureInitialised(context)

            // 1. Resolve document URIs to process
            val inputUriStrings = inputData.getStringArray(KEY_DOCUMENT_URIS)?.toList()
                ?: inputData.getString(KEY_DOCUMENT_URI)?.let { listOf(it) }
                ?: emptyList()

            val targetUris = if (inputUriStrings.isNotEmpty()) {
                inputUriStrings
            } else {
                // Read pending document paths stored in Room
                val pending = repository.getPendingDocumentPaths()
                if (pending.isNotEmpty()) {
                    pending.map { it.uri }
                } else {
                    // Fall back to all stored document paths
                    repository.getAllStoredDocumentPaths().map { it.uri }
                }
            }

            if (targetUris.isEmpty()) {
                Log.i(TAG, "No imported documents to index. Work complete.")
                return@withContext Result.success(
                    workDataOf(
                        KEY_INDEXED_CHUNKS to 0,
                        KEY_INDEXED_FILES to 0
                    )
                )
            }

            // Elevate to foreground notification if possible
            try {
                setForeground(getForegroundInfo())
            } catch (_: Throwable) {
                IndexingNotifier.show(
                    context,
                    IndexingNotifier.ID_DOCUMENT_INDEX,
                    "PDFBox Document Indexer",
                    "Processing ${targetUris.size} imported document(s)…",
                    0
                )
            }

            val totalCount = targetUris.size
            for ((index, uriStr) in targetUris.withIndex()) {
                if (isStopped) {
                    Log.i(TAG, "PdfBoxIndexWorker stopped by WorkManager / OS.")
                    return@withContext Result.retry()
                }

                val uri = Uri.parse(uriStr)
                val docFile = resolveDocumentFile(uri)
                val fileName = docFile?.name ?: uri.lastPathSegment ?: "Document"
                val percent = (((index + 1).toFloat() / totalCount.toFloat()) * 100).toInt()

                postProgress(
                    percent = percent,
                    currentFile = fileName,
                    phase = "Extracting with PDFBox & populating FTS index (${index + 1}/$totalCount)"
                )

                if (docFile == null || !docFile.exists()) {
                    Log.w(TAG, "File not found or unreadable: $uriStr")
                    repository.updateDocumentPathStatus(uriStr, "FAILED")
                    failedFiles++
                    errorList.add("$fileName: File not accessible")
                    continue
                }

                if (docFile.isDirectory) {
                    val childFiles = collectSupportedDocuments(docFile)
                    if (childFiles.isEmpty()) {
                        repository.updateDocumentPathStatus(uriStr, "INDEXED")
                        continue
                    }
                    var dirSuccess = true
                    for (child in childFiles) {
                        if (isStopped) return@withContext Result.retry()
                        val childRes = repository.indexDocumentSafely(child) { step, _, _ ->
                            postProgress(
                                percent = percent,
                                currentFile = child.name ?: fileName,
                                phase = step
                            )
                        }
                        if (childRes.isSuccess) {
                            totalChunks += childRes.chunksIndexed
                            totalFiles++
                        } else {
                            dirSuccess = false
                            errorList.add("${child.name}: ${childRes.errorMessage}")
                        }
                    }
                    repository.updateDocumentPathStatus(uriStr, if (dirSuccess) "INDEXED" else "FAILED")
                    continue
                }

                try {
                    // Index document safely using PDFBox extraction and write to Room FTS
                    val indexResult = repository.indexDocumentSafely(docFile) { step, cur, tot ->
                        val subPct = if (tot > 0) cur.toFloat() / tot.toFloat() else 0f
                        val currentOverall = (((index.toFloat() / totalCount.toFloat()) * 100) + (subPct / totalCount.toFloat() * 100)).toInt().coerceIn(0, 99)
                        postProgress(
                            percent = currentOverall,
                            currentFile = fileName,
                            phase = step
                        )
                    }

                    if (indexResult.isSuccess) {
                        totalChunks += indexResult.chunksIndexed
                        totalFiles++
                        repository.updateDocumentPathStatus(uriStr, "INDEXED")
                        Log.i(TAG, "Indexed $fileName: ${indexResult.chunksIndexed} chunks added to SQLite FTS.")
                    } else {
                        failedFiles++
                        repository.updateDocumentPathStatus(uriStr, "FAILED")
                        errorList.add("$fileName: ${indexResult.errorMessage ?: "Extraction error"}")
                        Log.w(TAG, "Failed indexing $fileName: ${indexResult.errorMessage}")
                    }
                } catch (t: Throwable) {
                    failedFiles++
                    repository.updateDocumentPathStatus(uriStr, "FAILED")
                    errorList.add("$fileName: ${t.localizedMessage ?: t.javaClass.simpleName}")
                    Log.e(TAG, "Error processing document $fileName: ${t.message}", t)
                }
            }

            // Optimize FTS index after bulk insertion for faster MATCH queries
            try {
                database.optimizeFtsIndex()
            } catch (e: Exception) {
                Log.w(TAG, "FTS optimize note: ${e.message}")
            }

            // Refresh app widget
            try {
                DocuVectorWidget.updateAllWidgets(context)
            } catch (_: Throwable) {}

            val finalMessage = "PDFBox indexing finished. Successfully indexed $totalFiles document(s) ($totalChunks chunks in SQLite FTS)."
            Log.i(TAG, finalMessage)

            Result.success(
                workDataOf(
                    KEY_INDEXED_CHUNKS to totalChunks,
                    KEY_INDEXED_FILES to totalFiles,
                    KEY_FAILED_FILES to failedFiles,
                    KEY_ERROR_MESSAGE to (if (errorList.isNotEmpty()) errorList.joinToString("\n") else "")
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fatal failure in PdfBoxIndexWorker: ${e.message}", e)
            Result.failure(
                workDataOf(
                    KEY_ERROR_MESSAGE to (e.localizedMessage ?: "Unknown indexing error")
                )
            )
        } finally {
            IndexingWakeLock.release(wakeLock, TAG)
            IndexingNotifier.cancel(context, IndexingNotifier.ID_DOCUMENT_INDEX)
        }
    }

    private fun resolveDocumentFile(uri: Uri): DocumentFile? {
        return try {
            if (uri.scheme == "file" || uri.scheme.isNullOrEmpty()) {
                val path = uri.path ?: uri.toString().removePrefix("file://")
                val f = File(path)
                if (f.exists()) DocumentFile.fromFile(f) else null
            } else if (uri.toString().contains("/tree/")) {
                DocumentFile.fromTreeUri(context, uri)
            } else {
                DocumentFile.fromSingleUri(context, uri)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving DocumentFile from $uri: ${e.message}")
            null
        }
    }

    private fun collectSupportedDocuments(dir: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        val queue = java.util.ArrayDeque<DocumentFile>()
        queue.add(dir)
        while (queue.isNotEmpty() && result.size < 500) {
            val current = queue.poll() ?: continue
            val children = try { current.listFiles() } catch (_: Exception) { emptyArray() }
            for (child in children) {
                if (child.isDirectory) {
                    val name = child.name?.lowercase() ?: ""
                    if (!com.example.engine.DocumentParser.IGNORED_DIRECTORY_NAMES.contains(name)) {
                        queue.add(child)
                    }
                } else if (child.isFile) {
                    val ext = child.name?.substringAfterLast('.', "")?.lowercase() ?: ""
                    if (ext in com.example.engine.DocumentParser.SUPPORTED_EXTENSIONS) {
                        result.add(child)
                    }
                }
            }
        }
        return result
    }

    private fun postProgress(percent: Int, currentFile: String, phase: String) {
        try {
            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS_PERCENT to percent,
                    KEY_CURRENT_FILE to currentFile,
                    KEY_CURRENT_PHASE to phase
                )
            )
            IndexingNotifier.show(
                context,
                IndexingNotifier.ID_DOCUMENT_INDEX,
                "PDFBox Document Indexer",
                "$currentFile • $phase",
                percent
            )
        } catch (_: Throwable) {}
    }
}

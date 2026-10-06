package com.example.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.repository.DocumentRepository
import com.example.engine.IndexingPowerPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class DocumentIndexWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_TREE_URI = "key_tree_uri"
        const val KEY_INDEX_SAMPLE = "key_index_sample"
        const val KEY_INDEX_100_SAMPLES = "key_index_100_samples"
        const val KEY_INDEX_DOWNLOADS = "key_index_downloads"
        const val KEY_INDEX_ANDROID = "key_index_android"
        const val KEY_INDEX_ENTIRE_SYSTEM = "key_index_entire_system"
        const val KEY_FILE_URIS = "key_file_uris"
        const val KEY_PROGRESS_CURRENT = "key_progress_current"
        const val KEY_PROGRESS_TOTAL = "key_progress_total"
        const val KEY_PROGRESS_PERCENT = "key_progress_percent"
        const val KEY_CURRENT_FILE = "key_current_file"
        const val KEY_CURRENT_PHASE = "key_current_phase"
        const val KEY_INDEXED_CHUNKS = "key_indexed_chunks"
        const val KEY_FAILED_COUNT = "key_failed_count"
        const val KEY_ERROR_SUMMARY = "key_error_summary"
        const val TAG = "DocumentIndexWorker"
    }

    private val repository = DocumentRepository(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val treeUriStr = inputData.getString(KEY_TREE_URI)
        val shouldIndexSample = inputData.getBoolean(KEY_INDEX_SAMPLE, false)
        val shouldIndex100Samples = inputData.getBoolean(KEY_INDEX_100_SAMPLES, false)
        val shouldIndexDownloads = inputData.getBoolean(KEY_INDEX_DOWNLOADS, false)
        val shouldIndexAndroid = inputData.getBoolean(KEY_INDEX_ANDROID, false)
        val shouldIndexEntireSystem = inputData.getBoolean(KEY_INDEX_ENTIRE_SYSTEM, false)
        val fileUriStrings = inputData.getStringArray(KEY_FILE_URIS)

        try {
            startForegroundSafely("Starting offline indexer…", "Initializing LiteRT pipeline")

            var totalIndexedChunks = 0
            val failures = mutableListOf<String>()

            if (shouldIndexEntireSystem) {
                // Entire System scan: Scans device storage, ignoring system files, packages, databases, and videos
                postProgress(5, "Scanning system storage…", "Ignoring system files, packages, databases, and videos")
                val files = repository.documentParser.scanEntireSystemStorage()

                if (files.isEmpty()) {
                    IndexingController.setFullStorageCrawlDone(context, true)
                    return@withContext Result.success(
                        workDataOf(
                            KEY_INDEXED_CHUNKS to 0,
                            KEY_FAILED_COUNT to 0,
                            KEY_ERROR_SUMMARY to "No regular documents or images found across device storage."
                        )
                    )
                }

                val outcome = indexFilesAdaptive(files) { ordinal, total, percent -> "Entire System: $ordinal/$total ($percent%)" }
                if (outcome.stopped) return@withContext Result.failure()
                totalIndexedChunks += outcome.chunks
                failures.addAll(outcome.failures)
            } else if (shouldIndex100Samples) {
                // If seeded files in getExternalFilesDir exist, prioritize indexing those
                val externalFilesDir = context.getExternalFilesDir(null)
                val externalFiles = externalFilesDir?.listFiles()?.filter {
                    it.isFile && it.name.substringAfterLast('.', "") in com.example.engine.DocumentParser.SUPPORTED_EXTENSIONS
                } ?: emptyList()

                val (chunks, errors) = if (externalFiles.isNotEmpty()) {
                    var c = 0
                    val errs = mutableListOf<String>()
                    externalFiles.forEachIndexed { idx, file ->
                        val docFile = androidx.documentfile.provider.DocumentFile.fromFile(file)
                        val res = repository.indexDocumentSafely(docFile) { step, cur, tot ->
                            val subFactor = if (tot > 0) cur.toFloat() / tot.toFloat() else 0f
                            val pct = (((idx.toFloat() / externalFiles.size.toFloat()) * 100) + (subFactor / externalFiles.size.toFloat() * 100)).toInt().coerceIn(0, 99)
                            postProgress(pct, "Indexing Seeded Files (${idx + 1}/${externalFiles.size})", "${file.name} • $step")
                            setProgressAsync(
                                workDataOf(
                                    KEY_PROGRESS_CURRENT to idx + 1,
                                    KEY_PROGRESS_TOTAL to externalFiles.size,
                                    KEY_PROGRESS_PERCENT to pct,
                                    KEY_CURRENT_FILE to file.name,
                                    KEY_CURRENT_PHASE to step
                                )
                            )
                        }
                        if (res.isSuccess) c += res.chunksIndexed else errs.add("${file.name}: ${res.errorMessage}")
                    }
                    Pair(c, errs)
                } else {
                    repository.createAndIndex100SampleFiles { current, total, currentFile, subPercent ->
                        val overallPercent = (((current - 1).toFloat() / total.toFloat()) * 100 + (subPercent.toFloat() / total.toFloat())).toInt().coerceIn(0, 100)
                        val title = "Indexing 100 Files ($overallPercent%)"
                        val content = "$currentFile ($current/$total)"

                        postProgress(overallPercent, title, content)
                        setProgressAsync(
                            workDataOf(
                                KEY_PROGRESS_CURRENT to current,
                                KEY_PROGRESS_TOTAL to total,
                                KEY_PROGRESS_PERCENT to overallPercent,
                                KEY_CURRENT_FILE to currentFile,
                                KEY_CURRENT_PHASE to "Indexing 100-file corpus"
                            )
                        )
                    }
                }
                totalIndexedChunks = chunks
                failures.addAll(errors)
            } else if (shouldIndexSample) {
                val (chunks, errors) = repository.createAndIndexSampleKnowledgeBase { current, total, currentFile, subPercent ->
                    val overallPercent = (((current - 1).toFloat() / total.toFloat()) * 100 + (subPercent.toFloat() / total.toFloat())).toInt().coerceIn(0, 100)
                    val title = "Indexing Sample KB ($overallPercent%)"
                    val content = "$currentFile ($current/$total)"

                    postProgress(overallPercent, title, content)
                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS_CURRENT to current,
                            KEY_PROGRESS_TOTAL to total,
                            KEY_PROGRESS_PERCENT to overallPercent,
                            KEY_CURRENT_FILE to currentFile,
                            KEY_CURRENT_PHASE to "Processing sample files"
                        )
                    )
                }
                totalIndexedChunks = chunks
                failures.addAll(errors)
            } else if (shouldIndexDownloads) {
                // Direct Scan of Download Folder: Bypasses Android 11+ SAF folder restriction
                postProgress(5, "Scanning Download folder…", "Searching regular docs and images")
                val files = repository.documentParser.scanDownloadDirectory()

                if (files.isEmpty()) {
                    return@withContext Result.success(
                        workDataOf(
                            KEY_INDEXED_CHUNKS to 0,
                            KEY_FAILED_COUNT to 0,
                            KEY_ERROR_SUMMARY to "No regular documents or images found in Download folder."
                        )
                    )
                }

                val outcome = indexFilesAdaptive(files) { ordinal, total, percent -> "Downloads: $ordinal/$total ($percent%)" }
                if (outcome.stopped) return@withContext Result.failure()
                totalIndexedChunks += outcome.chunks
                failures.addAll(outcome.failures)
            } else if (shouldIndexAndroid) {
                // Direct Scan of Android Folder: Bypasses Android 11+ SAF folder restriction for /Android
                postProgress(5, "Scanning Android folder…", "Searching documents, media & files")
                val files = repository.documentParser.scanAndroidDirectory()

                if (files.isEmpty()) {
                    return@withContext Result.success(
                        workDataOf(
                            KEY_INDEXED_CHUNKS to 0,
                            KEY_FAILED_COUNT to 0,
                            KEY_ERROR_SUMMARY to "No regular documents or images found in Android folder."
                        )
                    )
                }

                val outcome = indexFilesAdaptive(files) { ordinal, total, percent -> "Android Folder: $ordinal/$total ($percent%)" }
                if (outcome.stopped) return@withContext Result.failure()
                totalIndexedChunks += outcome.chunks
                failures.addAll(outcome.failures)
            } else if (fileUriStrings != null && fileUriStrings.isNotEmpty()) {
                // Multi-Document Pick mode: Indexes exact files picked by the user
                postProgress(5, "Processing selected files…", "${fileUriStrings.size} files queued")
                val files = fileUriStrings.mapNotNull {
                    androidx.documentfile.provider.DocumentFile.fromSingleUri(context, Uri.parse(it))
                }

                val outcome = indexFilesAdaptive(files) { ordinal, total, percent -> "Selected File $ordinal of $total ($percent%)" }
                if (outcome.stopped) return@withContext Result.failure()
                totalIndexedChunks += outcome.chunks
                failures.addAll(outcome.failures)
            } else if (!treeUriStr.isNullOrEmpty()) {
                val treeUri = Uri.parse(treeUriStr)
                postProgress(5, "Scanning directory tree…", "Discovering supported documents")

                val files = repository.documentParser.scanDirectory(treeUri)

                if (files.isEmpty()) {
                    return@withContext Result.success(
                        workDataOf(
                            KEY_INDEXED_CHUNKS to 0,
                            KEY_FAILED_COUNT to 0,
                            KEY_ERROR_SUMMARY to "No supported documents found in selected folder."
                        )
                    )
                }

                val outcome = indexFilesAdaptive(files) { ordinal, total, percent -> "Processing File $ordinal of $total ($percent%)" }
                if (outcome.stopped) return@withContext Result.failure()
                totalIndexedChunks += outcome.chunks
                failures.addAll(outcome.failures)
            }

            val errorSummary = if (failures.isNotEmpty()) failures.joinToString("\n") else null

            // Refresh home screen widget with latest indexed vectors count
            try {
                com.example.widget.DocuVectorWidget.updateAllWidgets(context)
            } catch (_: Exception) {}

            Result.success(
                workDataOf(
                    KEY_INDEXED_CHUNKS to totalIndexedChunks,
                    KEY_FAILED_COUNT to failures.size,
                    KEY_ERROR_SUMMARY to (errorSummary ?: "")
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped by the user or the system: let WorkManager mark the work cancelled.
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(
                workDataOf(
                    KEY_ERROR_SUMMARY to "Unexpected indexing error: ${e.localizedMessage ?: "Unknown failure"}"
                )
            )
        } finally {
            // Whatever the outcome (done, failed, cancelled) the status-bar notification goes away on its own.
            IndexingNotifier.cancel(context, IndexingNotifier.ID_DOCUMENT_INDEX)
        }
    }

    private class FileIndexOutcome(
        val chunks: Int,
        val failures: List<String>,
        val stopped: Boolean
    )

    /**
     * Indexes [files] with a small worker pool. Slot 0 always runs; extra slots only pick up files while
     * [IndexingPowerPolicy] grants parallelism (charging & idle), so the moment the user picks up the
     * phone or it is unplugged, indexing falls back to a single gentle worker.
     */
    private suspend fun indexFilesAdaptive(
        files: List<androidx.documentfile.provider.DocumentFile>,
        title: (ordinal: Int, total: Int, percent: Int) -> String
    ): FileIndexOutcome = coroutineScope {
        val total = files.size
        val nextIndex = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val chunks = AtomicInteger(0)
        val failures = Collections.synchronizedList(mutableListOf<String>())

        val workers = (0 until IndexingPowerPolicy.MAX_PARALLEL_FILES).map { slot ->
            async {
                while (true) {
                    if (isStopped) return@async
                    if (slot > 0) {
                        if (nextIndex.get() >= total) return@async
                        if (IndexingPowerPolicy.current().parallelFiles <= slot) {
                            delay(1000)
                            continue
                        }
                    }
                    val index = nextIndex.getAndIncrement()
                    if (index >= total) return@async

                    val docFile = files[index]
                    val fileName = docFile.name ?: "Document"
                    val result = repository.indexDocumentSafely(docFile) { step, cur, tot ->
                        val subFactor = if (tot > 0) cur.toFloat() / tot.toFloat() else 0f
                        val done = completed.get()
                        val percent = (((done + subFactor) / total.toFloat()) * 100).toInt().coerceIn(0, 99)
                        val ordinal = (done + 1).coerceAtMost(total)
                        setForegroundAsync(createForegroundInfo(percent, title(ordinal, total, percent), "$fileName • $step"))
                        setProgressAsync(
                            workDataOf(
                                KEY_PROGRESS_CURRENT to ordinal,
                                KEY_PROGRESS_TOTAL to total,
                                KEY_PROGRESS_PERCENT to percent,
                                KEY_CURRENT_FILE to fileName,
                                KEY_CURRENT_PHASE to step
                            )
                        )
                    }
                    if (result.isSuccess) {
                        chunks.addAndGet(result.chunksIndexed)
                    } else {
                        failures.add("$fileName: ${result.errorMessage ?: "Parsing failure"}")
                    }
                    completed.incrementAndGet()
                }
            }
        }
        workers.awaitAll()

        FileIndexOutcome(chunks.get(), failures.toList(), isStopped)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return IndexingNotifier.foregroundInfo(
            IndexingNotifier.ID_DOCUMENT_INDEX,
            IndexingNotifier.build(context, "DocuVector Offline Indexer", "Preparing document indexer…", null)
        )
    }

    private var lastPostedAtMillis = 0L

    /**
     * Shows the notification as a foreground service so Android keeps the indexer alive. If the system refuses
     * to start a foreground service right now, fall back to a plain ongoing notification instead of failing.
     */
    private suspend fun startForegroundSafely(title: String, message: String) {
        try {
            setForeground(
                IndexingNotifier.foregroundInfo(
                    IndexingNotifier.ID_DOCUMENT_INDEX,
                    IndexingNotifier.build(context, title, message, 0)
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            IndexingNotifier.show(context, IndexingNotifier.ID_DOCUMENT_INDEX, title, message, 0)
        }
        lastPostedAtMillis = System.currentTimeMillis()
    }

    /** Appends the live indexing speed (Turbo / Slowed to N% / Paused) to a notification line. */
    private fun withSpeed(message: String): String {
        val speed = IndexingPowerPolicy.current()
        return "$message • ${speed.summary()}"
    }

    private fun buildNotification(title: String, message: String, progress: Int): Notification {
        val builder = NotificationCompat.Builder(context, DocuVectorApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(withSpeed(message))
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress, false)

        return builder.build()
    }
}

package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.example.DocuVectorApp
import com.example.MainActivity
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentChunkEntity
import com.example.engine.ChatParser
import com.example.engine.HardwareMonitor
import com.example.engine.OnDeviceEmbeddingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

sealed interface ChatIndexingProgress {
    data object Idle : ChatIndexingProgress
    data class Active(
        val fileName: String,
        val step: String,
        val current: Int,
        val total: Int,
        val percent: Int,
        val isPaused: Boolean
    ) : ChatIndexingProgress
    data class Completed(
        val fileName: String,
        val chunksIndexed: Int,
        val message: String
    ) : ChatIndexingProgress
    data class Failed(
        val fileName: String,
        val error: String
    ) : ChatIndexingProgress
}

/**
 * Background Foreground Service that parses local SMS or messaging backup files (XML, JSON, WhatsApp TXT)
 * and adds them to the vector search index, ensuring private, 100% offline indexing of chat history.
 */
class ChatBackupIndexingService : Service() {

    companion object {
        private const val TAG = "ChatBackupService"
        const val NOTIFICATION_ID = 2002
        const val CHANNEL_ID = "docuvector_chat_indexing_channel"

        const val ACTION_INDEX_URI = "com.example.service.ACTION_INDEX_CHAT_URI"
        const val ACTION_INDEX_FILES = "com.example.service.ACTION_INDEX_CHAT_FILES"
        const val ACTION_PAUSE_RESUME = "com.example.service.ACTION_PAUSE_RESUME_INDEXING"
        const val ACTION_CANCEL = "com.example.service.ACTION_CANCEL_INDEXING"
        const val EXTRA_FILE_URI = "extra_file_uri"
        const val EXTRA_FILE_NAME = "extra_file_name"

        private val _serviceProgress = MutableStateFlow<ChatIndexingProgress>(ChatIndexingProgress.Idle)
        val serviceProgress: StateFlow<ChatIndexingProgress> = _serviceProgress.asStateFlow()

        fun startForUri(context: Context, uri: Uri, fileName: String? = null) {
            val intent = Intent(context, ChatBackupIndexingService::class.java).apply {
                action = ACTION_INDEX_URI
                putExtra(EXTRA_FILE_URI, uri.toString())
                if (fileName != null) putExtra(EXTRA_FILE_NAME, fileName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var indexingJob: Job? = null

    private lateinit var chatParser: ChatParser
    private lateinit var embeddingEngine: OnDeviceEmbeddingEngine
    private lateinit var database: AppDatabase

    inner class LocalBinder : Binder() {
        fun getService(): ChatBackupIndexingService = this@ChatBackupIndexingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        chatParser = ChatParser()
        embeddingEngine = OnDeviceEmbeddingEngine(applicationContext)
        database = AppDatabase.getInstance(applicationContext)
        Log.i(TAG, "ChatBackupIndexingService created")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_RESUME -> {
                val isPaused = HardwareMonitor.toggleIndexingPaused()
                updateNotification("DocuVector Chat Indexer", if (isPaused) "Indexing paused (Gaming Mode)" else "Indexing chat history offline…", 50)
            }
            ACTION_CANCEL -> {
                indexingJob?.cancel()
                _serviceProgress.value = ChatIndexingProgress.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_INDEX_URI -> {
                val uriStr = intent.getStringExtra(EXTRA_FILE_URI)
                val explicitName = intent.getStringExtra(EXTRA_FILE_NAME)
                if (uriStr != null) {
                    startIndexingUri(Uri.parse(uriStr), explicitName)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        indexingJob?.cancel()
        serviceScope.cancel()
        Log.i(TAG, "ChatBackupIndexingService destroyed")
    }

    private fun startIndexingUri(uri: Uri, explicitName: String?) {
        indexingJob?.cancel()

        startForeground(
            NOTIFICATION_ID,
            buildNotification("DocuVector Chat Indexer", "Preparing local chat backup…", 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        indexingJob = serviceScope.launch {
            try {
                val fileName = explicitName ?: resolveFileName(uri)
                _serviceProgress.value = ChatIndexingProgress.Active(
                    fileName = fileName,
                    step = "Parsing chat & SMS backup stream…",
                    current = 0,
                    total = 100,
                    percent = 5,
                    isPaused = HardwareMonitor.isIndexingPaused.value
                )

                // Cooperative pause point before heavy parsing
                HardwareMonitor.checkPausePoint()

                val chunks = withContext(Dispatchers.IO) {
                    openStream(uri).use { stream ->
                        chatParser.parseChatBackupStream(stream, fileName)
                    }
                }

                if (chunks.isEmpty()) {
                    _serviceProgress.value = ChatIndexingProgress.Failed(
                        fileName = fileName,
                        error = "No readable chat messages found in $fileName"
                    )
                    updateNotification("Chat Indexing Notice", "No valid messages found in $fileName", 100)
                    stopSelf()
                    return@launch
                }

                _serviceProgress.value = ChatIndexingProgress.Active(
                    fileName = fileName,
                    step = "Generating on-device vector embeddings for ${chunks.size} chat segments…",
                    current = 0,
                    total = chunks.size,
                    percent = 15,
                    isPaused = HardwareMonitor.isIndexingPaused.value
                )

                val fileUriStr = uri.toString()
                val dao = database.documentChunkDao()
                val existingChunks = dao.getChunksForFile(fileUriStr)
                val existingMap = existingChunks.associateBy { it.hash }

                val finalEntities = mutableListOf<DocumentChunkEntity>()
                val chunksToEmbed = mutableListOf<Pair<Int, String>>()

                chunks.forEachIndexed { idx, chunkText ->
                    val hash = computeSha256("$fileUriStr:$idx:$chunkText")
                    val existing = existingMap[hash]
                    if (existing != null) {
                        finalEntities.add(existing)
                    } else {
                        chunksToEmbed.add(Pair(idx, chunkText))
                    }
                }

                val batchSize = OnDeviceEmbeddingEngine.DEFAULT_BATCH_SIZE
                val totalBatches = (chunksToEmbed.size + batchSize - 1) / batchSize

                for (b in 0 until totalBatches) {
                    HardwareMonitor.checkPausePoint()

                    val start = b * batchSize
                    val end = minOf(start + batchSize, chunksToEmbed.size)
                    val batch = chunksToEmbed.subList(start, end)

                    val pct = (15 + (((b + 1).toFloat() / totalBatches.toFloat()) * 75)).toInt()
                    val stepMsg = "Embedding chat segments ${start + 1}-$end of ${chunksToEmbed.size} (${pct}%)"

                    _serviceProgress.value = ChatIndexingProgress.Active(
                        fileName = fileName,
                        step = stepMsg,
                        current = end,
                        total = chunksToEmbed.size,
                        percent = pct,
                        isPaused = HardwareMonitor.isIndexingPaused.value
                    )
                    updateNotification("Indexing Chat Backup: $fileName", stepMsg, pct)

                    val texts = batch.map { it.second }
                    val embeddings = embeddingEngine.embedBatch(texts, batchSize = batchSize)

                    for (i in batch.indices) {
                        val (idx, text) = batch[i]
                        val emb = embeddings[i]
                        val blob = embeddingEngine.floatArrayToByteArray(emb)

                        finalEntities.add(
                            DocumentChunkEntity(
                                fileUri = fileUriStr,
                                fileName = fileName,
                                chunkIndex = idx,
                                chunkText = text,
                                hash = computeSha256("$fileUriStr:$idx:$text"),
                                timestamp = System.currentTimeMillis(),
                                embeddingBlob = blob,
                                tags = "Chat, Messaging"
                            )
                        )
                    }
                }

                // Commit to Room DB atomically
                updateNotification("Committing Index", "Saving chat vectors into SQLite FTS…", 95)
                dao.deleteFileRecord(fileUriStr)
                dao.insertChunksWithFts(finalEntities)
                dao.setTagsForFile(fileUriStr, listOf("Chat", "Messaging"))

                _serviceProgress.value = ChatIndexingProgress.Completed(
                    fileName = fileName,
                    chunksIndexed = finalEntities.size,
                    message = "Successfully indexed ${finalEntities.size} chat conversation chunks offline"
                )
                updateNotification("Chat Indexing Complete", "Indexed ${finalEntities.size} chat segments privately", 100)

                // Refresh widgets
                try {
                    com.example.widget.DocuVectorWidget.updateAllWidgets(applicationContext)
                } catch (_: Exception) {}

            } catch (e: Exception) {
                Log.e(TAG, "Failed indexing chat backup: ${e.message}", e)
                _serviceProgress.value = ChatIndexingProgress.Failed(
                    fileName = explicitName ?: "Chat Backup",
                    error = e.localizedMessage ?: "Unknown parsing error"
                )
                updateNotification("Indexing Failed", e.localizedMessage ?: "Chat parsing error", 100)
            } finally {
                stopSelf()
            }
        }
    }

    private fun openStream(uri: Uri): InputStream {
        return if (uri.scheme == "file" || uri.scheme.isNullOrEmpty()) {
            val path = uri.path ?: uri.toString().removePrefix("file://")
            File(path).inputStream()
        } else {
            contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open input stream for $uri")
        }
    }

    private fun resolveFileName(uri: Uri): String {
        return if (uri.scheme == "file") {
            File(uri.path ?: "").name
        } else {
            val doc = DocumentFile.fromSingleUri(this, uri)
            doc?.name ?: uri.lastPathSegment ?: "chat_backup.xml"
        }
    }

    private fun computeSha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DocuVector Chat Indexer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Offline background parsing and vector indexing for SMS and chat history"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String, progress: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, ChatBackupIndexingService::class.java).apply {
            action = ACTION_PAUSE_RESUME
        }
        val pendingPause = PendingIntent.getService(
            this, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPaused = HardwareMonitor.isIndexingPaused.value
        val pauseActionTitle = if (isPaused) "Resume Indexing" else "Gaming Mode (Pause)"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingOpen)
            .setOngoing(progress < 100)
            .setProgress(100, progress, false)
            .addAction(android.R.drawable.ic_media_pause, pauseActionTitle, pendingPause)
            .build()
    }

    private fun updateNotification(title: String, content: String, progress: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(title, content, progress))
    }
}

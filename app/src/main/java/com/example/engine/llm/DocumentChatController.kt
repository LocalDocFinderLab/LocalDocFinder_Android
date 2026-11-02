package com.example.engine.llm

import android.content.Context
import android.util.Log
import com.example.data.repository.DocumentRepository
import com.example.engine.SearchMode
import com.example.engine.extraction.ChunkMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** One message in the chat. Answers carry the excerpts they were based on. */
data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val thinking: String = "",
    val sources: List<ChatSource> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
    val modelName: String? = null
)

/** What the chat engine is doing, for the status line at the top of the chat. */
sealed interface ChatEngineStatus {
    data object NoModel : ChatEngineStatus
    data object Idle : ChatEngineStatus
    data class Loading(val model: ChatModel) : ChatEngineStatus
    data object Searching : ChatEngineStatus
    data class Answering(val model: ChatModel) : ChatEngineStatus
    data class Error(val message: String) : ChatEngineStatus
}

/**
 * "Chat with your files": answers questions with an on-device model, grounded in excerpts found by the app's own
 * hybrid search (retrieval-augmented generation). Everything runs on the phone.
 *
 *   question -> [ChatPromptBuilder.searchQueryFor] -> hybrid search (top files' best chunks)
 *            -> [ChatPromptBuilder.build] (fits the model's context) -> [LocalLlmEngine] (streamed answer)
 */
class DocumentChatController(
    context: Context,
    private val repository: DocumentRepository,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    val store = LlmModelStore(appContext)
    private val engine = LocalLlmEngine(appContext)
    private val ids = AtomicLong(1)
    private var answerJob: Job? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _selectedModel = MutableStateFlow(store.selectedModel)
    val selectedModel: StateFlow<ChatModel> = _selectedModel.asStateFlow()

    private val _installed = MutableStateFlow(store.installedModels().toSet())
    val installedModels: StateFlow<Set<ChatModel>> = _installed.asStateFlow()

    private val _status = MutableStateFlow<ChatEngineStatus>(ChatEngineStatus.Idle)
    val status: StateFlow<ChatEngineStatus> = _status.asStateFlow()

    private val _allowMobileData = MutableStateFlow(store.allowMobileData)
    val allowMobileData: StateFlow<Boolean> = _allowMobileData.asStateFlow()

    /** Download state of every model, refreshed from WorkManager. */
    val downloads: StateFlow<Map<ChatModel, LlmDownloadWorker.DownloadState>> =
        combine(ChatModel.entries.map { m -> LlmDownloadWorker.progressFlow(appContext, m) }) { states ->
            val map = ChatModel.entries.zip(states.toList()).toMap()
            if (map.values.any { it is LlmDownloadWorker.DownloadState.Done }) refreshInstalled()
            map
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Sources the user tapped "ask about this file" on, to search only within it. */
    private val _focusFile = MutableStateFlow<Pair<String, String>?>(null)
    val focusFile: StateFlow<Pair<String, String>?> = _focusFile.asStateFlow()

    fun refreshInstalled() {
        val installed = store.installedModels().toSet()
        _installed.value = installed
        // The selected model went away (deleted) but another one is there: switch to it.
        if (_selectedModel.value !in installed && installed.isNotEmpty() && !isDownloading(_selectedModel.value)) {
            selectModel(installed.first())
        }
    }

    private fun isDownloading(model: ChatModel): Boolean {
        val s = downloads.value[model]
        return s is LlmDownloadWorker.DownloadState.Running || s is LlmDownloadWorker.DownloadState.Waiting
    }

    fun selectModel(model: ChatModel) {
        store.selectedModel = model
        _selectedModel.value = model
        if (!store.isInstalled(model)) download(model)
    }

    fun download(model: ChatModel) {
        store.autoDownloadDisabled = false
        LlmDownloadWorker.enqueue(appContext, model, userRequested = true)
    }

    fun cancelDownload(model: ChatModel) {
        if (model == ChatModel.DEFAULT) store.autoDownloadDisabled = true
        LlmDownloadWorker.cancel(appContext, model)
    }

    fun deleteModel(model: ChatModel) {
        scope.launch {
            if (engine.loadedModelFile != null && engine.loadedModelFile == store.modelFile(model)) engine.close()
            LlmDownloadWorker.cancel(appContext, model)
            store.delete(model)
            if (store.installedModels().isEmpty()) store.autoDownloadDisabled = true
            refreshInstalled()
        }
    }

    fun setAllowMobileData(allow: Boolean) {
        store.allowMobileData = allow
        _allowMobileData.value = allow
        // Re-queue waiting downloads with the new network rule.
        for ((model, state) in downloads.value) {
            if (state is LlmDownloadWorker.DownloadState.Waiting || state is LlmDownloadWorker.DownloadState.Running) {
                LlmDownloadWorker.enqueue(appContext, model, userRequested = true)
            }
        }
    }

    fun setFocusFile(fileUri: String?, fileName: String?) {
        _focusFile.value = if (fileUri != null && fileName != null) fileUri to fileName else null
    }

    fun clear() {
        stop()
        _messages.value = emptyList()
    }

    fun stop() {
        answerJob?.cancel()
        answerJob = null
    }

    fun send(question: String) {
        val q = question.trim()
        if (q.isEmpty() || answerJob?.isActive == true) return
        val model = _selectedModel.value
        val history = _messages.value.zipWithNext()
            .filter { (a, b) -> a.fromUser && !b.fromUser && b.error == null && b.text.isNotBlank() }
            .map { (a, b) -> ChatTurn(a.text, b.text) }
        val userMessage = ChatMessage(ids.getAndIncrement(), fromUser = true, text = q)
        val answerId = ids.getAndIncrement()
        _messages.value = _messages.value + userMessage +
            ChatMessage(answerId, fromUser = false, text = "", streaming = true, modelName = model.shortName)

        answerJob = scope.launch {
            try {
                val file = store.modelFile(model)
                if (file == null) {
                    _status.value = ChatEngineStatus.NoModel
                    finish(answerId, error = "${model.shortName} is not downloaded yet.")
                    return@launch
                }

                _status.value = ChatEngineStatus.Searching
                val sources = retrieve(q)

                if (engine.loadedModelFile != file) _status.value = ChatEngineStatus.Loading(model)
                engine.load(file)

                val prompt = ChatPromptBuilder.build(model.template, engine.contextTokens, q, sources, history)
                update(answerId) { it.copy(sources = prompt.sourcesUsed) }
                _status.value = ChatEngineStatus.Answering(model)
                val started = System.currentTimeMillis()
                var raw = ""
                engine.generate(prompt.text).collect { text ->
                    raw = text
                    val parsed = ChatPromptBuilder.parseAnswer(text, model.template)
                    update(answerId) { it.copy(text = parsed.text, thinking = parsed.thinking) }
                }
                val parsed = ChatPromptBuilder.parseAnswer(raw, model.template)
                com.example.dev.DevLog.i(
                    "Chat",
                    "${model.id}: ${prompt.sourcesUsed.size} excerpts, ${raw.length} chars in ${System.currentTimeMillis() - started} ms"
                )
                finish(answerId, error = if (parsed.text.isBlank() && parsed.thinking.isBlank()) "The model gave no answer." else null)
                _status.value = ChatEngineStatus.Idle
            } catch (e: CancellationException) {
                finish(answerId, error = null, stopped = true)
                _status.value = ChatEngineStatus.Idle
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Chat failed: ${e.message}", e)
                finish(answerId, error = e.message ?: "Something went wrong")
                _status.value = ChatEngineStatus.Error(e.message ?: "Something went wrong")
            }
        }
    }

    /** The best excerpt of each of the top files for [question], or the best parts of the focused file. */
    private suspend fun retrieve(question: String): List<ChatSource> {
        _focusFile.value?.let { (uri, name) ->
            val chunks = repository.chunksForFile(uri).filter { it.chunkText.isNotBlank() }
            return ChatPromptBuilder.rankChunks(question, chunks.map { it.chunkText }, MAX_SOURCES)
                .mapIndexed { i, index ->
                    val c = chunks[index]
                    ChatSource(i + 1, c.id, uri, name, ChunkMetadata.pageLabel(c.metadata), c.chunkText)
                }
        }
        val query = ChatPromptBuilder.searchQueryFor(question)
        val results = try {
            repository.search(query, SearchMode.HYBRID, topK = 12, semanticWeight = 0.5f)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Search for chat failed: ${e.message}")
            emptyList()
        }
        return results
            .filter { it.chunkText.isNotBlank() }
            .take(MAX_SOURCES)
            .mapIndexed { i, r -> ChatSource(i + 1, r.chunkId, r.fileUri, r.fileName, r.pageLabel, r.chunkText) }
    }

    private fun update(id: Long, change: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) change(it) else it }
    }

    private fun finish(id: Long, error: String?, stopped: Boolean = false) {
        update(id) {
            it.copy(
                streaming = false,
                error = error,
                text = if (stopped && it.text.isBlank() && it.thinking.isBlank()) "(stopped)" else it.text
            )
        }
    }

    /** Frees the model's memory, e.g. when the app is closed. */
    fun release() {
        stop()
        // Not on [scope]: it is being cancelled when this is called from ViewModel.onCleared().
        Thread { kotlinx.coroutines.runBlocking { engine.close() } }.start()
    }

    private companion object {
        const val TAG = "DocumentChat"
        const val MAX_SOURCES = 5
    }
}

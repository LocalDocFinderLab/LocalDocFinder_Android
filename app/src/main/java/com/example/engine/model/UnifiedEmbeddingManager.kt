package com.example.engine.model

import android.content.Context
import android.util.Log
import com.example.engine.OnDeviceEmbeddingEngine
import com.example.engine.SentenceBertTfliteEngine
import com.example.engine.VectorSimilarityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Coordinates and manages all indexing and vector embedding models for the app.
 * Every model runs fully on-device: no document text, search query or embedding ever leaves the phone.
 * - Sentence-BERT ONNX-TFLite (SOTA Local with all-MiniLM-L6-v2)
 * - On-Device BGE Neural Engine (hardware accelerated with Pixel EdgeTPU / QNN / GPU / XNNPACK)
 */
class UnifiedEmbeddingManager(
    private val context: Context,
    val onDeviceEngine: OnDeviceEmbeddingEngine = OnDeviceEmbeddingEngine(context),
    val sentenceBertEngine: SentenceBertTfliteEngine = SentenceBertTfliteEngine(context)
) {
    companion object {
        private const val TAG = "UnifiedEmbeddingManager"
        private const val PREFS_NAME = "docuvector_model_prefs"
        private const val KEY_ACTIVE_MODEL = "active_model_id"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _activeModel = MutableStateFlow(loadSavedModel())
    val activeModel: StateFlow<EmbeddingModelType> = _activeModel.asStateFlow()

    private val _isReindexing = MutableStateFlow(false)
    val isReindexing: StateFlow<Boolean> = _isReindexing.asStateFlow()

    private val _reindexingProgress = MutableStateFlow(0f)
    val reindexingProgress: StateFlow<Float> = _reindexingProgress.asStateFlow()

    private val _reindexingStatus = MutableStateFlow("")
    val reindexingStatus: StateFlow<String> = _reindexingStatus.asStateFlow()

    fun getActiveModel(): EmbeddingModelType = _activeModel.value

    fun setActiveModel(model: EmbeddingModelType) {
        _activeModel.value = model
        prefs.edit().putString(KEY_ACTIVE_MODEL, model.id).apply()
        Log.i(TAG, "Switched active embedding model to: ${model.displayName}")
    }

    private fun loadSavedModel(): EmbeddingModelType {
        val savedId = prefs.getString(KEY_ACTIVE_MODEL, null)
        return EmbeddingModelType.entries.firstOrNull { it.id == savedId }
            ?: EmbeddingModelType.ONNX_SENTENCE_BERT_TFLITE
    }

    /**
     * Generates a dense normalized embedding vector for a single text using the currently active model.
     * Guaranteed to return a valid L2-normalized FloatArray.
     */
    suspend fun embedText(
        text: String,
        isQuery: Boolean = false
    ): FloatArray = withContext(Dispatchers.Default) {
        val current = _activeModel.value
        if (current == EmbeddingModelType.ONNX_SENTENCE_BERT_TFLITE) {
            return@withContext sentenceBertEngine.embedText(text)
        }

        // On-device neural fallback
        onDeviceEngine.embedText(text)
    }

    /**
     * Generates embeddings for a batch of texts using the currently active model.
     */
    suspend fun embedBatch(
        texts: List<String>,
        isQuery: Boolean = false,
        batchSize: Int = OnDeviceEmbeddingEngine.DEFAULT_BATCH_SIZE
    ): List<FloatArray> = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) return@withContext emptyList()
        val current = _activeModel.value

        if (current == EmbeddingModelType.ONNX_SENTENCE_BERT_TFLITE) {
            return@withContext sentenceBertEngine.embedBatch(texts)
        }

        onDeviceEngine.embedBatch(texts, batchSize = batchSize)
    }

    /**
     * Runs live semantic similarity comparison between a query and two texts using the selected model.
     */
    suspend fun benchmarkSemanticSimilarity(
        query: String,
        textA: String,
        textB: String,
        model: EmbeddingModelType = _activeModel.value
    ): Triple<Float, Float, Long> = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        val queryVec = embedWith(model, query)
        val vecA = embedWith(model, textA)
        val vecB = embedWith(model, textB)

        val simA = VectorSimilarityUtils.calculateCosineSimilarity(queryVec, vecA)
        val simB = VectorSimilarityUtils.calculateCosineSimilarity(queryVec, vecB)
        val elapsed = System.currentTimeMillis() - start
        Triple(simA, simB, elapsed)
    }

    private suspend fun embedWith(model: EmbeddingModelType, text: String): FloatArray =
        if (model == EmbeddingModelType.ONNX_SENTENCE_BERT_TFLITE) {
            sentenceBertEngine.embedText(text)
        } else {
            onDeviceEngine.embedText(text)
        }
}

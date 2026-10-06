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
 * Supports:
 * - Gemini Embeddings 2.0 (gemini-embedding-2-preview)
 * - Hybrid AI Smart Indexer (gemini-3.5-flash + gemini-embedding-2-preview)
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

    val geminiService = GeminiEmbeddingService()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _activeModel = MutableStateFlow(loadSavedModel())
    val activeModel: StateFlow<EmbeddingModelType> = _activeModel.asStateFlow()

    private val _isReindexing = MutableStateFlow(false)
    val isReindexing: StateFlow<Boolean> = _isReindexing.asStateFlow()

    private val _reindexingProgress = MutableStateFlow(0f)
    val reindexingProgress: StateFlow<Float> = _reindexingProgress.asStateFlow()

    private val _reindexingStatus = MutableStateFlow("")
    val reindexingStatus: StateFlow<String> = _reindexingStatus.asStateFlow()

    fun isGeminiConfigured(): Boolean = geminiService.isApiKeyAvailable()

    fun getActiveModel(): EmbeddingModelType = _activeModel.value

    fun setActiveModel(model: EmbeddingModelType) {
        _activeModel.value = model
        prefs.edit().putString(KEY_ACTIVE_MODEL, model.id).apply()
        Log.i(TAG, "Switched active embedding model to: ${model.displayName}")
    }

    private fun loadSavedModel(): EmbeddingModelType {
        val savedId = prefs.getString(KEY_ACTIVE_MODEL, null)
        val defaultModel = if (geminiService.isApiKeyAvailable()) {
            EmbeddingModelType.GEMINI_EMBEDDING_2
        } else {
            EmbeddingModelType.ONNX_SENTENCE_BERT_TFLITE
        }
        return EmbeddingModelType.entries.firstOrNull { it.id == savedId } ?: defaultModel
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
        if ((current == EmbeddingModelType.GEMINI_EMBEDDING_2 || current == EmbeddingModelType.HYBRID_AI_ENRICHED) &&
            geminiService.isApiKeyAvailable()
        ) {
            val geminiVec = geminiService.embedText(text, isQuery = isQuery, outputDimension = current.dimensions)
            if (geminiVec != null && geminiVec.isNotEmpty()) {
                return@withContext geminiVec
            }
            Log.d(TAG, "Gemini embedding API unavailable, smoothly falling back to Sentence-BERT Engine")
        }

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

        if ((current == EmbeddingModelType.GEMINI_EMBEDDING_2 || current == EmbeddingModelType.HYBRID_AI_ENRICHED) &&
            geminiService.isApiKeyAvailable()
        ) {
            val geminiBatch = geminiService.embedBatch(texts, isQuery = isQuery, outputDimension = current.dimensions)
            if (geminiBatch != null && geminiBatch.size == texts.size) {
                return@withContext geminiBatch
            }
            Log.d(TAG, "Gemini batch embedding API unavailable, falling back to Sentence-BERT batch engine")
        }

        if (current == EmbeddingModelType.ONNX_SENTENCE_BERT_TFLITE) {
            return@withContext sentenceBertEngine.embedBatch(texts)
        }

        onDeviceEngine.embedBatch(texts, batchSize = batchSize)
    }

    /**
     * Analyzes document content with Gemini Flash during indexing to extract summaries, tags, and keywords.
     */
    suspend fun enrichDocument(
        fileName: String,
        content: String
    ): GeminiEmbeddingService.EnrichedMetadata? {
        val current = _activeModel.value
        if (current == EmbeddingModelType.HYBRID_AI_ENRICHED && geminiService.isApiKeyAvailable()) {
            return geminiService.enrichDocumentContent(fileName, content)
        }
        return null
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
        val queryVec: FloatArray
        val vecA: FloatArray
        val vecB: FloatArray

        if ((model == EmbeddingModelType.GEMINI_EMBEDDING_2 || model == EmbeddingModelType.HYBRID_AI_ENRICHED) &&
            geminiService.isApiKeyAvailable()
        ) {
            queryVec = geminiService.embedText(query, isQuery = true, outputDimension = model.dimensions) ?: onDeviceEngine.embedText(query)
            vecA = geminiService.embedText(textA, isQuery = false, outputDimension = model.dimensions) ?: onDeviceEngine.embedText(textA)
            vecB = geminiService.embedText(textB, isQuery = false, outputDimension = model.dimensions) ?: onDeviceEngine.embedText(textB)
        } else {
            queryVec = onDeviceEngine.embedText(query)
            vecA = onDeviceEngine.embedText(textA)
            vecB = onDeviceEngine.embedText(textB)
        }

        val simA = VectorSimilarityUtils.calculateCosineSimilarity(queryVec, vecA)
        val simB = VectorSimilarityUtils.calculateCosineSimilarity(queryVec, vecB)
        val elapsed = System.currentTimeMillis() - start
        Triple(simA, simB, elapsed)
    }
}

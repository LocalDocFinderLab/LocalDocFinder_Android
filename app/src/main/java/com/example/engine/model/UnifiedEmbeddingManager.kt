package com.example.engine.model

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.engine.OnDeviceEmbeddingEngine
import com.example.engine.VectorSimilarityUtils
import com.example.engine.embedding.EmbeddingModelStore
import com.example.engine.embedding.TfliteTextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Vectors plus the id of the model that really produced them (may differ from the selected model). */
class TaggedEmbeddings(val vectors: List<FloatArray>, val modelId: String)

/**
 * Chooses and runs the embedding model for indexing and search. Everything is on-device.
 *
 * The user selects a model ([activeModel]). If its files are not installed or fail to load, the manager
 * silently falls back to the built-in embedder — and says so through [effectiveModel] and by tagging every
 * vector with the model that actually made it ([TaggedEmbeddings.modelId]). Search compares a query only with
 * chunks carrying the same tag, because vectors from different models are not comparable.
 *
 * Use [getInstance] in app code so all indexers and the UI share one set of loaded models.
 */
class UnifiedEmbeddingManager(
    private val context: Context,
    val onDeviceEngine: OnDeviceEmbeddingEngine = OnDeviceEmbeddingEngine(context),
    val store: EmbeddingModelStore = EmbeddingModelStore(context)
) {
    companion object {
        private const val TAG = "UnifiedEmbeddingManager"
        private const val PREFS_NAME = "docuvector_model_prefs"
        private const val KEY_ACTIVE_MODEL = "active_model_id"

        @Volatile
        private var instance: UnifiedEmbeddingManager? = null

        fun getInstance(context: Context): UnifiedEmbeddingManager =
            instance ?: synchronized(this) {
                instance ?: UnifiedEmbeddingManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Guards [loadedEmbedders] and [brokenModels]; held only briefly (never while loading or copying files). */
    private val stateLock = Any()

    /** Serialises loading a model, which can take seconds, without blocking the UI thread's state reads. */
    private val loadLock = Any()

    private val loadedEmbedders = HashMap<EmbeddingModelType, TfliteTextEmbedder>()

    /** Models whose files exist but could not be loaded or crashed at runtime; treated as unavailable. */
    private val brokenModels = HashSet<EmbeddingModelType>()

    private val _activeModel = MutableStateFlow(loadSavedModel())
    val activeModel: StateFlow<EmbeddingModelType> = _activeModel.asStateFlow()

    private val _effectiveModel = MutableStateFlow(effectiveFor(_activeModel.value))

    /** The model that indexing and search really use right now (the active one, or built-in as fallback). */
    val effectiveModel: StateFlow<EmbeddingModelType> = _effectiveModel.asStateFlow()

    private val _installedModels = MutableStateFlow(installedSnapshot())

    /** Models usable right now: built-in plus every model whose files are present and loadable. */
    val installedModels: StateFlow<Set<EmbeddingModelType>> = _installedModels.asStateFlow()

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
        refreshAvailability()
        Log.i(TAG, "Switched active embedding model to: ${model.displayName}")
    }

    private fun loadSavedModel(): EmbeddingModelType =
        EmbeddingModelType.fromId(prefs.getString(KEY_ACTIVE_MODEL, null)) ?: EmbeddingModelType.DEFAULT

    // ---- Model files --------------------------------------------------------------------------------------

    /** Installs model files the user picked. Returns null on success or a message for the user. */
    fun importModel(model: EmbeddingModelType, tflite: Uri, vocab: Uri): String? {
        val error = store.importModel(model, tflite, vocab) // slow file copy: no lock held
        if (error == null) {
            val stale = synchronized(stateLock) {
                brokenModels.remove(model)
                loadedEmbedders.remove(model)
            }
            stale?.close() // the next use loads the new files
            refreshAvailability()
        }
        return error
    }

    fun removeModel(model: EmbeddingModelType) {
        val stale = synchronized(stateLock) {
            brokenModels.remove(model)
            loadedEmbedders.remove(model)
        }
        stale?.close()
        store.removeImported(model)
        refreshAvailability()
    }

    private fun isUsable(model: EmbeddingModelType): Boolean =
        model.isBuiltIn || (store.isInstalled(model) && synchronized(stateLock) { model !in brokenModels })

    private fun installedSnapshot(): Set<EmbeddingModelType> =
        EmbeddingModelType.entries.filterTo(HashSet()) { isUsable(it) }

    private fun effectiveFor(model: EmbeddingModelType): EmbeddingModelType =
        if (isUsable(model)) model else EmbeddingModelType.BUILTIN_LIGHTWEIGHT

    private fun refreshAvailability() {
        _installedModels.value = installedSnapshot()
        _effectiveModel.value = effectiveFor(_activeModel.value)
    }

    /** Loads (once) and returns the TFLite embedder for [model]; null if it can't be used. */
    private fun embedderFor(model: EmbeddingModelType): TfliteTextEmbedder? {
        if (model.isBuiltIn) return null
        synchronized(stateLock) {
            if (model in brokenModels) return null
            loadedEmbedders[model]?.let { return it }
        }
        synchronized(loadLock) {
            synchronized(stateLock) { loadedEmbedders[model]?.let { return it } } // loaded while we waited
            val created = TfliteTextEmbedder.create(context, model, store)
            synchronized(stateLock) {
                if (created != null) {
                    loadedEmbedders[model] = created
                } else if (store.isInstalled(model)) {
                    brokenModels.add(model) // files are present but unusable
                }
            }
            refreshAvailability()
            return created
        }
    }

    private fun markBroken(model: EmbeddingModelType) {
        val stale = synchronized(stateLock) {
            brokenModels.add(model)
            loadedEmbedders.remove(model)
        }
        stale?.close()
        refreshAvailability()
    }

    /** Id of the model whose vectors search should compare against (loads the model if needed). */
    suspend fun effectiveModelId(): String = withContext(Dispatchers.Default) {
        val wanted = _activeModel.value
        if (!wanted.isBuiltIn) embedderFor(wanted)
        _effectiveModel.value.id
    }

    // ---- Embedding ----------------------------------------------------------------------------------------

    /**
     * Embeds [texts] with the effective model and reports which model that was. Index with this (not
     * [embedBatch]) so each stored chunk can be tagged with the model that produced its vector.
     */
    suspend fun embedBatchTagged(
        texts: List<String>,
        isQuery: Boolean = false,
        batchSize: Int = OnDeviceEmbeddingEngine.DEFAULT_BATCH_SIZE
    ): TaggedEmbeddings = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) return@withContext TaggedEmbeddings(emptyList(), _effectiveModel.value.id)
        val wanted = _activeModel.value
        val embedder = if (wanted.isBuiltIn) null else embedderFor(wanted)
        if (embedder != null) {
            try {
                return@withContext TaggedEmbeddings(embedder.embedBatch(texts, isQuery, batchSize), wanted.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "${wanted.id} failed at runtime (${t.message}); falling back to built-in embedder")
                // Only blame the model if this embedder is still the installed one (not replaced by an import).
                if (synchronized(stateLock) { loadedEmbedders[wanted] === embedder }) markBroken(wanted)
            }
        }
        TaggedEmbeddings(
            onDeviceEngine.embedBatch(texts, batchSize = batchSize),
            EmbeddingModelType.BUILTIN_LIGHTWEIGHT.id
        )
    }

    /** Embeds one text with the effective model. L2-normalised. */
    suspend fun embedText(text: String, isQuery: Boolean = false): FloatArray =
        embedBatchTagged(listOf(text), isQuery, batchSize = 1).vectors.first()

    suspend fun embedBatch(
        texts: List<String>,
        isQuery: Boolean = false,
        batchSize: Int = OnDeviceEmbeddingEngine.DEFAULT_BATCH_SIZE
    ): List<FloatArray> = embedBatchTagged(texts, isQuery, batchSize).vectors

    /**
     * Live similarity check between a query and two texts using [model] (or the built-in embedder if that
     * model isn't installed). Returns (similarity to A, similarity to B, elapsed ms).
     */
    suspend fun benchmarkSemanticSimilarity(
        query: String,
        textA: String,
        textB: String,
        model: EmbeddingModelType = _activeModel.value
    ): Triple<Float, Float, Long> = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        val embedder = if (model.isBuiltIn) null else embedderFor(model)
        val queryVec: FloatArray
        val vecA: FloatArray
        val vecB: FloatArray
        if (embedder != null) {
            queryVec = embedder.embed(query, isQuery = true)
            vecA = embedder.embed(textA, isQuery = false)
            vecB = embedder.embed(textB, isQuery = false)
        } else {
            queryVec = onDeviceEngine.embedText(query)
            vecA = onDeviceEngine.embedText(textA)
            vecB = onDeviceEngine.embedText(textB)
        }
        val simA = VectorSimilarityUtils.calculateCosineSimilarity(queryVec, vecA)
        val simB = VectorSimilarityUtils.calculateCosineSimilarity(queryVec, vecB)
        Triple(simA, simB, System.currentTimeMillis() - start)
    }

    fun close() {
        val all = synchronized(stateLock) {
            loadedEmbedders.values.toList().also { loadedEmbedders.clear() }
        }
        all.forEach { it.close() }
    }
}

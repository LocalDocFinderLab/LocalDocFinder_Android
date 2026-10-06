package com.example

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentChunkEntity
import com.example.engine.extraction.ChunkMetadata
import com.example.engine.model.EmbeddingModelType
import com.example.engine.model.UnifiedEmbeddingManager
import com.example.worker.DownloadsFileObserverWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmbeddingModelManagerTest {

    private lateinit var context: Context
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("docuvector_model_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "embedding_models").deleteRecursively()
    }

    @After
    fun tearDown() {
        tempFiles.forEach { it.delete() }
        File(context.filesDir, "embedding_models").deleteRecursively()
    }

    private fun tempFile(name: String, bytes: ByteArray): Uri {
        val f = File(context.cacheDir, name).apply { writeBytes(bytes) }
        tempFiles.add(f)
        return Uri.fromFile(f)
    }

    /** Smallest byte string that passes the importer's flatbuffer check ("TFL3" at offset 4) but is not a model. */
    private val fakeTfliteBytes = byteArrayOf(0, 0, 0, 0, 'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte(), 1, 2, 3, 4)
    private val vocabBytes = "[PAD]\n[UNK]\n[CLS]\n[SEP]\nhello\n".toByteArray()

    @Test
    fun `no cloud models remain and the default is a real on-device model`() {
        assertEquals(EmbeddingModelType.BGE_SMALL_EN_V15, EmbeddingModelType.DEFAULT)
        EmbeddingModelType.entries.forEach { model ->
            val text = (model.id + model.modelName + model.description + model.displayName).lowercase()
            assertFalse("${model.id} must not reference Google/Gemini", "gemini" in text || "google" in text)
        }
    }

    @Test
    fun `legacy saved model ids map to current models and unknown ids use the default`() {
        assertEquals(EmbeddingModelType.ALL_MINILM_L6_V2, EmbeddingModelType.fromId("onnx_sentence_bert_tflite"))
        assertEquals(EmbeddingModelType.BUILTIN_LIGHTWEIGHT, EmbeddingModelType.fromId("on_device_neural_bge"))
        assertNull(EmbeddingModelType.fromId("gemini_embedding_2"))
        assertNull(EmbeddingModelType.fromId("hybrid_ai_enriched"))

        val prefs = context.getSharedPreferences("docuvector_model_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("active_model_id", "gemini_embedding_2").commit()
        assertEquals(EmbeddingModelType.DEFAULT, UnifiedEmbeddingManager(context).getActiveModel())
        prefs.edit().putString("active_model_id", "onnx_sentence_bert_tflite").commit()
        assertEquals(EmbeddingModelType.ALL_MINILM_L6_V2, UnifiedEmbeddingManager(context).getActiveModel())
    }

    @Test
    fun `falls back to the built-in embedder and tags vectors with it when model files are missing`() = runTest {
        val manager = UnifiedEmbeddingManager(context)
        manager.setActiveModel(EmbeddingModelType.BGE_SMALL_EN_V15)

        assertEquals(EmbeddingModelType.BGE_SMALL_EN_V15, manager.getActiveModel())
        assertEquals(EmbeddingModelType.BUILTIN_LIGHTWEIGHT, manager.effectiveModel.value)
        assertFalse(EmbeddingModelType.BGE_SMALL_EN_V15 in manager.installedModels.value)
        assertTrue(EmbeddingModelType.BUILTIN_LIGHTWEIGHT in manager.installedModels.value)

        val tagged = manager.embedBatchTagged(listOf("Query for local semantic search"), isQuery = true)
        assertEquals(EmbeddingModelType.BUILTIN_LIGHTWEIGHT.id, tagged.modelId)
        assertEquals(1, tagged.vectors.size)
        assertEquals(384, tagged.vectors.single().size)
        assertEquals(EmbeddingModelType.BUILTIN_LIGHTWEIGHT.id, manager.effectiveModelId())

        val vec = manager.embedText("hello world")
        val norm = kotlin.math.sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
        assertTrue("vector should be L2-normalised, norm=$norm", kotlin.math.abs(norm - 1f) < 0.05f)
    }

    @Test
    fun `importing a file that is not a tflite model is rejected and nothing is installed`() {
        val manager = UnifiedEmbeddingManager(context)
        val error = manager.importModel(
            EmbeddingModelType.BGE_SMALL_EN_V15,
            tempFile("notamodel.tflite", "definitely not a flatbuffer".toByteArray()),
            tempFile("vocab.txt", vocabBytes)
        )
        assertNotNull(error)
        assertFalse(manager.store.isInstalled(EmbeddingModelType.BGE_SMALL_EN_V15))
    }

    @Test
    fun `importing a vocab without BERT special tokens is rejected`() {
        val manager = UnifiedEmbeddingManager(context)
        val error = manager.importModel(
            EmbeddingModelType.BGE_SMALL_EN_V15,
            tempFile("model.tflite", fakeTfliteBytes),
            tempFile("vocab.txt", "just\nsome\nwords\n".toByteArray())
        )
        assertNotNull(error)
        assertFalse(manager.store.isInstalled(EmbeddingModelType.BGE_SMALL_EN_V15))
    }

    @Test
    fun `an installed but unloadable model degrades gracefully to the built-in embedder`() = runTest {
        val manager = UnifiedEmbeddingManager(context)
        manager.setActiveModel(EmbeddingModelType.ALL_MINILM_L6_V2)
        val error = manager.importModel(
            EmbeddingModelType.ALL_MINILM_L6_V2,
            tempFile("model.tflite", fakeTfliteBytes),
            tempFile("vocab.txt", vocabBytes)
        )
        assertNull(error)
        assertTrue(manager.store.isInstalled(EmbeddingModelType.ALL_MINILM_L6_V2))

        // The bytes are not a real graph, so loading fails; indexing must still work and say which model ran.
        val tagged = manager.embedBatchTagged(listOf("some text"), isQuery = false)
        assertEquals(EmbeddingModelType.BUILTIN_LIGHTWEIGHT.id, tagged.modelId)
        assertEquals(384, tagged.vectors.single().size)
        assertEquals(EmbeddingModelType.BUILTIN_LIGHTWEIGHT, manager.effectiveModel.value)
        assertFalse(EmbeddingModelType.ALL_MINILM_L6_V2 in manager.installedModels.value)

        // Removing the broken files resets it so a good import can be tried again.
        manager.removeModel(EmbeddingModelType.ALL_MINILM_L6_V2)
        assertFalse(manager.store.isInstalled(EmbeddingModelType.ALL_MINILM_L6_V2))
    }

    @Test
    fun `benchmark works with the built-in fallback`() = runTest {
        val manager = UnifiedEmbeddingManager(context)
        val (simA, simB, ms) = manager.benchmarkSemanticSimilarity(
            "vaccines and mRNA", "mRNA vaccines use lipid nanoparticles", "SQLite full text search"
        )
        assertTrue(simA in -1f..1f)
        assertTrue(simB in -1f..1f)
        assertTrue(ms >= 0)
    }

    @Test
    fun `stale chunk count only counts vectors from other models`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.documentChunkDao()
            fun chunk(name: String, metadata: String) = DocumentChunkEntity(
                fileUri = "file:///$name", fileName = name, chunkIndex = 0, chunkText = "text $name",
                hash = name, timestamp = 1L, embedding = FloatArray(4) { 0.5f }, metadata = metadata
            )
            dao.insertChunksWithFts(
                listOf(
                    chunk("mine_a", ChunkMetadata.encode(modelId = "bge_small_en_v15")),
                    chunk("mine_b", ChunkMetadata.encode(page = 3, pageEnd = 4, modelId = "bge_small_en_v15")),
                    chunk("other", ChunkMetadata.encode(modelId = "all_minilm_l6_v2")),
                    chunk("prefix_lookalike", ChunkMetadata.encode(modelId = "bge_small_en_v15_x")),
                    chunk("legacy_untagged", ""),
                    chunk("legacy_page_only", ChunkMetadata.encode(page = 2))
                )
            )
            assertEquals(4, dao.countChunksNotIndexedWith("bge_small_en_v15").first())
            assertEquals(5, dao.countChunksNotIndexedWith("all_minilm_l6_v2").first())
            assertEquals(6, dao.countChunksNotIndexedWith("nonexistent").first())
        } finally {
            db.close()
        }
    }

    @Test
    fun testDownloadsFileObserverWorkerScheduling() {
        DownloadsFileObserverWorker.scheduleDownloadsObserver(context)
        val workId = DownloadsFileObserverWorker.triggerImmediateScan(context)
        assertNotNull(workId)
    }
}

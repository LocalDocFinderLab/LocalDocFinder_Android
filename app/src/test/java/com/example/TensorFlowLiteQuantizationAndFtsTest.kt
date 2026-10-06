package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentChunkEntity
import com.example.data.local.DocumentChunkFtsEntity
import com.example.engine.OnDeviceEmbeddingEngine
import com.example.engine.TensorFlowLiteQuantizer
import com.example.engine.VectorSimilarityUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TensorFlowLiteQuantizationAndFtsTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testRoomDatabaseFtsIndexingAndSearch() = runBlocking {
        val dao = database.documentChunkDao()

        // Create sample document chunks with rich text contents
        val chunk1 = DocumentChunkEntity(
            fileUri = "file:///storage/emulated/0/Documents/AI_Overview.pdf",
            fileName = "AI_Overview.pdf",
            chunkIndex = 0,
            chunkText = "TensorFlow Lite enables low-latency on-device machine learning inference on Android.",
            hash = "hash_fts_chunk_1",
            timestamp = System.currentTimeMillis(),
            embedding = FloatArray(384) { 0.1f },
            tags = "Machine Learning, Android, TFLite"
        )

        val chunk2 = DocumentChunkEntity(
            fileUri = "file:///storage/emulated/0/Documents/SQLite_Optimization.txt",
            fileName = "SQLite_Optimization.txt",
            chunkIndex = 0,
            chunkText = "SQLite FTS4 virtual tables use unicode61 tokenization for fast full-text searching.",
            hash = "hash_fts_chunk_2",
            timestamp = System.currentTimeMillis(),
            embedding = FloatArray(384) { 0.2f },
            tags = "Database, SQLite, FTS"
        )

        // Insert chunks together with FTS virtual table entries
        dao.insertChunksWithFts(listOf(chunk1, chunk2))

        // Verify FTS MATCH queries for "TensorFlow"
        val tfliteResults = dao.searchFts("TensorFlow*")
        assertEquals(1, tfliteResults.size)
        assertEquals("AI_Overview.pdf", tfliteResults[0].fileName)
        assertTrue(tfliteResults[0].chunkText.contains("TensorFlow Lite"))

        // Verify FTS MATCH queries for "unicode61"
        val sqliteResults = dao.searchFts("unicode61*")
        assertEquals(1, sqliteResults.size)
        assertEquals("SQLite_Optimization.txt", sqliteResults[0].fileName)

        // Verify FTS MATCH query for multi-word phrase OR
        val multiResults = dao.searchFts("Android* OR SQLite*")
        assertEquals(2, multiResults.size)
    }

    @Test
    fun testTensorFlowLiteEmbeddingQuantizationAndFootprintReduction() = runBlocking {
        val engine = OnDeviceEmbeddingEngine(context)
        val originalFloats = engine.embedText("Quantized BERT document vector representation for local storage optimization.")

        assertEquals(384, originalFloats.size)

        // 1. Quantize using TensorFlow Lite INT8 quantization
        val quantized = TensorFlowLiteQuantizer.quantize(originalFloats)
        assertEquals(384, quantized.quantizedBytes.size)

        // 2. Dequantize and check fidelity
        val dequantized = TensorFlowLiteQuantizer.dequantize(quantized)
        assertEquals(384, dequantized.size)

        val cosineSim = VectorSimilarityUtils.calculateCosineSimilarity(originalFloats, dequantized)
        assertTrue("Cosine similarity between original and TFLite quantized vector should be > 0.99 (got $cosineSim)", cosineSim > 0.99f)

        // 3. Verify SQLite BLOB serialization and compression ratio
        val unquantizedBlob = VectorSimilarityUtils.floatArrayToByteArrayUnquantized(originalFloats)
        val tfliteQuantizedBlob = TensorFlowLiteQuantizer.compressToQuantizedBlob(originalFloats)

        // Unquantized 384 floats = 384 * 4 = 1536 bytes
        assertEquals(1536, unquantizedBlob.size)
        // Quantized BLOB = 17 header bytes + 384 bytes = 401 bytes
        assertEquals(401, tfliteQuantizedBlob.size)

        val reduction = TensorFlowLiteQuantizer.calculateStorageFootprintReduction(384)
        assertTrue("Expected > 70% storage footprint reduction (got $reduction%)", reduction > 70.0f)

        // 4. Verify roundtrip through VectorSimilarityUtils
        val restoredFromBlob = VectorSimilarityUtils.byteArrayToFloatArray(tfliteQuantizedBlob)
        assertEquals(384, restoredFromBlob.size)

        val restoredSim = VectorSimilarityUtils.calculateCosineSimilarity(originalFloats, restoredFromBlob)
        assertTrue("Restored vector from TFLite quantized BLOB should preserve similarity (got $restoredSim)", restoredSim > 0.99f)

        engine.close()
    }
}

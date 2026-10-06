package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentChunkEntity
import com.example.data.local.DocumentChunkFtsEntity
import com.example.engine.HybridSearchEngine
import com.example.engine.OnDeviceEmbeddingEngine
import com.example.engine.SearchMode
import com.example.engine.pdf.PdfTextExtractor
import com.example.service.TextEmbeddingService
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
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfFtsAndEmbeddingServiceTest {

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
    fun testPdfTextExtractionAndRoomFtsIndexing() = runBlocking {
        val dao = database.documentChunkDao()

        // 1. Synthetic PDF document with multiple sections
        val syntheticPdf = """
            %PDF-1.4
            1 0 obj
            << /Type /Catalog /Pages 2 0 R >>
            endobj
            2 0 obj
            << /Type /Pages /Kids [3 0 R] /Count 1 >>
            endobj
            3 0 obj
            << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>
            endobj
            4 0 obj
            << /Length 180 >>
            stream
            BT
            /F1 12 Tf
            50 750 Td
            (Autonomous Machine Learning Architecture in Embedded Android Systems) Tj
            T*
            (This technical whitepaper details quantized BERT embeddings and SQLite FTS indexing.) Tj
            T*
            (Zero cloud latency ensures complete user privacy and fast retrieval.) Tj
            ET
            endstream
            endobj
            xref
            0 5
            0000000000 65535 f 
            0000000009 00000 n 
            0000000058 00000 n 
            0000000115 00000 n 
            0000000204 00000 n 
            trailer
            << /Size 5 /Root 1 0 R >>
            startxref
            430
            %%EOF
        """.trimIndent()

        val extractionResult = PdfTextExtractor.extractFromBytes(syntheticPdf.toByteArray(Charsets.ISO_8859_1))
        assertTrue(extractionResult.isSuccess)
        assertTrue(extractionResult.fullText.contains("Autonomous Machine Learning Architecture"))
        assertTrue(extractionResult.fullText.contains("quantized BERT embeddings"))

        // 2. Chunk and insert into Room FTS
        val embeddingEngine = OnDeviceEmbeddingEngine(context)
        val chunk1Text = "Autonomous Machine Learning Architecture in Embedded Android Systems."
        val chunk2Text = "This technical whitepaper details quantized BERT embeddings and SQLite FTS indexing."

        val vec1 = embeddingEngine.embedText(chunk1Text)
        val vec2 = embeddingEngine.embedText(chunk2Text)

        val chunkEntity1 = DocumentChunkEntity(
            fileUri = "file:///storage/docs/embedded_ml.pdf",
            fileName = "embedded_ml.pdf",
            chunkIndex = 0,
            chunkText = chunk1Text,
            hash = "hash_pdf_chunk_1",
            timestamp = System.currentTimeMillis(),
            embeddingBlob = embeddingEngine.floatArrayToByteArray(vec1),
            tags = "PDF, ML, Embeddings"
        )

        val chunkEntity2 = DocumentChunkEntity(
            fileUri = "file:///storage/docs/embedded_ml.pdf",
            fileName = "embedded_ml.pdf",
            chunkIndex = 1,
            chunkText = chunk2Text,
            hash = "hash_pdf_chunk_2",
            timestamp = System.currentTimeMillis(),
            embeddingBlob = embeddingEngine.floatArrayToByteArray(vec2),
            tags = "PDF, Whitepaper"
        )

        dao.insertChunksWithFts(listOf(chunkEntity1, chunkEntity2))

        // 3. Test Room FTS MATCH queries
        val ftsMatches = dao.searchFts("quantized*")
        assertEquals(1, ftsMatches.size)
        assertEquals("embedded_ml.pdf", ftsMatches[0].fileName)
        assertTrue(ftsMatches[0].chunkText.contains("quantized BERT embeddings"))

        val ftsMultiMatch = dao.searchFts("Android* OR Architecture*")
        assertEquals(1, ftsMultiMatch.size)
        assertEquals(0, ftsMultiMatch[0].chunkIndex)

        // 4. Test Hybrid Search Fusion on PDF Chunks
        val hybridSearchEngine = HybridSearchEngine(dao, embeddingEngine)
        val searchResults = hybridSearchEngine.search("quantized BERT", SearchMode.HYBRID)
        assertTrue(searchResults.isNotEmpty())
        assertEquals("embedded_ml.pdf", searchResults[0].fileName)
        assertNotNull(searchResults[0].vectorRank)
    }

    @Test
    fun testTextEmbeddingServiceLifecycleAndBatchEmbeddings() = runBlocking {
        val service = TextEmbeddingService.getInstance(context)
        assertNotNull(service)

        // Test single text embedding generation
        val singleVec = service.generateEmbedding("Quantized BERT transformer for local vector retrieval")
        assertEquals(384, singleVec.size)
        var sumSq = 0.0
        for (v in singleVec) sumSq += (v * v).toDouble()
        val norm = Math.sqrt(sumSq)
        assertTrue("Single vector should be normalized to ~1.0", Math.abs(norm - 1.0) < 1e-4)

        // Test batch embedding generation
        val sampleBatch = listOf(
            "SQLite FTS4 full text search index",
            "Dense vector embeddings with INT8 quantization",
            "PDF document parser with CMap decoding",
            "Reciprocal rank fusion combining sparse and dense scores"
        )

        val batchVectors = service.generateEmbeddingsBatch(sampleBatch)
        assertEquals(4, batchVectors.size)
        batchVectors.forEach { vec ->
            assertEquals(384, vec.size)
            var vecSumSq = 0.0
            for (v in vec) vecSumSq += (v * v).toDouble()
            val vecNorm = Math.sqrt(vecSumSq)
            assertTrue("Batch vector should be L2 normalized", Math.abs(vecNorm - 1.0) < 1e-4)
        }
    }
}

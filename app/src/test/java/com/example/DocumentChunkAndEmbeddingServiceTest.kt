package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.Converters
import com.example.data.local.DocumentChunkEntity
import com.example.engine.VectorSimilarityUtils
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentChunkAndEmbeddingServiceTest {

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
    fun testDocumentChunkEntityFieldsAndTypeConverters() = runBlocking {
        val dao = database.documentChunkDao()

        val sampleEmbedding = FloatArray(384) { (it % 20) * 0.05f }
        val normalizedEmbedding = VectorSimilarityUtils.l2Normalize(sampleEmbedding)

        // Test creation with metadata, raw text, and FloatArray
        val chunk = DocumentChunkEntity(
            fileUri = "file:///storage/emulated/0/Documents/contract.pdf",
            fileName = "contract.pdf",
            chunkIndex = 3,
            chunkText = "This Agreement is entered into by and between the parties for offline search engine development.",
            hash = "sha256_contract_chunk_3",
            timestamp = 1700000000000L,
            embedding = normalizedEmbedding,
            tags = "Contract, Legal, Confidential",
            metadata = "{\"page\": 4, \"author\": \"Legal Dept\", \"mimeType\": \"application/pdf\"}"
        )

        // Verify entity properties
        assertEquals("This Agreement is entered into by and between the parties for offline search engine development.", chunk.content)
        assertEquals("This Agreement is entered into by and between the parties for offline search engine development.", chunk.chunkText)
        assertEquals("contract.pdf", chunk.fileName)
        assertEquals("file:///storage/emulated/0/Documents/contract.pdf", chunk.fileUri)
        assertEquals(3, chunk.chunkIndex)
        assertEquals("sha256_contract_chunk_3", chunk.hash)
        assertEquals(1700000000000L, chunk.timestamp)
        assertEquals("Contract, Legal, Confidential", chunk.tags)
        assertTrue(chunk.metadata.contains("Legal Dept"))

        // Verify FloatArray embedding accessor
        assertEquals(384, chunk.embedding.size)
        assertEquals(normalizedEmbedding[0], chunk.embedding[0], 1e-5f)

        // Insert into Room database
        val insertedId = dao.insertChunk(chunk)
        assertTrue(insertedId > 0)

        // Retrieve from Room database
        val retrieved = dao.getChunkByHash("sha256_contract_chunk_3")
        assertNotNull(retrieved)
        assertEquals(chunk.chunkText, retrieved?.chunkText)
        assertEquals(chunk.fileName, retrieved?.fileName)
        assertEquals(chunk.fileUri, retrieved?.fileUri)
        assertEquals(chunk.tags, retrieved?.tags)
        assertEquals(chunk.metadata, retrieved?.metadata)

        // Verify deserialized FloatArray from Room BLOB
        val retrievedEmbedding = retrieved?.embedding
        assertNotNull(retrievedEmbedding)
        assertEquals(384, retrievedEmbedding!!.size)
        for (i in 0 until 384) {
            assertEquals(normalizedEmbedding[i], retrievedEmbedding[i], 0.02f)
        }

        // Test Converters class directly
        val converters = Converters()
        val blob = converters.fromFloatArray(normalizedEmbedding)
        assertNotNull(blob)
        assertEquals(401, blob!!.size)

        val restoredFloats = converters.toFloatArray(blob)
        assertNotNull(restoredFloats)
        assertEquals(384, restoredFloats!!.size)
    }

    @Test
    fun testTextEmbeddingServiceLoadsModelAndGeneratesEmbeddings() = runBlocking {
        val service = TextEmbeddingService.getInstance(context)
        assertNotNull(service)

        // Verify loading function
        service.loadQuantizedBertModel()

        // Generate vector embedding for a string of text
        val testText = "Quantized BERT transformer for offline neural document search and semantic retrieval."
        val embedding = service.generateEmbedding(text = testText)

        assertNotNull(embedding)
        assertEquals(384, embedding.size)

        // Check L2 normalization: sum of squares ≈ 1.0
        var sumSquares = 0.0
        for (v in embedding) {
            sumSquares += (v * v).toDouble()
        }
        val norm = Math.sqrt(sumSquares)
        assertEquals(1.0, norm, 1e-3)

        // Verify generateVectorEmbedding alias
        val aliasEmbedding = service.generateVectorEmbedding(testText)
        assertEquals(384, aliasEmbedding.size)
        assertEquals(embedding[0], aliasEmbedding[0], 1e-6f)

        // Test batch embedding function
        val batchTexts = listOf(
            "First document chunk with legal clauses",
            "Second document chunk with financial summaries",
            "Third document chunk with technical documentation"
        )
        val batchResults = service.generateEmbeddingsBatch(batchTexts)
        assertEquals(3, batchResults.size)
        for (vec in batchResults) {
            assertEquals(384, vec.size)
        }
    }
}

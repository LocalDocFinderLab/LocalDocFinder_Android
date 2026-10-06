package com.example

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.DocumentRepository
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentIndexWorkerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: DocumentRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.getInstance(context)
        repository = DocumentRepository(context)
        runBlocking {
            database.documentChunkDao().clearAll()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            database.documentChunkDao().clearAll()
        }
    }

    @Test
    fun testWorkerIndexingPipelineExtractsTextAndStoresQuantizedEmbeddings() {
        runBlocking {
            // Create sample document file
            val tempDir = File(context.cacheDir, "worker_test_docs").apply { mkdirs() }
            val testFile = File(tempDir, "sample_ai_architecture.md").apply {
                writeText("""
                    # TensorFlow Lite Quantized Embeddings
                    
                    WorkManager background workers process local documents, extract text, and use
                    a TFLite model to generate quantized embeddings for storage in SQLite Room.
                """.trimIndent())
            }

            val docFile = DocumentFile.fromFile(testFile)

            // Run indexing pipeline as executed inside DocumentIndexWorker
            val indexResult = repository.indexDocumentSafely(docFile)

            assertTrue("Indexing should succeed", indexResult.isSuccess)
            assertTrue("Indexed chunks count should be > 0", indexResult.chunksIndexed > 0)

            val dao = database.documentChunkDao()
            val storedChunks = dao.getAllChunks()
            assertTrue("Room database should contain stored chunks", storedChunks.isNotEmpty())

            for (chunk in storedChunks) {
                assertTrue("Chunk text should be non-empty", chunk.chunkText.isNotBlank())
                assertTrue("File URI should be non-empty", chunk.fileUri.isNotBlank())
                assertNotNull("Embedding BLOB should not be null", chunk.embeddingBlob)

                // Verify the stored embedding is a TFLite INT8 quantized BLOB
                assertTrue(
                    "Stored BLOB should be a valid TensorFlow Lite INT8 quantized BLOB",
                    TensorFlowLiteQuantizer.isQuantizedBlob(chunk.embeddingBlob)
                )

                // Quantized BLOB size for 384 dimensions: 17 header bytes + 384 byte values = 401 bytes
                assertEquals(401, chunk.embeddingBlob.size)

                // Verify dequantization restores 384-dimensional vector
                val restoredVector = VectorSimilarityUtils.byteArrayToFloatArray(chunk.embeddingBlob)
                assertEquals(384, restoredVector.size)

                // Verify L2 normalization magnitude ≈ 1.0
                val norm = VectorSimilarityUtils.l2Norm(restoredVector)
                assertEquals(1.0f, norm, 0.05f)
            }

            tempDir.deleteRecursively()
        }
    }
}

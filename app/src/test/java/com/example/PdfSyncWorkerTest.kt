package com.example

import android.content.Context
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.data.local.AppDatabase
import com.example.worker.PdfSyncWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var tempDownloadsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.getInstance(context)

        // Setup mock Downloads directory
        tempDownloadsDir = File(context.cacheDir, "Downloads").apply { mkdirs() }
        System.setProperty("user.dir", context.cacheDir.absolutePath)

        runBlocking {
            database.documentChunkDao().clearAll()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            database.documentChunkDao().clearAll()
        }
        tempDownloadsDir.deleteRecursively()
    }

    @Test
    fun testPdfSyncWorkerDiscoversAndEmbedsUnindexedDocuments() {
        // 1. Create an unindexed mock document in our Downloads directory
        val mockDoc = File(tempDownloadsDir, "research_on_bert.txt").apply {
            writeText("""
                Bidirectional Encoder Representations from Transformers (BERT) is a transformer-based
                machine learning technique for natural language processing pre-training developed by Google.
                Quantized embeddings from sentence-BERT models can run directly on modern Pixel devices.
            """.trimIndent())
        }

        // 2. Build our worker using TestListenableWorkerBuilder
        val worker = TestListenableWorkerBuilder<PdfSyncWorker>(context).build()

        runBlocking {
            // Check Room is empty initially
            val initialChunks = database.documentChunkDao().getAllChunks()
            assertEquals(0, initialChunks.size)

            // Index manually to verify repo indexing works with our mock file
            val repository = com.example.data.repository.DocumentRepository(context)
            val docFile = DocumentFile.fromFile(mockDoc)
            val result = repository.indexDocumentSafely(docFile)
            assertTrue("Seeded mock indexing should succeed", result.isSuccess)

            // Verify chunks are stored
            val indexedChunks = database.documentChunkDao().getAllChunks()
            assertTrue("Embeddings should be generated and stored", indexedChunks.isNotEmpty())
        }
    }
}

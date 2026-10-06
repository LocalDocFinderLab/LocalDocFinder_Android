package com.example

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.DocumentRepository
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
class IncrementalIndexingTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: DocumentRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.getInstance(context)
        repository = DocumentRepository(context)
        tempDir = File(context.cacheDir, "incremental_docs").apply { mkdirs() }
        runBlocking { database.documentChunkDao().clearAll() }
    }

    @After
    fun tearDown() {
        runBlocking { database.documentChunkDao().clearAll() }
        tempDir.deleteRecursively()
    }

    @Test
    fun unchangedFileIsSkippedOnRescan() = runBlocking {
        val file = File(tempDir, "notes.txt").apply { writeText("Quantized embeddings keep the index small and fast.") }
        val dao = database.documentChunkDao()

        val first = repository.indexDocumentSafely(DocumentFile.fromFile(file))
        assertTrue(first.isSuccess)
        val idsAfterFirst = dao.getAllChunks().map { it.id }.sorted()

        val second = repository.indexDocumentSafely(DocumentFile.fromFile(file))

        assertTrue(second.isSuccess)
        assertEquals(first.chunksIndexed, second.chunksIndexed)
        // Skipped files are not deleted and re-inserted, so the stored row ids stay the same.
        assertEquals(idsAfterFirst, dao.getAllChunks().map { it.id }.sorted())
    }

    @Test
    fun modifiedFileIsReindexed() = runBlocking {
        val file = File(tempDir, "changing.txt").apply { writeText("Original content about vector search.") }
        val dao = database.documentChunkDao()
        repository.indexDocumentSafely(DocumentFile.fromFile(file))

        file.writeText("Completely rewritten content about notification progress bars.")
        file.setLastModified(file.lastModified() + 10_000L)
        repository.indexDocumentSafely(DocumentFile.fromFile(file))

        val texts = dao.getAllChunks().joinToString(" ") { it.chunkText }
        assertTrue(texts.contains("notification progress bars"))
        assertTrue(!texts.contains("Original content"))
    }

    @Test
    fun indexedFileStampsReportOneRowPerFileWithoutLoadingEmbeddings() = runBlocking {
        val a = File(tempDir, "a.txt").apply { writeText("First document text.") }
        val b = File(tempDir, "b.txt").apply { writeText("Second document text.") }
        repository.indexDocumentSafely(DocumentFile.fromFile(a))
        repository.indexDocumentSafely(DocumentFile.fromFile(b))

        val stamps = database.documentChunkDao().getIndexedFileStamps()

        assertEquals(2, stamps.size)
        assertTrue(stamps.all { it.lastIndexed > 0L })
    }
}

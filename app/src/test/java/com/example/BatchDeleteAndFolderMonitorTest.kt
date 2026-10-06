package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentChunkEntity
import com.example.data.local.DocumentTagEntity
import com.example.data.repository.DocumentRepository
import com.example.worker.FolderMonitorWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class BatchDeleteAndFolderMonitorTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: DocumentRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = AppDatabase.getInstance(context)
        repository = DocumentRepository(context)
    }

    @After
    fun tearDown() {
        runBlocking {
            database.documentChunkDao().clearAll()
        }
    }

    @Test
    fun testBatchDeleteMultipleDocuments(): Unit = runBlocking {
        val dao = database.documentChunkDao()
        dao.clearAll()

        // Create 3 documents with multiple chunks and tags
        val doc1Uri = "file:///storage/docs/doc1.txt"
        val doc2Uri = "file:///storage/docs/doc2.pdf"
        val doc3Uri = "file:///storage/docs/doc3.md"

        val chunks = listOf(
            DocumentChunkEntity(
                fileUri = doc1Uri,
                fileName = "doc1.txt",
                chunkIndex = 0,
                chunkText = "Doc 1 text chunk A",
                hash = "hash1_a",
                timestamp = 1000L,
                embeddingBlob = ByteArray(384) { 0 },
                tags = "Research, AI"
            ),
            DocumentChunkEntity(
                fileUri = doc1Uri,
                fileName = "doc1.txt",
                chunkIndex = 1,
                chunkText = "Doc 1 text chunk B",
                hash = "hash1_b",
                timestamp = 1000L,
                embeddingBlob = ByteArray(384) { 0 },
                tags = "Research, AI"
            ),
            DocumentChunkEntity(
                fileUri = doc2Uri,
                fileName = "doc2.pdf",
                chunkIndex = 0,
                chunkText = "Doc 2 text chunk A",
                hash = "hash2_a",
                timestamp = 2000L,
                embeddingBlob = ByteArray(384) { 0 },
                tags = "Finance"
            ),
            DocumentChunkEntity(
                fileUri = doc3Uri,
                fileName = "doc3.md",
                chunkIndex = 0,
                chunkText = "Doc 3 text chunk A",
                hash = "hash3_a",
                timestamp = 3000L,
                embeddingBlob = ByteArray(384) { 0 },
                tags = "Notes"
            )
        )

        dao.insertChunksWithFts(chunks)
        dao.insertTags(listOf(
            DocumentTagEntity(fileUri = doc1Uri, tag = "Research"),
            DocumentTagEntity(fileUri = doc1Uri, tag = "AI"),
            DocumentTagEntity(fileUri = doc2Uri, tag = "Finance"),
            DocumentTagEntity(fileUri = doc3Uri, tag = "Notes")
        ))

        // Verify initial state: 4 chunks, 3 files
        assertEquals(4, dao.getTotalChunksCountDirect())
        assertEquals(3, dao.getIndexedFilesDirect().size)
        assertEquals(2, dao.getTagsForFile(doc1Uri).size)
        assertEquals(1, dao.getTagsForFile(doc2Uri).size)
        assertEquals(1, dao.getTagsForFile(doc3Uri).size)

        // Perform batch deletion of doc1 and doc2 in a single batch operation
        dao.deleteFileRecords(listOf(doc1Uri, doc2Uri))

        // Verify batch deletion result:
        // doc1 and doc2 chunks must be deleted
        val remainingChunks = dao.getAllChunks()
        assertEquals(1, remainingChunks.size)
        assertEquals(doc3Uri, remainingChunks[0].fileUri)
        assertEquals("doc3.md", remainingChunks[0].fileName)

        // Tags for doc1 and doc2 must be purged
        assertTrue(dao.getTagsForFile(doc1Uri).isEmpty())
        assertTrue(dao.getTagsForFile(doc2Uri).isEmpty())
        assertEquals(listOf("Notes"), dao.getTagsForFile(doc3Uri))

        // FTS entries for doc1 and doc2 must be purged
        val ftsChunkIds = dao.searchFtsChunkIds("chunk")
        assertEquals(1, ftsChunkIds.size)
        assertEquals(remainingChunks[0].id, ftsChunkIds[0])

        // Total files must now be 1
        val remainingFiles = dao.getIndexedFilesDirect()
        assertEquals(1, remainingFiles.size)
        assertEquals(doc3Uri, remainingFiles[0])
    }

    @Test
    fun testRepositoryDeleteDocumentsByUris(): Unit = runBlocking {
        val dao = database.documentChunkDao()
        dao.clearAll()

        val docAUri = "file:///storage/docA.txt"
        val docBUri = "file:///storage/docB.txt"

        val chunks = listOf(
            DocumentChunkEntity(
                fileUri = docAUri,
                fileName = "docA.txt",
                chunkIndex = 0,
                chunkText = "Doc A content",
                hash = "hash_a",
                timestamp = 100L,
                embeddingBlob = ByteArray(384) { 0 }
            ),
            DocumentChunkEntity(
                fileUri = docBUri,
                fileName = "docB.txt",
                chunkIndex = 0,
                chunkText = "Doc B content",
                hash = "hash_b",
                timestamp = 200L,
                embeddingBlob = ByteArray(384) { 0 }
            )
        )
        dao.insertChunksWithFts(chunks)
        assertEquals(2, dao.getTotalChunksCountDirect())

        repository.deleteDocumentsByUris(listOf(docAUri, docBUri))
        assertEquals(0, dao.getTotalChunksCountDirect())
        assertTrue(dao.getIndexedFilesDirect().isEmpty())
    }

    @Test
    fun testFolderMonitorDesignatedFolderCreation() {
        val folder = FolderMonitorWorker.getDesignatedFolder(context)
        assertNotNull(folder)
        assertTrue(folder.exists())
        assertTrue(folder.isDirectory)
        assertEquals("monitored_documents", folder.name)

        // Test creating a test document inside the designated folder
        val testDoc = FolderMonitorWorker.createTestDocumentInMonitoredFolder(
            context,
            title = "Autonomous_Sensor_Log",
            content = "Log entry: offline vector pipeline operating at 100% capacity."
        )

        assertTrue(testDoc.exists())
        assertTrue(testDoc.length() > 0)
        assertTrue(testDoc.name.startsWith("Autonomous_Sensor_Log"))
        testDoc.delete()
    }

    @Test
    fun testFolderMonitorDetectionAndIndexing(): Unit = runBlocking {
        val dao = database.documentChunkDao()
        dao.clearAll()

        // Create temporary monitored folder
        val testMonitoredDir = File(context.cacheDir, "test_designated_monitored_folder").apply { mkdirs() }
        FolderMonitorWorker.setDesignatedFolder(context, testMonitoredDir)

        val file1 = File(testMonitoredDir, "network_topology.txt").apply {
            writeText("Hierarchical token bucket traffic shaping and packet filtering algorithms.")
        }
        val file2 = File(testMonitoredDir, "tensor_benchmarks.md").apply {
            writeText("INT8 quantization delegates achieve 3.8ms latency per 384-dim vector embedding.")
        }

        // Before indexing: database is empty
        assertEquals(0, dao.getTotalChunksCountDirect())

        // Index the files via repository (as FolderMonitorWorker does)
        val docFile1 = androidx.documentfile.provider.DocumentFile.fromFile(file1)
        val docFile2 = androidx.documentfile.provider.DocumentFile.fromFile(file2)

        val res1 = repository.indexDocumentSafely(docFile1)
        val res2 = repository.indexDocumentSafely(docFile2)

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertTrue(dao.getTotalChunksCountDirect() >= 2)

        val indexedUris = dao.getIndexedFilesDirect()
        assertTrue(indexedUris.contains(docFile1.uri.toString()))
        assertTrue(indexedUris.contains(docFile2.uri.toString()))

        // Simulate folder monitor detecting only NEW files:
        val file3 = File(testMonitoredDir, "newly_added_memo.txt").apply {
            writeText("New confidential memorandum added to monitored directory.")
        }
        val docFile3 = androidx.documentfile.provider.DocumentFile.fromFile(file3)

        val allCandidateFiles = listOf(docFile1, docFile2, docFile3)
        val newDetected = allCandidateFiles.filter { it.uri.toString() !in dao.getIndexedFilesDirect() }

        assertEquals(1, newDetected.size)
        assertEquals(docFile3.uri.toString(), newDetected[0].uri.toString())

        // Clean up
        testMonitoredDir.deleteRecursively()
    }
}

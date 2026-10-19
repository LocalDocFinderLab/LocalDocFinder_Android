package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentChunkEntity
import com.example.engine.DuplicateFinder
import com.example.engine.HybridSearchEngine
import com.example.engine.OnDeviceEmbeddingEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimilarDocumentsTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var embedding: OnDeviceEmbeddingEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        embedding = OnDeviceEmbeddingEngine(context)
    }

    @After
    fun tearDown() = database.close()

    private fun chunk(file: String, index: Int, text: String) = DocumentChunkEntity(
        fileUri = "file:///docs/$file",
        fileName = file,
        chunkIndex = index,
        chunkText = text,
        hash = "$file:$index",
        timestamp = 1L,
        embeddingBlob = embedding.floatArrayToByteArray(runBlocking { embedding.embedText(text) }),
        metadata = "model=builtin"
    )

    @Test
    fun similarDocumentsExcludeTheSourceFileAndListEachFileOnce() = runBlocking {
        val dao = database.documentChunkDao()
        val tax = "income tax return deduction filing refund"
        dao.insertChunksWithFts(
            listOf(
                chunk("tax.pdf", 0, tax),
                chunk("tax.pdf", 1, "$tax again"),
                chunk("tax_copy.pdf", 0, tax),
                chunk("tax_copy.pdf", 1, "$tax extra"),
                chunk("recipe.txt", 0, "pasta tomato basil garlic olive oil simmer")
            )
        )
        val sourceId = dao.getChunksForFile("file:///docs/tax.pdf").first().id

        val similar = HybridSearchEngine(dao, embedding).findSimilar(sourceId, limit = 5)

        assertTrue(similar.none { it.fileName == "tax.pdf" })
        assertEquals(similar.size, similar.map { it.fileUri }.distinct().size)
        assertEquals("tax_copy.pdf", similar.first().fileName)
    }

    @Test
    fun chunksWithoutAModelTagHaveNoSimilarDocuments() = runBlocking {
        val dao = database.documentChunkDao()
        dao.insertChunksWithFts(listOf(chunk("a.txt", 0, "hello world").copy(metadata = "")))
        val id = dao.getChunksForFile("file:///docs/a.txt").first().id
        assertTrue(HybridSearchEngine(dao, embedding).findSimilar(id).isEmpty())
    }

    @Test
    fun identicalFilesAreReportedAsDuplicatesFromTheStoredVectors() = runBlocking {
        val dao = database.documentChunkDao()
        dao.insertChunksWithFts(
            listOf(
                chunk("a.txt", 0, "same words in the first part"),
                chunk("a.txt", 1, "and the second part"),
                chunk("b.txt", 0, "same words in the first part"),
                chunk("b.txt", 1, "and the second part"),
                chunk("c.txt", 0, "totally unrelated content")
            )
        )
        val prints = dao.getFingerprintPage(Long.MIN_VALUE, 100).map { it.fileUri to DuplicateFinder.fingerprint(it.embeddingBlob) }

        val groups = DuplicateFinder.find(prints)

        assertEquals(1, groups.size)
        assertTrue(groups[0].exact)
        assertEquals(listOf("file:///docs/a.txt", "file:///docs/b.txt"), groups[0].fileUris)
    }

    @Test
    fun suggestionQueriesMatchFileNamesAndPastSearches() = runBlocking {
        val dao = database.documentChunkDao()
        dao.insertChunksWithFts(listOf(chunk("Tax_Return_2024.pdf", 0, "forms"), chunk("recipe.txt", 0, "soup")))
        // `_` in LIKE stands in for the space the user typed.
        assertEquals(listOf("Tax_Return_2024.pdf"), dao.searchFileNames("tax_return", 10))
        assertTrue(dao.searchFileNames("zzz", 10).isEmpty())

        val history = database.searchHistoryDao()
        history.insertSearch(com.example.data.local.SearchHistoryEntity(query = "tax deadline", searchMode = "HYBRID", resultCount = 3, timestamp = 1L))
        history.insertSearch(com.example.data.local.SearchHistoryEntity(query = "soup recipe", searchMode = "HYBRID", resultCount = 1, timestamp = 2L))
        assertEquals(listOf("tax deadline"), history.searchQueries("tax", 10))
    }
}

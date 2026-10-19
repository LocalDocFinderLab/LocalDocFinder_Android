package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentChunkEntity
import com.example.engine.HybridSearchEngine
import com.example.engine.OnDeviceEmbeddingEngine
import com.example.engine.SearchMode
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
class KeywordRankingTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private fun chunk(i: Int, text: String, engine: OnDeviceEmbeddingEngine) = DocumentChunkEntity(
        fileUri = "file:///docs/$i.txt",
        fileName = "$i.txt",
        chunkIndex = 0,
        chunkText = text,
        hash = "h$i",
        timestamp = 1L,
        embeddingBlob = engine.floatArrayToByteArray(runBlocking { engine.embedText(text) })
    )

    /** The best keyword match is inserted last; it must not be lost to the FTS row limit or to row order. */
    @Test
    fun bestKeywordMatchSurvivesManyWeakMatches() = runBlocking {
        val dao = database.documentChunkDao()
        val embedding = OnDeviceEmbeddingEngine(context)
        val weak = (1..150).map { chunk(it, "note $it mentions an invoice once among " + "other words ".repeat(20), embedding) }
        val strong = chunk(151, "invoice invoice invoice total due on the invoice", embedding)
        dao.insertChunksWithFts(weak + strong)

        val results = HybridSearchEngine(dao, embedding)
            .search("invoice", SearchMode.KEYWORD, topK = 10, semanticScoring = false)

        assertTrue(results.isNotEmpty())
        assertEquals("151.txt", results.first().fileName)
        assertEquals(1, results.first().ftsRank)
    }
}

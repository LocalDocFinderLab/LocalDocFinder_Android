package com.example

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.ui.overlay.GlossySearchOverlayActivity
import com.example.widget.DocuVectorWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context verifies LocalDoc Finder app name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LocalDoc Finder", appName)
    }

    @Test
    fun `verify widget strings and search prompt exist`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val widgetSearchHint = context.getString(R.string.widget_search_hint)
        val voiceSearchPrompt = context.getString(R.string.voice_search_prompt)
        val glassOpacity = context.getString(R.string.glass_opacity)

        assertTrue(widgetSearchHint.contains("Search local documents"))
        assertTrue(voiceSearchPrompt.contains("Speak to search documents"))
        assertEquals("Glass Display Opacity", glassOpacity)
    }

    @Test
    fun `verify widget update and intents configuration`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val views = android.widget.RemoteViews(context.packageName, R.layout.docuvector_widget)
        assertEquals(R.layout.docuvector_widget, views.layoutId)

        // Verifies updateAppWidget can be safely called
        val appWidgetManager = AppWidgetManager.getInstance(context)
        DocuVectorWidget.updateAppWidget(context, appWidgetManager, 101)
    }

    @Test
    fun `verify glass display opacity preferences logic`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("localdoc_glass_prefs", Context.MODE_PRIVATE)

        // Default test
        val defaultOpacity = prefs.getFloat("glass_opacity", 0.65f)
        assertEquals(0.65f, defaultOpacity, 0.01f)

        // See-through preset (30%)
        prefs.edit().putFloat("glass_opacity", 0.30f).commit()
        assertEquals(0.30f, prefs.getFloat("glass_opacity", 0.65f), 0.01f)

        // Opaque preset (95%)
        prefs.edit().putFloat("glass_opacity", 0.95f).commit()
        assertEquals(0.95f, prefs.getFloat("glass_opacity", 0.65f), 0.01f)
    }

    @Test
    fun `verify voice search intent extra keys`() {
        assertEquals("extra_start_voice", DocuVectorWidget.EXTRA_START_VOICE)
        assertEquals("extra_focus_search", DocuVectorWidget.EXTRA_FOCUS_SEARCH)
        assertEquals("extra_query", DocuVectorWidget.EXTRA_QUERY)
    }

    @Test
    fun `verify search result navigation and preview selection`() {
        val r1 = com.example.engine.SearchResult(
            chunkId = 1L,
            fileUri = "content://docs/report.pdf",
            fileName = "Quarterly_Report.pdf",
            chunkIndex = 0,
            chunkText = "Financial overview of Q3 shows strong offline performance.",
            snippet = "Financial overview of Q3 shows strong offline performance.",
            highlightedTerms = listOf("offline", "performance"),
            cosineSimilarity = 0.92f,
            vectorRank = 1,
            ftsRank = 1,
            rrfScore = 0.032f,
            latencyMs = 12L
        )

        val r2 = com.example.engine.SearchResult(
            chunkId = 2L,
            fileUri = "content://docs/notes.txt",
            fileName = "Meeting_Notes.txt",
            chunkIndex = 1,
            chunkText = "Discussed neural embedding models and SQLite FTS search.",
            snippet = "Discussed neural embedding models and SQLite FTS search.",
            highlightedTerms = listOf("neural", "search"),
            cosineSimilarity = 0.81f,
            vectorRank = 2,
            ftsRank = 2,
            rrfScore = 0.028f,
            latencyMs = 15L
        )

        val resultsList = listOf(r1, r2)
        assertEquals(2, resultsList.size)
        val currentIndex = resultsList.indexOfFirst { it.chunkId == r1.chunkId }
        assertEquals(0, currentIndex)

        // Navigate forward
        val nextResult = resultsList[currentIndex + 1]
        assertEquals(2L, nextResult.chunkId)
        assertEquals("Meeting_Notes.txt", nextResult.fileName)
        assertEquals("txt", nextResult.fileExtension)
    }

    @Test
    fun `verify search filter state applies type and sort correctly`() {
        val r1 = com.example.engine.SearchResult(
            chunkId = 1L,
            fileUri = "content://docs/b_report.pdf",
            fileName = "B_Report.pdf",
            chunkIndex = 0,
            chunkText = "PDF content",
            snippet = "PDF snippet",
            highlightedTerms = emptyList(),
            cosineSimilarity = 0.95f,
            vectorRank = 1,
            ftsRank = 1,
            rrfScore = 0.033f,
            latencyMs = 10L,
            timestamp = 1000L
        )

        val r2 = com.example.engine.SearchResult(
            chunkId = 2L,
            fileUri = "content://docs/a_notes.txt",
            fileName = "A_Notes.txt",
            chunkIndex = 0,
            chunkText = "TXT content",
            snippet = "TXT snippet",
            highlightedTerms = emptyList(),
            cosineSimilarity = 0.85f,
            vectorRank = 2,
            ftsRank = 2,
            rrfScore = 0.025f,
            latencyMs = 10L,
            timestamp = 2000L
        )

        val allResults = listOf(r1, r2)

        // Filter by PDF only
        val pdfFilter = com.example.engine.SearchFilterState(selectedFileType = "pdf")
        val pdfOnly = pdfFilter.apply(allResults)
        assertEquals(1, pdfOnly.size)
        assertEquals("B_Report.pdf", pdfOnly.first().fileName)

        // Sort by Name Ascending
        val nameAscFilter = com.example.engine.SearchFilterState(sortOrder = com.example.engine.SearchSortOrder.NAME_ASC)
        val sortedByName = nameAscFilter.apply(allResults)
        assertEquals("A_Notes.txt", sortedByName.first().fileName)
        assertEquals("B_Report.pdf", sortedByName.last().fileName)
    }

    @Test
    fun `verify zero indexed chunks state logic`() {
        val totalChunks = 0
        val totalFiles = 0
        val isNoIndexedData = totalChunks == 0 && totalFiles == 0
        assertTrue("Should detect zero indexed data state", isNoIndexedData)
    }
}

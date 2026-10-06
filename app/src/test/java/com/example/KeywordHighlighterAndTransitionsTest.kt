package com.example

import androidx.compose.ui.graphics.Color
import com.example.engine.KeywordHighlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordHighlighterAndTransitionsTest {

    @Test
    fun testExtractKeywords_CombinesExplicitAndQueryTerms() {
        val explicit = listOf("vector", "search")
        val query = "offline vector database search"

        val extracted = KeywordHighlighter.extractKeywords(explicit, query)

        assertTrue(extracted.contains("vector"))
        assertTrue(extracted.contains("search"))
        assertTrue(extracted.contains("offline"))
        assertTrue(extracted.contains("database"))
        // Longer words come first
        assertEquals("database", extracted[0])
    }

    @Test
    fun testExtractKeywords_FiltersTrivialStopWords() {
        val query = "the contract and an agreement in the file"
        val extracted = KeywordHighlighter.extractKeywords(emptyList(), query)

        assertTrue(extracted.contains("contract"))
        assertTrue(extracted.contains("agreement"))
        assertTrue(extracted.contains("file"))
        assertTrue(!extracted.contains("the"))
        assertTrue(!extracted.contains("and"))
        assertTrue(!extracted.contains("an"))
        assertTrue(!extracted.contains("in"))
    }

    @Test
    fun testFindMatches_CaseInsensitiveAndOverlappingMerge() {
        val text = "Artificial intelligence and neural networks in intelligence systems."
        val keywords = listOf("intelligence", "artificial", "neural")

        val matches = KeywordHighlighter.findMatches(text, keywords)
        assertTrue(matches.isNotEmpty())

        // Check that "Artificial" at start was found
        assertEquals(0, matches[0].start)
        assertEquals(10, matches[0].end)

        // Check that "intelligence" at pos 11 was found
        assertEquals(11, matches[1].start)
        assertEquals(23, matches[1].end)
    }

    @Test
    fun testHighlightText_ReturnsValidFrequenciesAndSpans() {
        val docText = "Confidential business report regarding quarterly performance metrics. The business performance was strong."
        val terms = listOf("business", "performance")

        val result = KeywordHighlighter.highlightText(
            text = docText,
            keywords = terms,
            query = "business performance",
            highlightColor = Color(0xFF38BDF8)
        )

        assertEquals(4, result.totalMatchesCount)
        assertEquals(2, result.keywordFrequencies["business"])
        assertEquals(2, result.keywordFrequencies["performance"])
        assertEquals(docText, result.annotatedString.text)
        assertTrue(result.annotatedString.spanStyles.isNotEmpty())
    }

    @Test
    fun testHighlightText_SingleKeywordFilter() {
        val docText = "Confidential business report regarding quarterly performance metrics. The business performance was strong."
        val terms = listOf("business", "performance")

        val result = KeywordHighlighter.highlightText(
            text = docText,
            keywords = terms,
            query = null,
            filterKeyword = "business",
            highlightColor = Color(0xFF38BDF8)
        )

        assertEquals(2, result.totalMatchesCount)
        assertEquals(2, result.keywordFrequencies["business"])
    }

    @Test
    fun testHighlightText_BoldsMatchingSearchTerms() {
        val snippet = "Overview of the vector embeddings engine and offline search algorithms."
        val query = "vector search"

        val result = KeywordHighlighter.highlightText(
            text = snippet,
            keywords = emptyList(),
            query = query,
            highlightColor = Color(0xFF38BDF8),
            boldMatchingTerms = true
        )

        assertEquals(2, result.totalMatchesCount)
        assertEquals(snippet, result.annotatedString.text)

        // Verify that span styles are bold
        val boldSpans = result.annotatedString.spanStyles.filter {
            it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold
        }
        assertEquals(2, boldSpans.size)

        // Check matched text of the spans
        val vectorSpan = boldSpans[0]
        assertEquals("vector", snippet.substring(vectorSpan.start, vectorSpan.end))

        val searchSpan = boldSpans[1]
        assertEquals("search", snippet.substring(searchSpan.start, searchSpan.end))
    }

    @Test
    fun testBuildHighlightedSnippet_AppliesBoldStyleDirectly() {
        val snippet = "LiteRT runs on-device embeddings with SQLite FTS5 for lightning-fast search."
        val annotated = KeywordHighlighter.buildHighlightedSnippet(
            snippet = snippet,
            terms = listOf("litert", "sqlite"),
            query = "fast search"
        )

        assertEquals(snippet, annotated.text)
        val boldSpans = annotated.spanStyles.filter {
            it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold
        }
        assertTrue("Should have bold spans for matching terms", boldSpans.size >= 3)
    }

    @Test
    fun testHighlightText_EmptyKeywordsFallback() {
        val docText = "Sample document without any matches."
        val result = KeywordHighlighter.highlightText(
            text = docText,
            keywords = emptyList(),
            query = "",
            highlightColor = Color(0xFF38BDF8)
        )

        assertEquals(0, result.totalMatchesCount)
        assertEquals(docText, result.annotatedString.text)
    }
}

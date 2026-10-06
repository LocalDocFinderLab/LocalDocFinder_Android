package com.example.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

data class KeywordMatchInterval(
    val start: Int,
    val end: Int,
    val matchedTerm: String
)

data class HighlightedTextResult(
    val annotatedString: AnnotatedString,
    val totalMatchesCount: Int,
    val keywordFrequencies: Map<String, Int>
)

object KeywordHighlighter {

    private val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "as", "is", "are", "was", "were",
        "it", "this", "that"
    )

    /**
     * Extracts clean search keywords from the user's query, explicit highlighted terms,
     * or fallback text, prioritizing meaningful tokens.
     */
    fun extractKeywords(
        explicitTerms: List<String>,
        query: String? = null,
        fallbackText: String? = null
    ): List<String> {
        val tokens = mutableSetOf<String>()

        // 1. Explicit terms from search engine
        for (term in explicitTerms) {
            val clean = cleanToken(term)
            if (clean.length >= 2) {
                tokens.add(clean)
            }
        }

        // 2. Query words
        if (!query.isNullOrBlank()) {
            val queryWords = query.trim()
                .replace(Regex("""[^\w\s]"""), " ")
                .split(Regex("""\s+"""))
                .map { cleanToken(it) }
                .filter { it.length >= 2 && !STOP_WORDS.contains(it) }

            tokens.addAll(queryWords)

            // If query words were filtered out (e.g. short tokens or stop words), preserve non-blank tokens
            if (tokens.isEmpty()) {
                val fallbackWords = query.trim()
                    .replace(Regex("""[^\w\s]"""), " ")
                    .split(Regex("""\s+"""))
                    .map { cleanToken(it) }
                    .filter { it.isNotBlank() }
                tokens.addAll(fallbackWords)
            }
        }

        // 3. If still empty and fallback text has keywords, take significant words
        if (tokens.isEmpty() && !fallbackText.isNullOrBlank()) {
            val fallbackTokens = fallbackText.take(120)
                .replace(Regex("""[^\w\s]"""), " ")
                .split(Regex("""\s+"""))
                .map { cleanToken(it) }
                .filter { it.length >= 3 && !STOP_WORDS.contains(it) }
                .take(3)
            tokens.addAll(fallbackTokens)
        }

        // Sort by length descending so longer compound terms match before shorter substrings
        return tokens.sortedByDescending { it.length }
    }

    private fun cleanToken(raw: String): String {
        return raw.trim()
            .trim('"', '\'', '.', ',', '(', ')', '[', ']', '{', '}', ':', ';', '!', '?', '-', '_')
            .lowercase(Locale.ROOT)
    }

    /**
     * Finds non-overlapping matching intervals for the given keywords in the target text.
     */
    fun findMatches(
        text: String,
        keywords: List<String>,
        filterKeyword: String? = null
    ): List<KeywordMatchInterval> {
        if (text.isBlank() || keywords.isEmpty()) return emptyList()

        val lowerText = text.lowercase(Locale.ROOT)
        val activeKeywords = if (!filterKeyword.isNullOrBlank()) {
            listOf(filterKeyword.lowercase(Locale.ROOT))
        } else {
            keywords.map { it.lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct()
        }

        val rawIntervals = mutableListOf<KeywordMatchInterval>()

        for (kw in activeKeywords) {
            if (kw.length < 2) continue
            var searchStart = 0
            while (searchStart < lowerText.length) {
                val index = lowerText.indexOf(kw, searchStart)
                if (index == -1) break
                val end = index + kw.length
                rawIntervals.add(KeywordMatchInterval(index, end, kw))
                searchStart = index + 1 // advance by 1 to find all candidate matches
            }
        }

        if (rawIntervals.isEmpty()) return emptyList()

        // Sort by start position; for ties, longer intervals come first
        rawIntervals.sortWith(compareBy({ it.start }, { -(it.end - it.start) }))

        // Merge overlapping or nested intervals
        val merged = mutableListOf<KeywordMatchInterval>()
        var current = rawIntervals[0]

        for (i in 1 until rawIntervals.size) {
            val next = rawIntervals[i]
            if (next.start < current.end) {
                // Overlap: extend end if next reaches further
                if (next.end > current.end) {
                    current = current.copy(end = next.end)
                }
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }

    /**
     * Builds an AnnotatedString with matching keywords highlighted with customizable colors and styling.
     * Also returns occurrence counts per keyword.
     */
    fun highlightText(
        text: String,
        keywords: List<String>,
        query: String? = null,
        filterKeyword: String? = null,
        highlightColor: Color = Color(0xFF38BDF8),
        highlightBgColor: Color = Color(0xFF38BDF8).copy(alpha = 0.28f),
        baseTextColor: Color = Color(0xFFE2E8F0),
        boldMatchingTerms: Boolean = true
    ): HighlightedTextResult {
        if (text.isBlank()) {
            return HighlightedTextResult(
                annotatedString = buildAnnotatedString { },
                totalMatchesCount = 0,
                keywordFrequencies = emptyMap()
            )
        }

        val effectiveKeywords = extractKeywords(keywords, query)
        if (effectiveKeywords.isEmpty()) {
            return HighlightedTextResult(
                annotatedString = buildAnnotatedString {
                    withStyle(SpanStyle(color = baseTextColor)) {
                        append(text)
                    }
                },
                totalMatchesCount = 0,
                keywordFrequencies = emptyMap()
            )
        }

        val matches = findMatches(text, effectiveKeywords, filterKeyword)

        // Calculate occurrence counts per keyword
        val lowerText = text.lowercase(Locale.ROOT)
        val frequencies = mutableMapOf<String, Int>()
        for (kw in effectiveKeywords) {
            var count = 0
            var idx = lowerText.indexOf(kw)
            while (idx != -1) {
                count++
                idx = lowerText.indexOf(kw, idx + kw.length)
            }
            if (count > 0) {
                frequencies[kw] = count
            }
        }

        val annotated = buildAnnotatedString {
            var cursor = 0

            for (match in matches) {
                val matchStart = match.start.coerceIn(0, text.length)
                val matchEnd = match.end.coerceIn(0, text.length)

                if (matchStart > cursor) {
                    withStyle(SpanStyle(color = baseTextColor)) {
                        append(text.substring(cursor, matchStart))
                    }
                }

                if (matchEnd > matchStart) {
                    withStyle(
                        SpanStyle(
                            color = highlightColor,
                            fontWeight = if (boldMatchingTerms) FontWeight.Bold else FontWeight.Normal,
                            background = highlightBgColor
                        )
                    ) {
                        append(text.substring(matchStart, matchEnd))
                    }
                    cursor = matchEnd
                }
            }

            if (cursor < text.length) {
                withStyle(SpanStyle(color = baseTextColor)) {
                    append(text.substring(cursor))
                }
            }
        }

        return HighlightedTextResult(
            annotatedString = annotated,
            totalMatchesCount = matches.size,
            keywordFrequencies = frequencies
        )
    }

    /**
     * Builds highlighted and bolded snippet text for document preview snippets in search results list.
     */
    fun buildHighlightedSnippet(
        snippet: String,
        terms: List<String> = emptyList(),
        query: String? = null,
        highlightColor: Color = Color(0xFF38BDF8),
        highlightBgColor: Color = Color(0xFF38BDF8).copy(alpha = 0.22f),
        baseTextColor: Color = Color.Unspecified
    ): AnnotatedString {
        return highlightText(
            text = snippet,
            keywords = terms,
            query = query,
            highlightColor = highlightColor,
            highlightBgColor = highlightBgColor,
            baseTextColor = baseTextColor,
            boldMatchingTerms = true
        ).annotatedString
    }
}

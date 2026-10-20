package com.example.engine

import java.util.Locale

/** Builds the text shared from a search result: a readable excerpt plus where it came from. */
object ShareFormatter {

    const val DEFAULT_MAX_EXCERPT = 600
    private val WHITESPACE = Regex("""\s+""")

    /**
     * A single-paragraph excerpt of at most about [maxChars] characters. When one of [terms] occurs in [text] the
     * window is centred on its first occurrence so the shared passage shows what was actually searched for.
     */
    fun excerpt(text: String, terms: List<String>, maxChars: Int = DEFAULT_MAX_EXCERPT): String {
        val clean = text.replace(WHITESPACE, " ").trim()
        if (clean.length <= maxChars) return clean

        val lower = clean.lowercase(Locale.ROOT)
        val hit = terms.asSequence()
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotEmpty() }
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: 0

        var start = (hit - maxChars / 4).coerceAtLeast(0)
        var end = (start + maxChars).coerceAtMost(clean.length)
        start = (end - maxChars).coerceAtLeast(0)
        // Snap to word boundaries so words are not cut in half.
        if (start > 0) {
            val space = clean.indexOf(' ', start)
            if (space in start until end) start = space + 1
        }
        if (end < clean.length) {
            val space = clean.lastIndexOf(' ', end)
            if (space > start) end = space
        }
        return (if (start > 0) "…" else "") + clean.substring(start, end).trim() + (if (end < clean.length) "…" else "")
    }

    /** The message body handed to the system share sheet. */
    fun format(fileName: String, pageLabel: String?, text: String, terms: List<String>): String {
        val source = if (pageLabel.isNullOrBlank()) fileName else "$fileName, $pageLabel"
        return "“${excerpt(text, terms)}”\n\n— $source\nShared from LocalDoc Finder"
    }
}

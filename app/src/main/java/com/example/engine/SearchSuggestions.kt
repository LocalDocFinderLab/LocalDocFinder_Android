package com.example.engine

import java.util.Locale

/**
 * Search-as-you-type suggestions built from what the user already searched for and from the names of
 * indexed files. Pure and cheap, so it can run on every (debounced) keystroke.
 */
object SearchSuggestions {

    const val MIN_INPUT_LENGTH = 2
    const val DEFAULT_LIMIT = 6

    private val NAME_SEPARATORS = Regex("""[_\-.\s]+""")
    private val WORD_SPLIT = Regex("""\s+""")

    /** A file name as a readable phrase: `Tax_Return-2024.pdf` -> `Tax Return 2024`. */
    fun titleFromFileName(fileName: String): String =
        fileName.substringBeforeLast('.', fileName).replace(NAME_SEPARATORS, " ").trim()

    /**
     * @param input what the user has typed so far.
     * @param history previous queries, most recent first.
     * @param fileNames candidate file names (typically already narrowed to ones containing [input]).
     * @return up to [limit] suggestions, best first: queries that start with the input, then queries containing a
     * word that does, then file names; never the input itself, never duplicates (case-insensitive).
     */
    fun build(
        input: String,
        history: List<String>,
        fileNames: List<String>,
        limit: Int = DEFAULT_LIMIT
    ): List<String> {
        val typed = input.trim()
        if (typed.length < MIN_INPUT_LENGTH || limit <= 0) return emptyList()
        val q = typed.lowercase(Locale.ROOT)

        val out = LinkedHashMap<String, String>() // lower-cased key -> suggestion as shown
        fun offer(candidate: String) {
            val shown = candidate.trim()
            if (shown.isEmpty()) return
            val key = shown.lowercase(Locale.ROOT)
            if (key == q) return
            out.putIfAbsent(key, shown)
        }

        val queries = history.map { it.trim() }.filter { it.isNotEmpty() }
        queries.filter { it.lowercase(Locale.ROOT).startsWith(q) }.forEach(::offer)
        queries.filter { startsAnyWord(it, q) }.forEach(::offer)

        val titles = fileNames.map { titleFromFileName(it) }.filter { it.isNotEmpty() }.distinct()
        titles.filter { it.lowercase(Locale.ROOT).startsWith(q) }.sortedBy { it.length }.forEach(::offer)
        titles.filter { startsAnyWord(it, q) }.sortedBy { it.length }.forEach(::offer)

        return out.values.take(limit)
    }

    private fun startsAnyWord(text: String, lowerQuery: String): Boolean =
        text.lowercase(Locale.ROOT).split(WORD_SPLIT).any { it.startsWith(lowerQuery) } ||
            text.lowercase(Locale.ROOT).contains(" $lowerQuery")
}

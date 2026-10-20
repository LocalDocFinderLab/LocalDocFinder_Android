package com.example.engine.search

import java.text.Normalizer
import java.util.Locale

/**
 * Word-level text helpers shared by query parsing and ranking. Everything is plain Kotlin (no Android),
 * so the ranking rules can be unit tested on the JVM.
 *
 * Matching is done on whole words, never raw substrings: "art" must not match "start" or "party".
 */
object TextMatch {

    private val DIACRITICS = Regex("""\p{Mn}+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** Common English words that carry no meaning on their own; they never decide whether something matched. */
    val STOPWORDS: Set<String> = setOf(
        "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "has", "have", "in", "into", "is", "it",
        "its", "of", "on", "or", "that", "the", "this", "to", "was", "were", "will", "with", "my", "me", "i",
        "our", "your", "about", "all", "any", "find", "show", "where", "what", "which", "file", "files",
        "document", "documents", "doc", "docs",
        // Question words, so "how do I renew my passport" is decided by "renew" and "passport".
        "how", "why", "when", "who", "whose", "do", "does", "did", "can", "could", "should", "would", "there"
    )

    /** Lowercase and strip accents, so "Résumé" and "resume" compare equal (SQLite's unicode61 does the same). */
    fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        if (lower.all { it.code < 128 }) return lower
        return DIACRITICS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
    }

    /** Normalized words of [text], split on anything that isn't a letter or a digit. */
    fun words(text: String): List<String> =
        NON_WORD.split(normalize(text)).filter { it.isNotEmpty() }

    /** File name without its extension, e.g. "Q3_TaxReport.final.pdf" -> "Q3_TaxReport.final". */
    fun stem(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0 && fileName.length - dot <= 6) fileName.substring(0, dot) else fileName
    }

    fun extension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0 && dot < fileName.length - 1) fileName.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    /**
     * Words of a file name, including the pieces of glued-together words:
     * "Q3_TaxReport2023-final.pdf" -> [q3, taxreport2023, final, q, 3, tax, report, 2023].
     */
    fun fileNameWords(fileName: String): Set<String> {
        val stem = stem(fileName)
        val result = LinkedHashSet<String>()
        result.addAll(words(stem))
        // Split camelCase and letter/digit boundaries before normalizing (normalizing loses the case).
        val split = stem
            .replace(Regex("""(?<=\p{Ll})(?=\p{Lu})"""), " ")
            .replace(Regex("""(?<=\p{Lu})(?=\p{Lu}\p{Ll})"""), " ")
            .replace(Regex("""(?<=\p{L})(?=\p{N})|(?<=\p{N})(?=\p{L})"""), " ")
        result.addAll(words(split))
        return result
    }

    /** The file name as one normalized phrase: "Q3_Tax-Report.pdf" -> "q3 tax report". */
    fun fileNamePhrase(fileName: String): String = words(stem(fileName)).joinToString(" ")

    /** Very light plural folding so "invoices" finds "invoice" and "policies" finds "policy". */
    fun lightStem(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") -> word.dropLast(1)
        else -> word
    }

    /** How many typos a query word may contain and still match: none for short words. */
    fun typoBudget(word: String): Int = when {
        word.length < 4 || word.all { it.isDigit() } -> 0
        word.length < 8 -> 1
        else -> 2
    }

    /**
     * True when [a] and [b] are within [max] edits (insert, delete, substitute or swap two neighbours).
     * Bails out early, so it is cheap to call on every word of a chunk.
     */
    fun withinEdits(a: String, b: String, max: Int): Boolean {
        if (max <= 0) return a == b
        if (kotlin.math.abs(a.length - b.length) > max) return false
        if (a == b) return true
        val n = a.length
        val m = b.length
        var prevPrev = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, prevPrev[j - 2] + 1)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return false
            val t = prevPrev
            prevPrev = prev
            prev = cur
            cur = t
        }
        return prev[m] <= max
    }
}

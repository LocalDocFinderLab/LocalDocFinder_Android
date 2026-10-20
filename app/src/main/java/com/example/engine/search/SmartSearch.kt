package com.example.engine.search

/**
 * Picks the "meaning vs words" balance for a query, so people never have to choose between keyword,
 * vector and hybrid search themselves. The search itself always runs as hybrid (file name + words +
 * meaning); only how much the meaning score counts changes:
 *
 *  - Exact lookups lean on the words: operators (`"phrase"`, `name:`, `ext:`, `-word`), numbers and codes
 *    ("INV-4411", "2023"), and one-word queries, which are usually a name or a keyword.
 *  - Questions and longer descriptions lean on meaning: "how do I renew my car insurance" should find
 *    the policy even when it never uses those exact words.
 *  - Everything else stays balanced.
 *
 * Word matches still beat meaning-only matches of similar strength at every weight (see [SearchRanker.finalScore]).
 */
object SmartSearch {

    const val EXACT = 0.2f
    const val KEYWORD_LEANING = 0.35f
    const val BALANCED = 0.5f
    const val MEANING_LEANING = 0.75f

    private val QUESTION_WORDS = setOf("how", "why", "what", "when", "where", "which", "who", "whose", "can", "should", "do", "does", "is", "are")

    fun semanticWeight(query: ParsedQuery): Float {
        if (!query.hasPositiveTerms) return BALANCED
        if (query.phrases.isNotEmpty() || query.nameTerms.isNotEmpty() || query.excluded.isNotEmpty() || query.extensions.isNotEmpty()) {
            return EXACT
        }
        val required = query.requiredTerms
        if (required.any { term -> term.any { it.isDigit() } }) return EXACT
        val first = query.terms.firstOrNull()
        if ((first != null && first in QUESTION_WORDS && query.terms.size >= 3) || required.size >= 4) return MEANING_LEANING
        if (required.size == 1) return KEYWORD_LEANING
        return BALANCED
    }

    fun semanticWeight(raw: String): Float = semanticWeight(QueryParser.parse(raw))
}

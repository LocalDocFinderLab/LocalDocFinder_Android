package com.example

import com.example.engine.SearchMode
import com.example.engine.search.QueryParser
import com.example.engine.search.RankCandidate
import com.example.engine.search.SearchRanker
import com.example.engine.search.SemanticCalibration
import com.example.engine.search.SmartSearch
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartSearchTest {

    @Test
    fun exactLookupsLeanOnWords() {
        assertEquals(SmartSearch.EXACT, SmartSearch.semanticWeight("\"rental agreement\""), 0f)
        assertEquals(SmartSearch.EXACT, SmartSearch.semanticWeight("invoice ext:pdf"), 0f)
        assertEquals(SmartSearch.EXACT, SmartSearch.semanticWeight("budget -draft"), 0f)
        assertEquals(SmartSearch.EXACT, SmartSearch.semanticWeight("INV-4411"), 0f)
        assertEquals(SmartSearch.EXACT, SmartSearch.semanticWeight("tax return 2023"), 0f)
    }

    @Test
    fun oneWordLeansOnWordsAndShortQueriesStayBalanced() {
        assertEquals(SmartSearch.KEYWORD_LEANING, SmartSearch.semanticWeight("passport"), 0f)
        assertEquals(SmartSearch.BALANCED, SmartSearch.semanticWeight("car insurance"), 0f)
        assertEquals(SmartSearch.BALANCED, SmartSearch.semanticWeight(""), 0f)
    }

    @Test
    fun questionsAndDescriptionsLeanOnMeaning() {
        assertEquals(SmartSearch.MEANING_LEANING, SmartSearch.semanticWeight("how do I renew my car insurance"), 0f)
        assertEquals(SmartSearch.MEANING_LEANING, SmartSearch.semanticWeight("letter from landlord about rent increase"), 0f)
    }

    @Test
    fun aQuestionCanFindAStrongMeaningMatchAheadOfAOneWordMatch() {
        var id = 1L
        fun chunk(name: String, text: String, cosine: Float) = RankCandidate(id++, "file:///$name", name, 0, text, cosine = cosine)
        val noise = (1..40).map { chunk("n$it.txt", "filler $it", 0.40f) }
        val policy = chunk("policy.pdf", "Vehicle cover renewal form and premium schedule.", 0.85f)
        val oneWord = chunk("shopping.txt", "Buy a new car charger.", 0.45f)
        val all = noise + policy + oneWord
        val query = QueryParser.parse("how can I renew the car insurance ")
        val ranked = SearchRanker.rank(
            all,
            query,
            SemanticCalibration.from(all.mapNotNull { it.cosine }.toFloatArray()),
            SearchMode.HYBRID,
            SmartSearch.semanticWeight(query)
        )
        assertEquals("policy.pdf", ranked.first().best.fileName)
    }
}

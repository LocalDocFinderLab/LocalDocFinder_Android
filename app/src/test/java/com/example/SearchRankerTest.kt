package com.example

import com.example.engine.SearchMode
import com.example.engine.extraction.ImageText
import com.example.engine.search.MatchSummary
import com.example.engine.search.MatchType
import com.example.engine.search.QueryParser
import com.example.engine.search.RankCandidate
import com.example.engine.search.SearchRanker
import com.example.engine.search.SemanticCalibration
import com.example.engine.search.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankerTest {

    private var nextId = 1L

    private fun chunk(name: String, text: String, cosine: Float? = null, uri: String = "file:///$name", index: Int = 0) =
        RankCandidate(nextId++, uri, name, index, text, cosine = cosine)

    private fun rank(query: String, vararg candidates: RankCandidate, mode: SearchMode = SearchMode.HYBRID) =
        SearchRanker.rank(
            candidates.toList(),
            QueryParser.parse(query),
            SemanticCalibration.from(candidates.mapNotNull { it.cosine }.toFloatArray()),
            mode
        )

    // --- query parsing ---

    @Test
    fun parsesPhrasesExclusionsNameAndTypeOperators() {
        val q = QueryParser.parse("\"bank statement\" march -draft name:hdfc ext:pdf")
        assertEquals(listOf("bank", "statement", "march"), q.terms)
        assertEquals(listOf(listOf("bank", "statement")), q.phrases)
        assertEquals(listOf("draft"), q.excluded)
        assertEquals(listOf("hdfc"), q.nameTerms)
        assertEquals(setOf("pdf"), q.extensions)
        assertFalse(q.lastTermIsPrefix)
    }

    @Test
    fun lastWordIsAPrefixWhileTyping() {
        assertTrue(QueryParser.parse("tax invo").lastTermIsPrefix)
        assertFalse(QueryParser.parse("tax invoice ").lastTermIsPrefix)
        assertTrue(QueryParser.parse("type:image").extensions.contains("jpg"))
    }

    @Test
    fun stopWordsDoNotDecideCoverage() {
        assertEquals(listOf("invoice", "march"), QueryParser.parse("the invoice from march").requiredTerms)
        assertEquals(listOf("the"), QueryParser.parse("the").requiredTerms)
    }

    // --- file names ---

    @Test
    fun fileNameWordsSplitGluedAndCamelCaseNames() {
        val words = TextMatch.fileNameWords("Q3_TaxReport2023-final.pdf")
        assertTrue(words.containsAll(listOf("q3", "tax", "report", "2023", "final")))
    }

    @Test
    fun exactFileNameBeatsAFileThatMentionsTheWordsManyTimes() {
        val named = chunk("Tax_Return_2023.pdf", "Form 1040 schedule A itemized deductions.")
        val mentions = chunk(
            "notes.txt",
            "My tax return is late. The tax return needs a signature. Ask about the tax return refund."
        )
        val ranked = rank("tax return 2023", mentions, named)
        assertEquals("Tax_Return_2023.pdf", ranked[0].best.fileName)
        assertEquals(MatchType.FILE_NAME, ranked[0].matchType)
    }

    @Test
    fun partialAndMisspelledFileNamesStillMatch() {
        val q = QueryParser.parse("reciept")
        assertTrue(SearchRanker.nameScore("Receipt_Amazon.jpg", q) > 0f)
        assertTrue(SearchRanker.nameScore("InvoiceMarch.pdf", QueryParser.parse("invoice march")) > 0.8f)
        assertTrue(SearchRanker.nameScore("passport_scan.pdf", QueryParser.parse("pass")) > 0f)
        assertEquals(0f, SearchRanker.nameScore("holiday.jpg", QueryParser.parse("invoice")), 0f)
    }

    // --- text ---

    @Test
    fun wholeWordsOnlyNoSubstringMatches() {
        val ranked = rank("art ", chunk("a.txt", "We start the party at noon."))
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun allWordsBeatSomeWordsAndPhraseBeatsScattered() {
        val phrase = chunk("a.txt", "Please find the rental agreement attached for review.")
        val scattered = chunk("b.txt", "The agreement covers the car. Rental terms are below.")
        val partial = chunk("c.txt", "This rental is for the summer house near the lake.")
        val ranked = rank("rental agreement ", partial, scattered, phrase)
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), ranked.map { it.best.fileName })
        assertEquals(MatchType.EXACT_PHRASE, ranked[0].matchType)
        assertEquals(MatchType.ALL_WORDS, ranked[1].matchType)
        assertEquals(MatchType.SOME_WORDS, ranked[2].matchType)
    }

    @Test
    fun pluralsAndTyposAreForgiven() {
        assertEquals(1, rank("invoices ", chunk("a.txt", "Invoice number 4411 due in May.")).size)
        assertEquals(1, rank("insurence ", chunk("b.txt", "Your car insurance renewal notice.")).size)
    }

    @Test
    fun oneResultPerFileWithMatchCount() {
        val uri = "file:///manual.pdf"
        val ranked = rank(
            "battery ",
            chunk("manual.pdf", "Charge the battery fully before first use.", uri = uri, index = 0),
            chunk("manual.pdf", "Battery life depends on brightness.", uri = uri, index = 1),
            chunk("manual.pdf", "Warranty information.", uri = uri, index = 2)
        )
        assertEquals(1, ranked.size)
        assertEquals(2, ranked[0].matchCount)
    }

    @Test
    fun excludedAndPhraseOperatorsFilterFiles() {
        val draft = chunk("a.txt", "Budget plan draft for 2024")
        val final = chunk("b.txt", "Budget plan final for 2024")
        assertEquals(listOf("b.txt"), rank("budget -draft", draft, final).map { it.best.fileName })
        assertEquals(listOf("a.txt"), rank("\"plan draft\"", draft, final).map { it.best.fileName })
    }

    @Test
    fun wordsCloseTogetherBeatTheSameWordsScatteredButNotTheExactPhrase() {
        // Same length and word counts, so only how close the words are tells them apart.
        val phrase = chunk("a.txt", "Please sign the rental agreement and send " + "it back soon ".repeat(4))
        val near = chunk("b.txt", "Please sign the agreement for this rental " + "it back soon ".repeat(4))
        val scattered = chunk("c.txt", "Please sign the agreement it back soon " + "it back soon ".repeat(3) + "and this rental")
        val ranked = rank("rental agreement ", scattered, near, phrase)
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), ranked.map { it.best.fileName })
        assertEquals(MatchType.ALL_WORDS, ranked[1].matchType)
    }

    @Test
    fun questionWordsDoNotDecideTheMatch() {
        assertEquals(listOf("renew", "passport"), QueryParser.parse("how do I renew my passport").requiredTerms)
        val ranked = rank("how do I renew my passport ", chunk("p.txt", "Renew your passport online in ten days."))
        assertEquals(MatchType.ALL_WORDS, ranked.single().matchType)
    }

    @Test
    fun fileNameTypedWithItsExtensionIsAnExactNameMatch() {
        assertEquals(1f, SearchRanker.nameScore("Tax_Return.pdf", QueryParser.parse("tax_return.pdf")), 0f)
        assertEquals(1f, SearchRanker.nameScore("Tax_Return.pdf", QueryParser.parse("tax return")), 0f)
        assertEquals(0f, SearchRanker.nameScore("notes.pdf", QueryParser.parse("tax return pdf")), 0f)
    }

    // --- meaning ---

    @Test
    fun meaningOnlyMatchesNeedToStandOutFromTheCollection() {
        val noise = (1..40).map { chunk("n$it.txt", "unrelated text number $it", cosine = 0.40f + (it % 5) * 0.01f) }
        val close = chunk("car.txt", "Vehicle policy renewal and premium.", cosine = 0.80f)
        val ranked = rank("auto coverage", *(noise + close).toTypedArray())
        assertEquals(listOf("car.txt"), ranked.map { it.best.fileName })
        assertEquals(MatchType.SIMILAR_MEANING, ranked[0].matchType)
    }

    @Test
    fun wordMatchesOutrankMeaningOnlyMatches() {
        val noise = (1..40).map { chunk("n$it.txt", "filler $it", cosine = 0.40f) }
        val meaning = chunk("m.txt", "Vehicle policy renewal.", cosine = 0.85f)
        val words = chunk("w.txt", "Your auto coverage starts today.", cosine = 0.55f)
        val ranked = rank("auto coverage ", *(noise + meaning + words).toTypedArray())
        assertEquals(listOf("w.txt", "m.txt"), ranked.map { it.best.fileName })
    }

    // --- images ---

    @Test
    fun textReadFromAPictureIsLabelledAsSuch() {
        val ocr = chunk("IMG_2041.jpg", ImageText.compose("TOTAL 42.50\nStarbucks Coffee", "Captured with camera: Pixel 8."))
        val details = chunk("IMG_2042.jpg", ImageText.compose("", "Captured with camera: Pixel 8. Starbucks visit."))
        val ranked = rank("starbucks ", ocr, details)
        assertEquals(MatchType.IMAGE_TEXT, ranked.first { it.best.fileName == "IMG_2041.jpg" }.matchType)
        assertEquals(MatchType.PHOTO_DETAILS, ranked.first { it.best.fileName == "IMG_2042.jpg" }.matchType)
    }

    // --- snippets ---

    @Test
    fun snippetShowsThePlaceWithTheMostQueryWords() {
        val text = "intro ".repeat(80) + "the rental agreement was signed " + "filler ".repeat(80)
        val snippet = SearchRanker.bestSnippet(text, listOf("rental", "agreement"))
        assertTrue(snippet, snippet.contains("rental agreement"))
        assertTrue(snippet.startsWith("…"))
    }

    @Test
    fun summaryLineExplainsTheMatch() {
        assertEquals("File name + text • 3 places • p. 2", MatchSummary.describe(MatchType.FILE_NAME, 3, true, true, "p. 2"))
        assertEquals("Text in image", MatchSummary.describe(MatchType.IMAGE_TEXT, 1, false, true, null))
        assertEquals("", MatchSummary.describe(MatchType.NONE, 1, false, false, null))
    }
}

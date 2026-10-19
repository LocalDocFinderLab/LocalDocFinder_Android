package com.example.engine.search

import com.example.engine.SearchMode
import com.example.engine.extraction.ImageText
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Why a result was found, shown on the result card so the user can tell a file-name hit from a text hit. */
enum class MatchType(val label: String) {
    FILE_NAME("File name"),
    EXACT_PHRASE("Exact phrase"),
    ALL_WORDS("All words"),
    SOME_WORDS("Some words"),
    IMAGE_TEXT("Text in image"),
    PHOTO_DETAILS("Photo details"),
    SIMILAR_MEANING("Similar meaning"),
    NONE("")
}

/** One chunk that might answer the query. */
data class RankCandidate(
    val id: Long,
    val fileUri: String,
    val fileName: String,
    val chunkIndex: Int,
    val text: String,
    val tags: String = "",
    val timestamp: Long = 0L,
    /** Cosine similarity with the query vector; null when the chunk has no comparable vector. */
    val cosine: Float? = null
)

/** One file in the result list, represented by its best chunk. */
data class RankedFile(
    val best: RankCandidate,
    val score: Float,
    val lexicalScore: Float,
    val semanticScore: Float,
    val nameScore: Float,
    val textScore: Float,
    val matchType: MatchType,
    val matchedInName: Boolean,
    val matchedInText: Boolean,
    /** Chunks of this file that matched; more than one means "N more places in this file". */
    val matchCount: Int
)

/**
 * Turns raw cosine similarities into a 0..1 "how unusually close is this" score.
 *
 * Raw cosines are not comparable across models (BGE puts unrelated text at ~0.5, MiniLM at ~0.1), so
 * a fixed cut-off either floods the list with noise or hides real matches. Instead each chunk is measured
 * against the whole collection for this query: a chunk two standard deviations above the average is a
 * real semantic match, one at the average is noise.
 */
class SemanticCalibration private constructor(
    private val mean: Float,
    private val std: Float,
    private val max: Float,
    private val count: Int
) {
    fun score(cosine: Float?): Float {
        if (cosine == null || count == 0) return 0f
        if (count < SMALL_CORPUS) {
            val span = max - mean
            return if (span <= 1e-4f) 0f else ((cosine - mean) / span).coerceIn(0f, 1f)
        }
        val z = (cosine - mean) / std
        return ((z - 1f) / 2.5f).coerceIn(0f, 1f)
    }

    /** Close enough to be shown even though none of the query's words appear. */
    fun accepts(cosine: Float?): Boolean {
        if (cosine == null || count == 0) return false
        return if (count < SMALL_CORPUS) score(cosine) >= 0.5f else (cosine - mean) / std >= 2f
    }

    companion object {
        private const val SMALL_CORPUS = 20
        private const val MIN_STD = 0.02f

        val NONE = SemanticCalibration(0f, 1f, 0f, 0)

        fun from(cosines: FloatArray): SemanticCalibration {
            if (cosines.isEmpty()) return NONE
            var sum = 0.0
            var top = -1f
            for (c in cosines) {
                sum += c
                if (c > top) top = c
            }
            val mean = (sum / cosines.size).toFloat()
            var sq = 0.0
            for (c in cosines) {
                val d = c - mean
                sq += d * d
            }
            val std = max(MIN_STD, sqrt(sq / cosines.size).toFloat())
            return SemanticCalibration(mean, std, top, cosines.size)
        }
    }
}

/**
 * Ranks search candidates the way people expect a file search to behave:
 *
 *  1. A file whose **name** is what was typed comes first ("tax return" -> `Tax_Return_2023.pdf`).
 *  2. Then files containing **all** the words, best when they appear as the exact phrase.
 *  3. Then files whose text is **close in meaning** (vector search), even without the words.
 *  4. Files that only share some of the words come last.
 *
 * Matching is word-based with plural folding, prefix matching for the word being typed, and a typo budget
 * ("recipt" finds "receipt"). Each file appears once, represented by its best chunk.
 */
object SearchRanker {

    private const val K1 = 1.2f
    private const val B = 0.75f

    /** Results for a query that may hit both name and text; [semanticWeight] is the "meaning vs words" slider (0..1). */
    fun rank(
        candidates: Collection<RankCandidate>,
        query: ParsedQuery,
        calibration: SemanticCalibration,
        mode: SearchMode = SearchMode.HYBRID,
        semanticWeight: Float = 0.5f,
        corpusSize: Int = candidates.size
    ): List<RankedFile> {
        if (candidates.isEmpty()) return emptyList()
        val required = query.requiredTerms
        val prepared = candidates.map { PreparedChunk(it) }

        val excludedFiles = HashSet<String>()
        if (query.excluded.isNotEmpty()) {
            for (p in prepared) {
                if (query.excluded.any { it in p.words || it in p.nameWords }) excludedFiles.add(p.c.fileUri)
            }
        }

        val idf = inverseDocumentFrequencies(prepared, required, corpusSize)
        val avgLength = prepared.sumOf { it.length }.toFloat() / prepared.size

        val semanticFallback = required.isEmpty() || prepared.none { p -> required.any { it in p.words } }
        val scored = ArrayList<ScoredChunk>(prepared.size)
        val nameCache = HashMap<String, Float>()
        for (p in prepared) {
            if (p.c.fileUri in excludedFiles) continue
            if (query.extensions.isNotEmpty() && TextMatch.extension(p.c.fileName) !in query.extensions) continue
            val name = nameCache.getOrPut(p.c.fileUri) { nameScore(p.c.fileName, query) }
            if (query.nameTerms.isNotEmpty() && !nameHasAll(p, query.nameTerms)) continue

            val text = textScore(p, query, required, idf, avgLength)
            if (query.phrases.isNotEmpty() && !text.hasPhrases && !nameHasPhrases(p, query.phrases)) continue

            val semantic = calibration.score(p.c.cosine)
            val lexical = if (name > 0f || text.score > 0f) {
                (max(name, text.score) + 0.1f * min(name, text.score)).coerceAtMost(1f)
            } else 0f
            val acceptedSemantic = calibration.accepts(p.c.cosine)
            if (lexical <= 0f && !acceptedSemantic && mode != SearchMode.VECTOR) continue

            val type = when {
                name > 0f && name >= text.score -> MatchType.FILE_NAME
                text.score > 0f && p.isImage -> if (text.inRecognizedText) MatchType.IMAGE_TEXT else MatchType.PHOTO_DETAILS
                text.score > 0f && text.phrase -> MatchType.EXACT_PHRASE
                text.score > 0f && text.allWords -> MatchType.ALL_WORDS
                text.score > 0f -> MatchType.SOME_WORDS
                else -> MatchType.SIMILAR_MEANING
            }
            scored.add(
                ScoredChunk(
                    p.c,
                    finalScore(lexical, semantic, mode, semanticWeight),
                    lexical,
                    semantic,
                    name,
                    text.score,
                    type,
                    acceptedSemantic || lexical > 0f
                )
            )
        }

        if (mode == SearchMode.VECTOR || semanticFallback) {
            // Nothing contains the words: fall back to the closest chunks so the list is never blank,
            // but keep them few, since they are only best guesses.
            val strong = scored.count { it.relevant }
            if (strong == 0) {
                val guesses = prepared.filter { it.c.cosine != null }
                    .sortedByDescending { it.c.cosine }
                    .take(3)
                    .filter { g -> scored.none { it.chunk.id == g.c.id } }
                for (g in guesses) {
                    val s = calibration.score(g.c.cosine).coerceAtLeast(0.05f)
                    scored.add(ScoredChunk(g.c, finalScore(0f, s, mode, semanticWeight) * 0.5f, 0f, s, 0f, 0f, MatchType.SIMILAR_MEANING, true))
                }
            }
        }

        return scored.filter { it.relevant }
            .groupBy { it.chunk.fileUri }
            .map { (_, chunks) ->
                val best = chunks.maxWith(compareBy<ScoredChunk> { it.score }.thenBy { -it.chunk.chunkIndex })
                val count = chunks.size
                RankedFile(
                    best = best.chunk,
                    score = (best.score + 0.015f * min(4, count - 1)).coerceAtMost(1f),
                    lexicalScore = best.lexical,
                    semanticScore = best.semantic,
                    nameScore = best.name,
                    textScore = chunks.maxOf { it.text },
                    matchType = best.type,
                    matchedInName = best.name > 0f,
                    matchedInText = chunks.any { it.text > 0f },
                    matchCount = count
                )
            }
            .sortedWith(
                compareByDescending<RankedFile> { it.score }
                    .thenByDescending { it.nameScore }
                    .thenByDescending { it.best.timestamp }
            )
    }

    /**
     * Blends word and meaning scores. Word matches always beat meaning-only matches of similar strength,
     * because a file that literally contains what was typed is almost always what the user wanted.
     */
    fun finalScore(lexical: Float, semantic: Float, mode: SearchMode, semanticWeight: Float = 0.5f): Float {
        val w = semanticWeight.coerceIn(0f, 1f)
        return when (mode) {
            SearchMode.KEYWORD -> (lexical * 0.98f + 0.02f * semantic)
            SearchMode.VECTOR -> (semantic * 0.98f + 0.02f * lexical)
            SearchMode.HYBRID -> {
                val share = 0.5f * w
                if (lexical > 0f) (1f - share) * lexical + share * semantic else (0.35f + 0.3f * w) * semantic
            }
        }.coerceIn(0f, 1f)
    }

    /**
     * 0..1 for how well [fileName] matches: 1 for the exact name, ~0.9 when the name starts with the query,
     * ~0.88 when every word is in the name, less for partial, glued ("taxreport") or misspelled matches.
     */
    fun nameScore(fileName: String, query: ParsedQuery): Float {
        val required = query.requiredTerms
        if (required.isEmpty()) return 0f
        val phrase = TextMatch.fileNamePhrase(fileName)
        val compact = phrase.replace(" ", "")
        val qPhrase = query.allTerms.joinToString(" ")
        val qCompact = qPhrase.replace(" ", "")
        if (phrase == qPhrase || compact == qCompact) return 1f
        if (qCompact.length >= 3 && (phrase.startsWith("$qPhrase ") || compact.startsWith(qCompact))) return 0.93f

        val nameWords = TextMatch.fileNameWords(fileName)
        var matched = 0
        var weightSum = 0f
        for (term in required) {
            val w = nameTermWeight(term, nameWords, compact, query.isPrefixTerm(term))
            if (w > 0f) {
                matched++
                weightSum += w
            }
        }
        if (matched == 0) return 0f
        val fraction = matched.toFloat() / required.size
        return 0.88f * (weightSum / matched) * fraction * fraction
    }

    private fun nameTermWeight(term: String, nameWords: Set<String>, compact: String, typing: Boolean): Float {
        if (term in nameWords) return 1f
        val stem = TextMatch.lightStem(term)
        if (nameWords.any { TextMatch.lightStem(it) == stem }) return 0.95f
        if (nameWords.any { it.startsWith(term) }) return if (typing) 0.9f else 0.85f
        if (term.length >= 3 && compact.contains(term)) return 0.7f
        val budget = TextMatch.typoBudget(term)
        if (budget > 0 && nameWords.any { it.length >= 3 && TextMatch.withinEdits(term, it, budget) }) return 0.55f
        return 0f
    }

    private fun nameHasAll(p: PreparedChunk, terms: List<String>): Boolean {
        val compact = TextMatch.fileNamePhrase(p.c.fileName).replace(" ", "")
        return terms.all { nameTermWeight(it, p.nameWords, compact, false) > 0f }
    }

    private fun nameHasPhrases(p: PreparedChunk, phrases: List<List<String>>): Boolean {
        val name = TextMatch.fileNamePhrase(p.c.fileName)
        return phrases.all { name.contains(it.joinToString(" ")) }
    }

    private class TextScore(
        val score: Float,
        val phrase: Boolean,
        val allWords: Boolean,
        val hasPhrases: Boolean,
        val inRecognizedText: Boolean
    )

    private fun textScore(
        p: PreparedChunk,
        query: ParsedQuery,
        required: List<String>,
        idf: Map<String, Float>,
        avgLength: Float
    ): TextScore {
        val hasPhrases = query.phrases.all { containsSequence(p.sequence, it, prefixLast = false) }
        if (required.isEmpty()) return TextScore(0f, false, false, hasPhrases, false)

        var idfTotal = 0f
        var covered = 0f
        var matched = 0
        var raw = 0f
        var allExact = true
        val lengthNorm = 1f - B + B * (p.length / max(1f, avgLength))
        for (term in required) {
            val termIdf = idf[term] ?: 1f
            idfTotal += termIdf
            val hit = matchTerm(term, query.isPrefixTerm(term), p.words)
            if (hit.weight > 0f) {
                matched++
                covered += termIdf * hit.weight
                val tf = hit.tf.toFloat()
                raw += termIdf * hit.weight * (tf * (K1 + 1f)) / (tf + K1 * lengthNorm)
                if (hit.weight < 1f) allExact = false
            } else {
                allExact = false
            }
        }
        if (matched == 0) return TextScore(0f, false, false, hasPhrases, false)

        val coverage = covered / idfTotal
        val fraction = matched.toFloat() / required.size
        val saturation = 1f - exp(-raw / idfTotal)
        val terms = query.terms.ifEmpty { required }
        val phrase = if (terms.size > 1) {
            containsSequence(p.sequence, terms, prefixLast = query.lastTermIsPrefix)
        } else {
            allExact
        }
        val score = coverage * fraction * (0.62f + 0.23f * saturation + 0.15f * (if (phrase) 1f else 0f))

        val inRecognized = p.isImage && p.recognizedWords.isNotEmpty() &&
            required.any { matchTerm(it, query.isPrefixTerm(it), p.recognizedWords).weight >= 0.75f }
        return TextScore(score, phrase, matched == required.size, hasPhrases, inRecognized)
    }

    private class TermHit(val weight: Float, val tf: Int)

    private val MISS = TermHit(0f, 0)

    /** Exact word, then plural form, then prefix, then a word within the typo budget. */
    private fun matchTerm(term: String, typing: Boolean, words: Map<String, Int>): TermHit {
        words[term]?.let { return TermHit(1f, it) }
        val stem = TextMatch.lightStem(term)
        var tf = 0
        for ((w, n) in words) if (TextMatch.lightStem(w) == stem) tf += n
        if (tf > 0) return TermHit(0.95f, tf)
        if (typing || term.length >= 3) {
            for ((w, n) in words) if (w.startsWith(term)) tf += n
            if (tf > 0) return TermHit(if (typing) 0.9f else 0.75f, tf)
        }
        val budget = TextMatch.typoBudget(term)
        if (budget > 0) {
            for ((w, n) in words) if (w.length >= 3 && TextMatch.withinEdits(term, w, budget)) tf += n
            if (tf > 0) return TermHit(0.6f, tf)
        }
        return MISS
    }

    private fun containsSequence(sequence: List<String>, terms: List<String>, prefixLast: Boolean): Boolean {
        if (terms.isEmpty()) return true
        if (sequence.size < terms.size) return false
        outer@ for (i in 0..sequence.size - terms.size) {
            for (k in terms.indices) {
                val word = sequence[i + k]
                val term = terms[k]
                val ok = word == term ||
                    TextMatch.lightStem(word) == TextMatch.lightStem(term) ||
                    (prefixLast && k == terms.lastIndex && word.startsWith(term))
                if (!ok) continue@outer
            }
            return true
        }
        return false
    }

    private fun inverseDocumentFrequencies(
        prepared: List<PreparedChunk>,
        terms: List<String>,
        corpusSize: Int
    ): Map<String, Float> {
        val n = max(corpusSize, prepared.size).toFloat()
        return terms.associateWith { term ->
            val df = prepared.count { term in it.words }.toFloat()
            max(0.1f, ln(1f + (n - df + 0.5f) / (df + 0.5f)))
        }
    }

    /**
     * The ~[window]-character piece of [text] that contains the most different query words, so the snippet
     * shows why the result matched instead of always the first occurrence.
     */
    fun bestSnippet(text: String, terms: List<String>, window: Int = 220): String {
        val clean = text.trim()
        if (clean.length <= window) return clean
        val hits = ArrayList<Pair<Int, Int>>()
        terms.forEachIndexed { t, term ->
            if (term.length < 2) return@forEachIndexed
            var idx = clean.indexOf(term, ignoreCase = true)
            while (idx >= 0 && hits.size < 400) {
                val atWordStart = idx == 0 || !clean[idx - 1].isLetterOrDigit()
                if (atWordStart) hits.add(idx to t)
                idx = clean.indexOf(term, idx + term.length, ignoreCase = true)
            }
        }
        if (hits.isEmpty()) return clean.take(window).trimEnd() + "…"
        hits.sortBy { it.first }

        var bestStart = hits[0].first
        var bestDistinct = 0
        var bestCount = 0
        var lo = 0
        val inWindow = IntArray(terms.size)
        var distinct = 0
        for (hi in hits.indices) {
            if (inWindow[hits[hi].second]++ == 0) distinct++
            while (hits[hi].first - hits[lo].first > window - 40) {
                if (--inWindow[hits[lo].second] == 0) distinct--
                lo++
            }
            val count = hi - lo + 1
            if (distinct > bestDistinct || (distinct == bestDistinct && count > bestCount)) {
                bestDistinct = distinct
                bestCount = count
                bestStart = hits[lo].first
            }
        }

        var start = max(0, bestStart - 50)
        while (start > 0 && start > bestStart - 70 && !clean[start - 1].isWhitespace()) start--
        var end = min(clean.length, start + window)
        while (end < clean.length && end < start + window + 20 && !clean[end].isWhitespace()) end++
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < clean.length) "…" else ""
        return prefix + clean.substring(start, end).trim() + suffix
    }

    private class ScoredChunk(
        val chunk: RankCandidate,
        val score: Float,
        val lexical: Float,
        val semantic: Float,
        val name: Float,
        val text: Float,
        val type: MatchType,
        val relevant: Boolean
    )

    private class PreparedChunk(val c: RankCandidate) {
        val sequence: List<String> = TextMatch.words(c.text)
        val words: Map<String, Int> = HashMap<String, Int>().also { map ->
            for (w in sequence) map[w] = (map[w] ?: 0) + 1
            for (w in TextMatch.words(c.tags)) map[w] = (map[w] ?: 0) + 1
        }
        val length: Int = max(1, sequence.size)
        val nameWords: Set<String> = TextMatch.fileNameWords(c.fileName)
        val isImage: Boolean = TextMatch.extension(c.fileName) in QueryParser.IMAGE_EXTENSIONS
        val recognizedWords: Map<String, Int> = if (isImage) {
            HashMap<String, Int>().also { map ->
                for (w in TextMatch.words(ImageText.recognizedPart(c.text))) map[w] = (map[w] ?: 0) + 1
            }
        } else emptyMap()
    }
}

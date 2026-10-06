package com.example.engine.embedding

import java.io.InputStream
import java.text.Normalizer

/**
 * BERT WordPiece tokenizer (uncased by default) that follows Hugging Face's `BertTokenizer`, so token ids
 * match what BGE, MiniLM, E5 and GTE were trained with: text cleanup, CJK spacing, lowercasing + accent
 * stripping, punctuation splitting, then greedy longest-match-first subword lookup against `vocab.txt`.
 *
 * Pure Kotlin/JVM (no Android types) so it is unit-tested off-device.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val lowerCase: Boolean = true,
    private val maxCharsPerWord: Int = 100
) {
    val clsId: Int = vocab[CLS] ?: throw IllegalArgumentException("vocab is missing $CLS")
    val sepId: Int = vocab[SEP] ?: throw IllegalArgumentException("vocab is missing $SEP")
    val unkId: Int = vocab[UNK] ?: throw IllegalArgumentException("vocab is missing $UNK")
    val padId: Int = vocab[PAD] ?: 0

    val vocabSize: Int get() = vocab.size

    class Encoding(val ids: IntArray, val attentionMask: IntArray) {
        val length: Int get() = ids.size
    }

    /**
     * `[CLS] tokens [SEP]`, truncated to [maxLength] tokens in total. Not padded: the model wrapper pads to
     * the model's fixed sequence length (or runs the exact length when the model has a dynamic one).
     */
    fun encode(text: String, maxLength: Int): Encoding {
        require(maxLength >= 3) { "maxLength must fit [CLS] x [SEP]" }
        val budget = maxLength - 2
        val ids = ArrayList<Int>(minOf(budget, 128) + 2)
        ids.add(clsId)
        var taken = 0
        outer@ for (word in basicTokenize(text)) {
            for (id in wordPiece(word)) {
                if (taken >= budget) break@outer
                ids.add(id)
                taken++
            }
        }
        ids.add(sepId)
        return Encoding(ids.toIntArray(), IntArray(ids.size) { 1 })
    }

    /** Surface tokens (post-WordPiece), for debugging and tests. */
    fun tokenize(text: String): List<String> {
        val inverse = vocab.entries.associate { it.value to it.key }
        return basicTokenize(text).flatMap { wordPiece(it) }.map { inverse[it] ?: UNK }
    }

    // ---- BasicTokenizer ---------------------------------------------------------------------------------

    private fun basicTokenize(text: String): List<String> {
        val cleaned = tokenizeChineseChars(cleanText(text))
        val words = ArrayList<String>()
        for (raw in cleaned.split(' ')) {
            if (raw.isEmpty()) continue
            var token = raw
            if (lowerCase) {
                token = stripAccents(token.lowercase())
            }
            splitOnPunctuation(token, words)
        }
        return words
    }

    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue
            if (isWhitespace(cp)) sb.append(' ') else sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun tokenizeChineseChars(text: String): String {
        val sb = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isCjk(cp)) {
                sb.append(' ').appendCodePoint(cp).append(' ')
            } else {
                sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    private fun stripAccents(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun splitOnPunctuation(token: String, out: MutableList<String>) {
        val current = StringBuilder()
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            i += Character.charCount(cp)
            if (isPunctuation(cp)) {
                if (current.isNotEmpty()) {
                    out.add(current.toString())
                    current.setLength(0)
                }
                out.add(String(Character.toChars(cp)))
            } else {
                current.appendCodePoint(cp)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
    }

    // ---- WordPiece --------------------------------------------------------------------------------------

    private fun wordPiece(word: String): List<Int> {
        if (word.length > maxCharsPerWord) return listOf(unkId)
        val pieces = ArrayList<Int>(2)
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (start < end) {
                val sub = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) {
                    found = id
                    break
                }
                end--
                // Never cut a surrogate pair in half.
                if (end > start && Character.isLowSurrogate(word[end]) && Character.isHighSurrogate(word[end - 1])) end--
            }
            if (found < 0) return listOf(unkId) // any unmatched piece makes the whole word [UNK]
            pieces.add(found)
            start = end
        }
        return pieces
    }

    // ---- Character classes (match Hugging Face's BertTokenizer) -----------------------------------------

    private fun isWhitespace(cp: Int): Boolean =
        cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
            Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        val type = Character.getType(cp)
        return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
    }

    private fun isPunctuation(cp: Int): Boolean {
        if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isCjk(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) || (cp in 0x20000..0x2A6DF) ||
            (cp in 0x2A700..0x2B73F) || (cp in 0x2B740..0x2B81F) || (cp in 0x2B820..0x2CEAF) ||
            (cp in 0xF900..0xFAFF) || (cp in 0x2F800..0x2FA1F)

    companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val UNK = "[UNK]"
        const val PAD = "[PAD]"

        /** Reads a `vocab.txt` (one token per line, line number = id). */
        fun loadVocab(input: InputStream): Map<String, Int> {
            val vocab = HashMap<String, Int>(32_768)
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                var index = 0
                for (line in lines) {
                    vocab[line.trimEnd('\r', '\n')] = index++
                }
            }
            return vocab
        }

        fun fromVocab(input: InputStream, lowerCase: Boolean = true) = WordPieceTokenizer(loadVocab(input), lowerCase)
    }
}

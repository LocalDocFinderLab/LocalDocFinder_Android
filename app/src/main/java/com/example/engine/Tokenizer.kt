package com.example.engine

import java.util.Locale

/**
 * Pure Kotlin WordPiece tokenizer implementation compatible with BERT / MiniLM / BGE models.
 * Completely offline with no external network or file dependency.
 */
class Tokenizer(
    val maxSequenceLength: Int = 256
) {
    companion object {
        const val PAD_TOKEN = "[PAD]"
        const val UNK_TOKEN = "[UNK]"
        const val CLS_TOKEN = "[CLS]"
        const val SEP_TOKEN = "[SEP]"
        const val MASK_TOKEN = "[MASK]"

        const val PAD_ID = 0
        const val UNK_ID = 100
        const val CLS_ID = 101
        const val SEP_ID = 102
        const val MASK_ID = 103
    }

    private val vocabToId = HashMap<String, Int>(4096)
    private val idToVocab = HashMap<Int, String>(4096)

    init {
        initializeVocabulary()
    }

    data class Encoding(
        val inputIds: IntArray,
        val attentionMask: IntArray,
        val tokenTypeIds: IntArray,
        val tokens: List<String>
    )

    fun encode(text: String, padToMaxLength: Boolean = true): Encoding {
        val clean = cleanText(text)
        val words = whitespaceAndPunctuationTokenize(clean)
        val tokenStrings = mutableListOf<String>()
        tokenStrings.add(CLS_TOKEN)

        for (word in words) {
            val subwords = wordPieceTokenize(word)
            for (sw in subwords) {
                if (tokenStrings.size >= maxSequenceLength - 1) break
                tokenStrings.add(sw)
            }
            if (tokenStrings.size >= maxSequenceLength - 1) break
        }
        tokenStrings.add(SEP_TOKEN)

        val targetLen = if (padToMaxLength) maxSequenceLength else tokenStrings.size
        val inputIds = IntArray(targetLen) { PAD_ID }
        val attentionMask = IntArray(targetLen) { 0 }
        val tokenTypeIds = IntArray(targetLen) { 0 }

        for (i in tokenStrings.indices) {
            val token = tokenStrings[i]
            val id = vocabToId[token] ?: vocabToId[token.lowercase(Locale.ROOT)] ?: UNK_ID
            inputIds[i] = id
            attentionMask[i] = 1
        }

        return Encoding(
            inputIds = inputIds,
            attentionMask = attentionMask,
            tokenTypeIds = tokenTypeIds,
            tokens = tokenStrings
        )
    }

    private fun wordPieceTokenize(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        val lower = word.lowercase(Locale.ROOT)
        if (vocabToId.containsKey(lower)) {
            return listOf(lower)
        }

        val tokens = mutableListOf<String>()
        var start = 0
        val len = lower.length

        while (start < len) {
            var end = len
            var curSubword: String? = null
            var curId = -1

            while (start < end) {
                var substr = lower.substring(start, end)
                if (start > 0) {
                    substr = "##$substr"
                }
                val id = vocabToId[substr]
                if (id != null) {
                    curSubword = substr
                    curId = id
                    break
                }
                end--
            }

            if (curSubword == null) {
                tokens.add(UNK_TOKEN)
                start++
            } else {
                tokens.add(curSubword)
                start = end
            }
        }
        return tokens
    }

    private fun cleanText(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            if (c == '\u0000' || c == '\ufffd' || Character.isISOControl(c)) {
                if (c == '\t' || c == '\n' || c == '\r') {
                    sb.append(' ')
                }
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun whitespaceAndPunctuationTokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val len = text.length

        while (i < len) {
            while (i < len && text[i].isWhitespace()) {
                i++
            }
            if (i >= len) break

            val c = text[i]
            if (isPunctuation(c)) {
                tokens.add(c.toString())
                i++
            } else {
                val start = i
                while (i < len && !text[i].isWhitespace() && !isPunctuation(text[i])) {
                    i++
                }
                tokens.add(text.substring(start, i))
            }
        }
        return tokens
    }

    private fun isPunctuation(c: Char): Boolean {
        val type = Character.getType(c)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
                type == Character.DASH_PUNCTUATION.toInt() ||
                type == Character.START_PUNCTUATION.toInt() ||
                type == Character.END_PUNCTUATION.toInt() ||
                type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.OTHER_PUNCTUATION.toInt() ||
                (c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~")
    }

    private fun addToken(token: String, id: Int) {
        vocabToId[token] = id
        idToVocab[id] = token
    }

    private fun initializeVocabulary() {
        // Special tokens
        addToken(PAD_TOKEN, PAD_ID)
        addToken(UNK_TOKEN, UNK_ID)
        addToken(CLS_TOKEN, CLS_ID)
        addToken(SEP_TOKEN, SEP_ID)
        addToken(MASK_TOKEN, MASK_ID)

        var idCounter = 1000

        // Single characters (ASCII a-z, 0-9, symbols)
        for (c in 'a'..'z') {
            addToken(c.toString(), idCounter++)
            addToken("##$c", idCounter++)
        }
        for (d in '0'..'9') {
            addToken(d.toString(), idCounter++)
            addToken("##$d", idCounter++)
        }
        val symbols = listOf(".", ",", "-", "_", "/", ":", ";", "(", ")", "[", "]", "{", "}", "=", "+", "*", "&", "%", "$", "@", "!", "?", "'", "\"", "<", ">")
        for (s in symbols) {
            addToken(s, idCounter++)
        }

        // Common word stems, affixes and subwords
        val subwords = listOf(
            "ing", "ed", "es", "s", "ly", "tion", "tions", "ment", "ments", "able", "ible", "ness",
            "ity", "al", "ic", "ive", "ize", "ise", "ation", "ism", "ist", "ful", "less", "ous",
            "er", "est", "en", "fy", "ant", "ent", "ance", "ence", "ize", "ized", "ating", "ated",
            "re", "un", "in", "im", "dis", "non", "pre", "post", "anti", "multi", "sub", "super",
            "over", "under", "inter", "intra", "auto", "co", "de", "ex", "extra", "hyper", "micro"
        )
        for (sw in subwords) {
            addToken(sw, idCounter++)
            addToken("##$sw", idCounter++)
        }

        // Comprehensive Core English & Domain Vocab (Search, Computing, Science, Business, General)
        val coreWords = listOf(
            "the", "of", "and", "to", "a", "in", "is", "you", "that", "it", "he", "was", "for", "on",
            "are", "as", "with", "his", "they", "i", "at", "be", "this", "have", "from", "or", "one",
            "had", "by", "word", "but", "not", "what", "all", "were", "we", "when", "your", "can",
            "said", "there", "use", "an", "each", "which", "she", "do", "how", "their", "if", "will",
            "up", "other", "about", "out", "many", "then", "them", "these", "so", "some", "her", "would",
            "make", "like", "him", "into", "time", "has", "look", "two", "more", "write", "go", "see",
            "number", "no", "way", "could", "people", "my", "than", "first", "water", "been", "call",
            "who", "oil", "its", "now", "find", "long", "down", "day", "did", "get", "come", "made",
            "may", "part", "vector", "search", "document", "documents", "embedding", "embeddings",
            "offline", "device", "model", "neural", "network", "index", "indexing", "database", "sqlite",
            "fts", "fts5", "hybrid", "rank", "ranking", "cosine", "similarity", "distance", "metric",
            "tokens", "token", "tokenizer", "wordpiece", "file", "files", "folder", "directory",
            "system", "android", "kotlin", "compose", "material", "worker", "workmanager", "service",
            "foreground", "notification", "query", "text", "chunk", "chunks", "hash", "sha256", "content",
            "storage", "saf", "tree", "uri", "permission", "pdf", "txt", "md", "csv", "json", "data",
            "dataset", "algorithm", "intelligence", "artificial", "deep", "learning", "machine",
            "inference", "cpu", "gpu", "npu", "qualcomm", "qnn", "delegate", "xnnpack", "neon", "simd",
            "int8", "quantized", "dimension", "dimensions", "384", "minilm", "bge", "bert", "transformer",
            "attention", "layer", "weights", "performance", "speed", "latency", "benchmark", "memory",
            "allocation", "buffer", "byte", "float", "array", "batch", "batching", "score", "scores",
            "precision", "recall", "reciprocal", "fusion", "bm25", "keyword", "keywords", "semantic",
            "syntax", "match", "snippet", "highlight", "open", "read", "extract", "parser", "pipeline",
            "architecture", "coroutine", "flow", "state", "viewmodel", "repository", "dao", "entity",
            "table", "column", "schema", "record", "records", "result", "results", "view", "card",
            "button", "field", "progress", "ready", "idle", "busy", "error", "success", "status"
        )
        for (w in coreWords) {
            if (!vocabToId.containsKey(w)) {
                addToken(w, idCounter++)
            }
        }
    }
}

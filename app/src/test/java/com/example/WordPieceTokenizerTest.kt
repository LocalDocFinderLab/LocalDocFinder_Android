package com.example

import com.example.engine.embedding.WordPieceTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expected ids in `resources/tokenizer/cases.tsv` were produced by Hugging Face's `BertTokenizer`
 * (do_lower_case=True, max_length=16, truncation) over `resources/tokenizer/vocab.txt`, so these tests
 * check that our Kotlin tokenizer is id-for-id identical to the reference the models were trained with.
 */
class WordPieceTokenizerTest {

    private val tokenizer: WordPieceTokenizer by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("tokenizer/vocab.txt")!!
        stream.use { WordPieceTokenizer.fromVocab(it) }
    }

    private fun unescape(s: String) = s.replace("\\t", "\t").replace("\\n", "\n")

    @Test
    fun `matches Hugging Face BertTokenizer on every reference case`() {
        val lines = javaClass.classLoader!!.getResourceAsStream("tokenizer/cases.tsv")!!
            .bufferedReader(Charsets.UTF_8).readLines()
        assertTrue("reference cases missing", lines.size >= 19)
        for (line in lines) {
            val tab = line.lastIndexOf('\t')
            val text = unescape(line.substring(0, tab))
            val expected = line.substring(tab + 1).trim().split(' ').map { it.toInt() }
            val actual = tokenizer.encode(text, maxLength = 16).ids.toList()
            assertEquals("text=<$text>", expected, actual)
        }
    }

    @Test
    fun `encoding starts with CLS ends with SEP and mask is all ones`() {
        val enc = tokenizer.encode("hello world", 32)
        assertEquals(tokenizer.clsId, enc.ids.first())
        assertEquals(tokenizer.sepId, enc.ids.last())
        assertTrue(enc.attentionMask.all { it == 1 })
        assertEquals(enc.ids.size, enc.attentionMask.size)
    }

    @Test
    fun `truncation keeps CLS and SEP within maxLength`() {
        val enc = tokenizer.encode("hello ".repeat(100), 10)
        assertEquals(10, enc.length)
        assertEquals(tokenizer.clsId, enc.ids.first())
        assertEquals(tokenizer.sepId, enc.ids.last())
    }

    @Test
    fun `wordpiece splits and unknown words become UNK`() {
        assertEquals(listOf("un", "##aff", "##able"), tokenizer.tokenize("unaffable"))
        assertEquals(listOf("[UNK]"), tokenizer.tokenize("ش")) // not in vocab
        assertEquals(listOf("hello", ",", "world", "!"), tokenizer.tokenize("Hello, World!"))
    }

    @Test
    fun `accents are stripped and text is lower-cased`() {
        assertEquals(tokenizer.tokenize("cafe resume"), tokenizer.tokenize("Café RÉSUMÉ"))
    }

    @Test
    fun `special-token text inside a document is plain text not a control token`() {
        // Hugging Face would turn a literal "[SEP]" in the text into the real separator id. Document contents
        // must never be able to inject control tokens into the model, so we tokenise it as ordinary text.
        val ids = tokenizer.encode("a [SEP] b", 32).ids
        assertEquals(1, ids.count { it == tokenizer.sepId }) // only the real trailing [SEP]
        assertEquals(1, ids.count { it == tokenizer.clsId })
    }

    @Test
    fun `empty text still produces CLS SEP`() {
        val enc = tokenizer.encode("", 8)
        assertEquals(listOf(tokenizer.clsId, tokenizer.sepId), enc.ids.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `vocab without special tokens is rejected`() {
        WordPieceTokenizer(mapOf("hello" to 0))
    }
}

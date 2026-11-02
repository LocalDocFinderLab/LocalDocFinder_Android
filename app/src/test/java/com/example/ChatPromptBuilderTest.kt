package com.example

import com.example.engine.llm.ChatModel
import com.example.engine.llm.ChatPromptBuilder
import com.example.engine.llm.ChatSource
import com.example.engine.llm.ChatTemplate
import com.example.engine.llm.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptBuilderTest {

    private fun source(n: Int, text: String, name: String = "file$n.pdf", page: String? = null) =
        ChatSource(n, n.toLong(), "content://doc/$n", name, page, text)

    @Test
    fun searchQueryDropsQuestionAndFillerWords() {
        assertEquals("rent march", ChatPromptBuilder.searchQueryFor("What was my rent in March?"))
        assertEquals("q3_report.pdf", ChatPromptBuilder.searchQueryFor("summarize q3_report.pdf"))
        // Nothing meaningful left: the question itself is searched
        assertEquals("what is it?", ChatPromptBuilder.searchQueryFor("what is it?"))
    }

    @Test
    fun chatMlPromptHasExcerptsQuestionAndOpenAssistantTurn() {
        val prompt = ChatPromptBuilder.build(
            ChatTemplate.CHATML, 1280, "When does the lease end?",
            listOf(source(1, "The lease ends on 31 May 2027.", "lease.pdf", "p. 2"))
        )
        val text = prompt.text
        assertTrue(text.startsWith("<|im_start|>system\n"))
        assertTrue(text.contains("[1] lease.pdf (p. 2)\nThe lease ends on 31 May 2027."))
        assertTrue(text.contains("Question: When does the lease end?"))
        assertTrue(text.endsWith("<|im_start|>assistant\n"))
        assertEquals(1, prompt.sourcesUsed.size)
    }

    @Test
    fun promptFitsTheContextWindow() {
        val long = "Payment of the invoice is due within thirty days. ".repeat(60) // ~3000 chars each
        val sources = (1..5).map { source(it, long) }
        for (ctx in listOf(1280, 2048, 4096)) {
            val prompt = ChatPromptBuilder.build(ChatTemplate.CHATML, ctx, "When is payment due?", sources)
            val budget = ctx - ChatPromptBuilder.answerReserve(ctx)
            assertTrue("ctx $ctx: ${ChatPromptBuilder.estimateTokens(prompt.text)} > $budget",
                ChatPromptBuilder.estimateTokens(prompt.text) <= budget)
            assertTrue(prompt.sourcesUsed.isNotEmpty())
        }
        // A bigger window fits more excerpts
        val small = ChatPromptBuilder.build(ChatTemplate.CHATML, 1280, "q", sources).sourcesUsed.size
        val big = ChatPromptBuilder.build(ChatTemplate.CHATML, 4096, "q", sources).sourcesUsed.size
        assertTrue(big > small)
    }

    @Test
    fun usedSourcesAreNumberedInOrder() {
        val prompt = ChatPromptBuilder.build(
            ChatTemplate.CHATML, 4096, "q",
            listOf(source(7, "first excerpt text"), source(9, "second excerpt text"))
        )
        assertEquals(listOf(1, 2), prompt.sourcesUsed.map { it.number })
        assertTrue(prompt.text.contains("[2] file9.pdf"))
    }

    @Test
    fun noSourcesTellsTheModelNothingMatched() {
        val prompt = ChatPromptBuilder.build(ChatTemplate.CHATML, 1280, "Hello?", emptyList())
        assertTrue(prompt.text.contains(ChatPromptBuilder.NO_SOURCES_PROMPT))
        assertFalse(prompt.text.contains("Excerpts from my files"))
    }

    @Test
    fun previousTurnIsKeptWhenItFits() {
        val prompt = ChatPromptBuilder.build(
            ChatTemplate.ZEPHYR, 2048, "And in April?",
            listOf(source(1, "Rent: March 900, April 950.")),
            history = listOf(ChatTurn("What was my rent in March?", "900 [1]"))
        )
        assertTrue(prompt.text.contains("<|user|>\nWhat was my rent in March?</s>\n<|assistant|>\n900 [1]</s>"))
        assertTrue(prompt.text.endsWith("<|assistant|>\n"))
    }

    @Test
    fun deepSeekPromptHasNoSystemTurnAndOpensThinking() {
        val prompt = ChatPromptBuilder.build(ChatTemplate.DEEPSEEK_R1, 1280, "Why?", listOf(source(1, "Because of the rain.")))
        assertTrue(prompt.text.startsWith("<｜User｜>"))
        assertTrue(prompt.text.endsWith("<｜Assistant｜><think>\n"))
    }

    @Test
    fun parseAnswerSeparatesThinkingAndStripsTemplateTokens() {
        val r1 = ChatPromptBuilder.parseAnswer("Let me check the excerpts.</think>\n\nIt ends in May [1].<｜end▁of▁sentence｜>", ChatTemplate.DEEPSEEK_R1)
        assertEquals("Let me check the excerpts.", r1.thinking)
        assertEquals("It ends in May [1].", r1.text)

        val stillThinking = ChatPromptBuilder.parseAnswer("Hmm, the user asks", ChatTemplate.DEEPSEEK_R1)
        assertEquals("Hmm, the user asks", stillThinking.thinking)
        assertEquals("", stillThinking.text)

        val qwen = ChatPromptBuilder.parseAnswer("31 May 2027 [1].<|im_end|>", ChatTemplate.CHATML)
        assertEquals("", qwen.thinking)
        assertEquals("31 May 2027 [1].", qwen.text)
    }

    @Test
    fun shortenCutsAtSentenceOrWord() {
        val text = "First sentence here. Second sentence is longer than the limit allows."
        assertEquals("First sentence here.…", ChatPromptBuilder.shorten(text, 40))
        assertEquals(text, ChatPromptBuilder.shorten(text, 500))
    }

    @Test
    fun rankChunksPrefersChunksWithQuestionWords() {
        val chunks = listOf("Introduction to the report", "Total amount due: 120 EUR", "Appendix with the amount table")
        assertEquals(listOf(1, 2), ChatPromptBuilder.rankChunks("What is the total amount due?", chunks, 2))
        // No matching words ("summarize this"): the opening chunks, in order
        assertEquals(listOf(0, 1), ChatPromptBuilder.rankChunks("summarize this", chunks, 2))
    }

    @Test
    fun modelCatalogIsConsistent() {
        assertEquals(ChatModel.QWEN25_05B, ChatModel.DEFAULT)
        assertEquals(ChatModel.entries.size, ChatModel.entries.map { it.id }.toSet().size)
        for (m in ChatModel.entries) {
            assertTrue(m.fileName.endsWith(".task"))
            assertTrue(m.repo.startsWith("litert-community/"))
            assertTrue(m.downloadUrl().startsWith("https://huggingface.co/${m.repo}/resolve/main/"))
            assertEquals(m, ChatModel.fromId(m.id))
        }
    }

    @Test
    fun contextTokensAreReadFromTheFileName() {
        assertEquals(1280, ChatModel.contextTokensOf("Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"))
        assertEquals(4096, ChatModel.contextTokensOf("model_q8_ekv4096.task"))
        assertEquals(ChatModel.DEFAULT_CONTEXT_TOKENS, ChatModel.contextTokensOf("gemma.task"))
    }

    @Test
    fun pickTaskFilePrefersExpectedThenQ8SmallestUsableContext() {
        val files = listOf(
            "README.md",
            "Qwen_q4_ekv1280.task",
            "Qwen_q8_ekv4096.task",
            "Qwen_q8_ekv1280.task",
            "Qwen_q8_ekv512.task",
            "Qwen_gpu_q8_ekv1280.task",
            "Qwen_q8.litertlm"
        )
        assertEquals("Qwen_q4_ekv1280.task", ChatModel.pickTaskFile(files, "Qwen_q4_ekv1280.task"))
        assertEquals("Qwen_q8_ekv1280.task", ChatModel.pickTaskFile(files, "renamed.task"))
        assertEquals(null, ChatModel.pickTaskFile(listOf("README.md", "x.litertlm"), "renamed.task"))
    }
}

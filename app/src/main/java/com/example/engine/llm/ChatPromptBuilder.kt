package com.example.engine.llm

import java.util.Locale

/** One excerpt from the user's files that an answer is based on. Shown under the answer as `[n] file · p. 3`. */
data class ChatSource(
    val number: Int,
    val chunkId: Long,
    val fileUri: String,
    val fileName: String,
    val pageLabel: String?,
    val text: String
)

/** A finished question/answer pair, used to give the model the recent conversation. */
data class ChatTurn(val question: String, val answer: String)

/**
 * Builds the prompt for a question about the user's files (retrieval-augmented generation), within the small
 * context window of an on-device model. Pure Kotlin, no Android types, so it is unit-tested.
 *
 * The window (prompt + answer, e.g. 1280 tokens) is split into: room for the answer, the instructions and the
 * question, then as many excerpts as fit (best first, long ones shortened), then the previous turn if room is left.
 */
object ChatPromptBuilder {

    /** Conservative: on-device tokenizers average ~4 characters per token for English, less for numbers/code. */
    private const val CHARS_PER_TOKEN = 3.2

    /** Excerpts shorter than this after shortening are not worth including. */
    private const val MIN_EXCERPT_CHARS = 160

    const val SYSTEM_PROMPT =
        "You are LocalDoc Assistant, running offline on the user's phone. Answer the question using only the " +
            "numbered excerpts from the user's own files. Cite the excerpts you used like [1] or [2]. If the " +
            "excerpts do not contain the answer, say that you could not find it in the files. Be concise."

    const val NO_SOURCES_PROMPT =
        "You are LocalDoc Assistant, running offline on the user's phone. None of the user's indexed files " +
            "matched this question. Say so in one sentence, then answer briefly from general knowledge if you can."

    private val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "for", "from", "by", "with", "about", "into",
        "is", "are", "was", "were", "be", "been", "being", "am", "do", "does", "did", "done", "have", "has", "had",
        "what", "whats", "which", "who", "whom", "whose", "when", "where", "why", "how", "much", "many",
        "can", "could", "would", "should", "will", "shall", "may", "might", "must",
        "i", "me", "my", "mine", "we", "our", "us", "you", "your", "it", "its", "this", "that", "these", "those",
        "there", "their", "they", "them", "he", "she", "his", "her", "please", "tell", "show", "find", "give",
        "list", "explain", "summarize", "summarise", "summary", "describe", "say", "says", "said", "mention",
        "mentioned", "according", "file", "files", "document", "documents", "doc", "docs", "any", "all", "some",
        "if", "so", "than", "then", "also", "just", "only", "not", "no", "yes", "get", "got", "know", "need"
    )

    /**
     * Turns a chat question into a search query: drops question and filler words so the keyword search looks for
     * the words that carry the meaning ("What was my rent in March?" -> "rent march"). The vector search still
     * sees the meaning. Falls back to the question itself when nothing would be left.
     */
    fun searchQueryFor(question: String): String {
        val words = question.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s._-]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim('.', '_', '-') }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .distinct()
        return if (words.isEmpty()) question.trim() else words.joinToString(" ")
    }

    /**
     * For a question about one file: indexes of the [chunks] that share the most question words, best first.
     * When no words match (e.g. "summarize this"), the file's opening chunks are used, in order.
     */
    fun rankChunks(question: String, chunks: List<String>, limit: Int): List<Int> {
        val words = searchQueryFor(question).split(' ').filter { it.length >= 2 }.toSet()
        val scored = chunks.mapIndexed { i, text ->
            val lower = text.lowercase(Locale.ROOT)
            i to words.count { w -> lower.contains(w) }
        }
        val hits = scored.filter { it.second > 0 }.sortedWith(compareBy({ -it.second }, { it.first }))
        return (if (hits.isEmpty()) scored else hits).take(limit).map { it.first }
    }

    fun estimateTokens(text: String): Int = Math.ceil(text.length / CHARS_PER_TOKEN).toInt()

    /** Tokens kept free for the answer. */
    fun answerReserve(contextTokens: Int): Int = (contextTokens / 3).coerceIn(128, 512)

    data class Prompt(val text: String, val sourcesUsed: List<ChatSource>)

    fun build(
        template: ChatTemplate,
        contextTokens: Int,
        question: String,
        sources: List<ChatSource>,
        history: List<ChatTurn> = emptyList()
    ): Prompt {
        val q = question.trim()
        val system = if (sources.isEmpty()) NO_SOURCES_PROMPT else SYSTEM_PROMPT
        // Everything but the excerpts and history, plus a few tokens of slack for the rounding of the estimate.
        val overhead = estimateTokens(format(template, system, emptyList(), userMessage(q, "x"), q)) + 8
        var budgetChars = ((contextTokens - answerReserve(contextTokens) - overhead) * CHARS_PER_TOKEN).toInt()

        // Excerpts, best first. Each one gets at most what is left, the last one is shortened to fit.
        val used = ArrayList<ChatSource>()
        val context = StringBuilder()
        for (source in sources) {
            val header = "[${used.size + 1}] ${source.fileName}${source.pageLabel?.let { " ($it)" } ?: ""}\n"
            val room = budgetChars - header.length - 2
            if (room < MIN_EXCERPT_CHARS) break
            val body = shorten(source.text.trim(), room)
            if (body.length < MIN_EXCERPT_CHARS && body.length < source.text.trim().length) break
            val renumbered = source.copy(number = used.size + 1)
            used.add(renumbered)
            context.append(header).append(body).append("\n\n")
            budgetChars -= header.length + body.length + 2
        }

        // The previous turn, if it still fits: lets "and in April?" follow "what was my rent in March?".
        val keptHistory = ArrayList<ChatTurn>()
        for (turn in history.asReversed().take(2)) {
            val cost = turn.question.length + turn.answer.length + 40
            if (cost > budgetChars) break
            keptHistory.add(0, turn)
            budgetChars -= cost
        }

        val message = if (used.isEmpty()) q else userMessage(q, context.toString().trimEnd())
        return Prompt(format(template, system, keptHistory, message, q), used)
    }

    private fun userMessage(question: String, excerpts: String) = "Excerpts from my files:\n\n$excerpts\n\nQuestion: $question"

    private fun format(template: ChatTemplate, system: String, history: List<ChatTurn>, userMessage: String, question: String): String {
        val message = userMessage.ifEmpty { question }
        val sb = StringBuilder()
        when (template) {
            ChatTemplate.CHATML -> {
                sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
                for (t in history) {
                    sb.append("<|im_start|>user\n").append(t.question).append("<|im_end|>\n")
                    sb.append("<|im_start|>assistant\n").append(t.answer).append("<|im_end|>\n")
                }
                sb.append("<|im_start|>user\n").append(message).append("<|im_end|>\n<|im_start|>assistant\n")
            }
            ChatTemplate.ZEPHYR -> {
                sb.append("<|system|>\n").append(system).append("</s>\n")
                for (t in history) {
                    sb.append("<|user|>\n").append(t.question).append("</s>\n")
                    sb.append("<|assistant|>\n").append(t.answer).append("</s>\n")
                }
                sb.append("<|user|>\n").append(message).append("</s>\n<|assistant|>\n")
            }
            ChatTemplate.DEEPSEEK_R1 -> {
                // R1 distills work best without a system turn: the instructions go into the first user message.
                for ((i, t) in history.withIndex()) {
                    sb.append("<｜User｜>")
                    if (i == 0) sb.append(system).append("\n\n")
                    sb.append(t.question).append("<｜Assistant｜>").append(t.answer).append("<｜end▁of▁sentence｜>")
                }
                sb.append("<｜User｜>")
                if (history.isEmpty()) sb.append(system).append("\n\n")
                sb.append(message).append("<｜Assistant｜><think>\n")
            }
        }
        return sb.toString()
    }

    /** Cuts [text] to at most [maxChars], at a sentence or word boundary when possible. */
    internal fun shorten(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        if (maxChars <= 1) return ""
        val cut = text.substring(0, maxChars - 1)
        val sentence = cut.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
        val end = when {
            sentence >= maxChars / 3 -> sentence + 1
            cut.lastIndexOf(' ') >= maxChars / 2 -> cut.lastIndexOf(' ')
            else -> cut.length
        }
        return cut.substring(0, end).trimEnd() + "…"
    }

    /** The answer split into the model's visible reasoning (DeepSeek-R1's `<think>` block) and the answer itself. */
    data class Answer(val thinking: String, val text: String)

    private val TEMPLATE_TOKENS = listOf(
        "<|im_end|>", "<|im_start|>", "<|endoftext|>", "</s>", "<|assistant|>", "<|user|>", "<|system|>",
        "<｜end▁of▁sentence｜>", "<｜Assistant｜>", "<｜User｜>", "<eos>", "<end_of_turn>"
    )

    /** Cleans streamed model output for display (template tokens removed, `<think>` separated). */
    fun parseAnswer(raw: String, template: ChatTemplate): Answer {
        var text = raw
        for (token in TEMPLATE_TOKENS) text = text.replace(token, "")
        if (template == ChatTemplate.DEEPSEEK_R1 || "<think>" in text || "</think>" in text) {
            text = text.replace("<think>", "")
            val end = text.indexOf("</think>")
            return if (end >= 0) {
                Answer(text.substring(0, end).trim(), text.substring(end + "</think>".length).trim())
            } else if (template == ChatTemplate.DEEPSEEK_R1) {
                Answer(text.trim(), "")
            } else {
                Answer("", text.trim())
            }
        }
        return Answer("", text.trim())
    }
}

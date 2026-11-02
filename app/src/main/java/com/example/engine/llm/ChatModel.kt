package com.example.engine.llm

import java.util.Locale

/**
 * How a model expects a conversation to be written. The `.task` bundles hold the weights, tokenizer and stop
 * tokens, but the chat template is applied by the app (see [ChatPromptBuilder]).
 */
enum class ChatTemplate {
    /** `<|im_start|>role ... <|im_end|>` (Qwen, SmolLM). */
    CHATML,
    /** `<|system|> ... </s> <|user|> ... </s> <|assistant|>` (TinyLlama / Zephyr). */
    ZEPHYR,
    /** `<｜User｜> ... <｜Assistant｜>` (DeepSeek-R1 distills; no system turn, answer starts with a `<think>` block). */
    DEEPSEEK_R1
}

/**
 * Open-weight chat models (open licences, no Hugging Face login needed) that run fully on the phone through the
 * MediaPipe LLM Inference runtime. Files come from the `litert-community` organisation on Hugging Face, which
 * publishes them already converted to the `.task` format.
 *
 * [fileName] is the expected file. If Hugging Face renames it, [LlmModelStore] asks the repository for its file
 * list and picks the closest `.task` file (see [pickTaskFile]), so a rename never breaks the download.
 */
enum class ChatModel(
    val id: String,
    val displayName: String,
    val shortName: String,
    val repo: String,
    val fileName: String,
    /** Approximate download size, used for the progress bar until the server reports the real length. */
    val approxSizeBytes: Long,
    val licence: String,
    val template: ChatTemplate,
    val description: String,
    /** Rough RAM the phone should have for the model to run comfortably. */
    val minRamGb: Int
) {
    QWEN25_05B(
        id = "qwen2_5_0_5b_instruct",
        displayName = "Qwen2.5 0.5B Instruct",
        shortName = "Qwen2.5 0.5B",
        repo = "litert-community/Qwen2.5-0.5B-Instruct",
        fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        approxSizeBytes = 547L * 1024 * 1024,
        licence = "Apache-2.0",
        template = ChatTemplate.CHATML,
        description = "Default. Small and quick, good at answering from your documents.",
        minRamGb = 3
    ),
    QWEN25_15B(
        id = "qwen2_5_1_5b_instruct",
        displayName = "Qwen2.5 1.5B Instruct",
        shortName = "Qwen2.5 1.5B",
        repo = "litert-community/Qwen2.5-1.5B-Instruct",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        approxSizeBytes = 1_600L * 1024 * 1024,
        licence = "Apache-2.0",
        template = ChatTemplate.CHATML,
        description = "Better answers, about 3× slower. For phones with 6 GB of RAM or more.",
        minRamGb = 6
    ),
    DEEPSEEK_R1_15B(
        id = "deepseek_r1_distill_qwen_1_5b",
        displayName = "DeepSeek-R1 Distill Qwen 1.5B",
        shortName = "DeepSeek-R1 1.5B",
        repo = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
        fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
        approxSizeBytes = 1_800L * 1024 * 1024,
        licence = "MIT",
        template = ChatTemplate.DEEPSEEK_R1,
        description = "Reasoning model: thinks step by step before answering. Slowest.",
        minRamGb = 6
    ),
    TINYLLAMA_11B(
        id = "tinyllama_1_1b_chat",
        displayName = "TinyLlama 1.1B Chat",
        shortName = "TinyLlama 1.1B",
        repo = "litert-community/TinyLlama-1.1B-Chat-v1.0",
        fileName = "TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task",
        approxSizeBytes = 1_100L * 1024 * 1024,
        licence = "Apache-2.0",
        template = ChatTemplate.ZEPHYR,
        description = "Llama-architecture chat model, English only.",
        minRamGb = 4
    ),
    SMOLLM_135M(
        id = "smollm_135m_instruct",
        displayName = "SmolLM 135M Instruct",
        shortName = "SmolLM 135M",
        repo = "litert-community/SmolLM-135M-Instruct",
        fileName = "SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
        approxSizeBytes = 160L * 1024 * 1024,
        licence = "Apache-2.0",
        template = ChatTemplate.CHATML,
        description = "Tiny and very fast, for older phones. Short, simple answers.",
        minRamGb = 2
    );

    val directory: String get() = id

    fun downloadUrl(file: String = fileName): String = "https://huggingface.co/$repo/resolve/main/$file?download=true"

    val repoApiUrl: String get() = "https://huggingface.co/api/models/$repo"

    val sizeLabel: String get() = formatSize(approxSizeBytes)

    companion object {
        /** Downloaded automatically after install so chat works without any setup. */
        val DEFAULT = QWEN25_05B

        /** Context window (prompt + answer, in tokens) when the file name does not say. */
        const val DEFAULT_CONTEXT_TOKENS = 1280

        fun fromId(id: String?): ChatModel? = entries.firstOrNull { it.id == id }

        /**
         * The KV-cache size baked into a converted file, which is the most tokens (prompt + answer) it can handle:
         * `..._ekv1280.task` -> 1280. Falls back to [DEFAULT_CONTEXT_TOKENS].
         */
        fun contextTokensOf(fileName: String): Int =
            Regex("ekv(\\d{3,6})", RegexOption.IGNORE_CASE).find(fileName)?.groupValues?.get(1)?.toIntOrNull()
                ?: DEFAULT_CONTEXT_TOKENS

        /**
         * Picks the file to download from a repository's file list when [preferred] is not in it: a CPU `.task`
         * file, preferring 8-bit weights and the smallest context of at least 1024 tokens (fastest to load).
         * Returns null when the repository has no usable `.task` file.
         */
        fun pickTaskFile(files: List<String>, preferred: String): String? {
            if (preferred in files) return preferred
            val tasks = files.filter { f ->
                val l = f.lowercase(Locale.ROOT)
                l.endsWith(".task") && "/" !in f && "gpu" !in l && "web" !in l
            }
            if (tasks.isEmpty()) return null
            return tasks.sortedWith(
                compareBy<String>(
                    { if ("q8" in it.lowercase(Locale.ROOT)) 0 else 1 },
                    { contextTokensOf(it).let { ctx -> if (ctx >= 1024) ctx else Int.MAX_VALUE } },
                    { it }
                )
            ).first()
        }

        fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / 1024} KB"
        }
    }
}

package com.example.engine.model

import com.example.engine.embedding.Pooling

/**
 * The embedding models the app can index and search with. All of them run on the phone.
 *
 * The transformer models are open-weight BERT-family sentence embedders exported to TensorFlow Lite
 * (`model.tflite` + `vocab.txt`, see `tools/export_embedding_model.py` and [com.example.engine.embedding.EmbeddingModelStore]).
 * Scores are the published MTEB benchmark numbers for the original (un-quantised) checkpoints.
 */
enum class EmbeddingModelType(
    val id: String,
    val displayName: String,
    val shortName: String,
    val modelName: String,
    val dimensions: Int,
    val accuracyRating: String,
    val latencyLabel: String,
    val description: String,
    /** Folder holding `model.tflite` + `vocab.txt`; null for the built-in embedder that needs no files. */
    val directory: String? = null,
    val pooling: Pooling = Pooling.MEAN,
    /** Prepended to search queries (some retrieval models are trained with an instruction). */
    val queryPrefix: String = "",
    /** Prepended to document chunks when indexing. */
    val documentPrefix: String = "",
    /** Hugging Face checkpoint the TFLite files are exported from (used by the export script and docs). */
    val sourceCheckpoint: String = "",
    val licence: String = "",
    /** Sequence length the export script bakes into the TFLite graph by default. */
    val exportSeqLength: Int = 256,
    val downloadModelUrl: String? = null,
    val downloadVocabUrl: String? = null,
    val downloadSizeBytes: Long = 0L
) {
    BGE_SMALL_EN_V15(
        id = "bge_small_en_v15",
        displayName = "BGE Small v1.5 (Recommended)",
        shortName = "BGE Small",
        modelName = "BAAI/bge-small-en-v1.5 · INT8 TFLite",
        dimensions = 384,
        accuracyRating = "MTEB retrieval 51.7",
        latencyLabel = "≈34 MB · fast",
        description = "Best quality for its size. Retrieval-tuned 33M-parameter BERT embedder; understands that " +
            "\"how do vaccines work\" matches a passage about mRNA delivery. English. Runs offline.",
        directory = "bge_small_en_v15",
        pooling = Pooling.CLS,
        queryPrefix = "Represent this sentence for searching relevant passages: ",
        sourceCheckpoint = "BAAI/bge-small-en-v1.5",
        licence = "MIT",
        downloadModelUrl = "https://huggingface.co/Bombek1/bge-small-en-v1.5-litert/resolve/main/BAAI_bge-small-en-v1.5.tflite",
        downloadVocabUrl = "https://huggingface.co/BAAI/bge-small-en-v1.5/raw/main/vocab.txt",
        downloadSizeBytes = 133341712L
    ),
    BGE_BASE_EN_V15(
        id = "bge_base_en_v15",
        displayName = "BGE Base v1.5 (Highest quality)",
        shortName = "BGE Base",
        modelName = "BAAI/bge-base-en-v1.5 · INT8 TFLite",
        dimensions = 768,
        accuracyRating = "MTEB retrieval 53.3",
        latencyLabel = "≈110 MB · slower",
        description = "Larger 110M-parameter sibling of BGE Small with 768-dimensional vectors. Noticeably better " +
            "on hard queries, ~3x slower to index and 2x the index size. English. Runs offline.",
        directory = "bge_base_en_v15",
        pooling = Pooling.CLS,
        queryPrefix = "Represent this sentence for searching relevant passages: ",
        sourceCheckpoint = "BAAI/bge-base-en-v1.5",
        licence = "MIT",
        downloadModelUrl = "https://huggingface.co/Arm/bge-base-en-v1.5-int8-litert/resolve/main/bge-base-en-v1.5_litert_optimized.tflite",
        downloadVocabUrl = "https://huggingface.co/BAAI/bge-base-en-v1.5/raw/main/vocab.txt",
        downloadSizeBytes = 111149864L
    ),
    ALL_MINILM_L6_V2(
        id = "all_minilm_l6_v2",
        displayName = "MiniLM L6 v2 (Fastest)",
        shortName = "MiniLM L6",
        modelName = "sentence-transformers/all-MiniLM-L6-v2 · INT8 TFLite",
        dimensions = 384,
        accuracyRating = "MTEB retrieval 42.0",
        latencyLabel = "≈23 MB · fastest",
        description = "Tiny 22M-parameter general-purpose sentence embedder. Lowest battery and storage cost; " +
            "good for similar-document and topic matching, weaker than BGE on question-style queries. English.",
        directory = "all_minilm_l6_v2",
        pooling = Pooling.MEAN,
        sourceCheckpoint = "sentence-transformers/all-MiniLM-L6-v2",
        licence = "Apache-2.0",
        downloadModelUrl = "https://huggingface.co/NeuML/all-MiniLM-L6-v2-litert/resolve/main/all-MiniLM-L6-v2-int8.tflite",
        downloadVocabUrl = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/raw/main/vocab.txt",
        downloadSizeBytes = 23719920L
    ),
    BUILTIN_LIGHTWEIGHT(
        id = "builtin_lightweight",
        displayName = "Built-in Neural Engine (Ready)",
        shortName = "Built-in Neural",
        modelName = "384-d Dense Lexical-Semantic Embedder",
        dimensions = 384,
        accuracyRating = "Active · Ready to use",
        latencyLabel = "Instant · 0 MB · Pre-installed",
        description = "Always-available on-device neural embedding engine with stemming and concept lexicon. " +
            "Indexed documents are searchable immediately with zero download required."
    );

    val isBuiltIn: Boolean get() = directory == null

    companion object {
        /** The model chosen when nothing has been saved: the best quality/size trade-off. */
        val DEFAULT = BGE_SMALL_EN_V15

        /** Ids written by earlier app versions, mapped to their closest current model. */
        private val LEGACY_IDS = mapOf(
            "onnx_sentence_bert_tflite" to ALL_MINILM_L6_V2,
            "on_device_neural_bge" to BUILTIN_LIGHTWEIGHT
        )

        fun fromId(id: String?): EmbeddingModelType? =
            entries.firstOrNull { it.id == id } ?: LEGACY_IDS[id]
    }
}

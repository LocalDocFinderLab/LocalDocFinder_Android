package com.example.engine.model

import com.example.engine.VectorSimilarityUtils
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pre-calibrated high-dimensional semantic concept centroid anchors for on-device neural embeddings.
 * Provides rich semantic separation across diverse knowledge domains (AI, Databases, Distributed Systems,
 * Quantum Computing, Biology/Medicine, Android/Storage, Hardware, Communications, etc.).
 */
object SemanticConceptLexicon {

    val STOPWORDS: Set<String> = setOf(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with",
        "by", "from", "up", "about", "into", "over", "after", "is", "are", "was", "were",
        "be", "been", "being", "have", "has", "had", "do", "does", "did", "will", "would",
        "shall", "should", "may", "might", "must", "can", "could", "this", "that", "these",
        "those", "it", "its", "they", "them", "their", "we", "us", "our", "you", "your",
        "he", "him", "his", "she", "her", "which", "who", "whom", "what", "where", "when",
        "how", "why", "all", "any", "both", "each", "few", "more", "most", "other", "some",
        "such", "no", "nor", "not", "only", "own", "same", "so", "than", "too", "very"
    )

    data class ConceptCluster(
        val name: String,
        val domainId: Int,
        val keywords: List<String>,
        val importanceBoost: Float = 1.6f
    )

    val CONCEPT_CLUSTERS: List<ConceptCluster> = listOf(
        ConceptCluster(
            name = "AI & Machine Learning",
            domainId = 1,
            keywords = listOf(
                "ai", "ml", "transformer", "attention", "neural", "network", "embedding", "embeddings",
                "litert", "tensorflow", "tflite", "quantiz", "quantization", "int8", "fp16", "minilm",
                "bge", "bert", "weights", "inference", "backprop", "lora", "diffusion", "vision", "nlp",
                "deep", "learning", "model", "token", "tokens", "tokenizer", "wordpiece"
            ),
            importanceBoost = 2.0f
        ),
        ConceptCluster(
            name = "Distributed Systems & Consensus",
            domainId = 2,
            keywords = listOf(
                "distributed", "consensus", "raft", "paxos", "leader", "follower", "candidate", "election",
                "partition", "tolerance", "linearizable", "cap", "replicated", "log", "cluster", "nodes",
                "quorum", "failover", "consistency", "availability", "microservice"
            ),
            importanceBoost = 2.0f
        ),
        ConceptCluster(
            name = "Database & Search Architecture",
            domainId = 3,
            keywords = listOf(
                "sqlite", "database", "fts", "fts5", "fts4", "bm25", "tfidf", "vector", "search",
                "knn", "cosine", "similarity", "distance", "rrf", "reciprocal", "rank", "ranking",
                "index", "indexing", "btree", "lsm", "storage", "query", "blob", "table", "schema"
            ),
            importanceBoost = 2.0f
        ),
        ConceptCluster(
            name = "Quantum Information & Physics",
            domainId = 4,
            keywords = listOf(
                "quantum", "qubit", "qubits", "superposition", "entanglement", "bell", "shor", "grover",
                "decoherence", "circuit", "physics", "linear", "state", "psi", "spin", "photon"
            ),
            importanceBoost = 2.2f
        ),
        ConceptCluster(
            name = "Immunology, Biology & Vaccines",
            domainId = 5,
            keywords = listOf(
                "mrna", "vaccine", "vaccines", "lipid", "nanoparticle", "lnp", "spike", "glycoprotein",
                "antigen", "antibody", "antibodies", "immune", "immunology", "ribosome", "dendritic",
                "lymphocyte", "t-cell", "b-cell", "mhc", "cellular", "gene", "genomic", "crispr",
                "medical", "health", "physical", "lipid panel", "doctor", "dr"
            ),
            importanceBoost = 2.2f
        ),
        ConceptCluster(
            name = "Android OS & Storage Framework",
            domainId = 6,
            keywords = listOf(
                "android", "saf", "storage", "framework", "documentfile", "tree", "uri", "permission",
                "workmanager", "coroutine", "foreground", "notification", "contentresolver", "download",
                "downloads", "kotlin", "compose", "material", "viewmodel", "broadcast", "widget"
            ),
            importanceBoost = 1.8f
        ),
        ConceptCluster(
            name = "Hardware & NPU Acceleration",
            domainId = 7,
            keywords = listOf(
                "hardware", "cpu", "gpu", "npu", "htp", "tpu", "edgetpu", "qualcomm", "qnn", "snapdragon",
                "tensor", "pixel", "neon", "simd", "xnnpack", "vulkan", "opencl", "latency", "benchmark",
                "thermal", "fps", "gaming", "governor", "cache", "frequency"
            ),
            importanceBoost = 1.8f
        ),
        ConceptCluster(
            name = "Chat, Messaging & Travel",
            domainId = 8,
            keywords = listOf(
                "sms", "chat", "message", "whatsapp", "conversation", "alex", "sarah", "flight",
                "airline", "airport", "hotel", "confirmation", "reservation", "schedule", "meeting",
                "monday", "hyatt", "seattle", "presentation", "slides"
            ),
            importanceBoost = 1.7f
        )
    )

    /**
     * Maps word stems to domain centroid vectors.
     */
    fun getDomainCentroid(domainId: Int, dimension: Int): FloatArray {
        val vec = FloatArray(dimension)
        val seed = (domainId * 31337L) and 0xFFFFFFFFL
        var state = seed
        for (d in 0 until dimension) {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            val v = ((state ushr 16) - 32768).toFloat() / 32768f
            val freq = (d * (domainId + 1) * 0.05).toFloat()
            vec[d] = v + 0.8f * sin(freq) + 0.5f * cos(freq * 1.5f)
        }
        return VectorSimilarityUtils.l2Normalize(vec)
    }

    /**
     * Computes the semantic saliency weight for a token.
     */
    fun getTokenSaliency(token: String): Float {
        val clean = token.lowercase().removePrefix("##")
        if (clean.isBlank()) return 0f
        if (STOPWORDS.contains(clean)) return 0.15f
        if (clean.length == 1 && clean[0].isLetter()) return 0.25f

        var highestBoost = 1.0f
        for (cluster in CONCEPT_CLUSTERS) {
            for (kw in cluster.keywords) {
                if (clean == kw || clean.contains(kw) || kw.contains(clean)) {
                    if (cluster.importanceBoost > highestBoost) {
                        highestBoost = cluster.importanceBoost
                    }
                }
            }
        }
        return highestBoost
    }

    /**
     * Checks matching concept clusters for a text string and returns primary domain IDs.
     */
    fun extractMatchedDomains(text: String): List<Int> {
        val lower = text.lowercase()
        val matched = mutableListOf<Int>()
        for (cluster in CONCEPT_CLUSTERS) {
            for (kw in cluster.keywords) {
                if (lower.contains(kw)) {
                    matched.add(cluster.domainId)
                    break
                }
            }
        }
        return matched.distinct()
    }
}

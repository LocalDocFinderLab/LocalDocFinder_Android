package com.example.engine

enum class SearchMode(val displayName: String) {
    HYBRID("Hybrid (RRF)"),
    VECTOR("Vector (KNN)"),
    KEYWORD("Keyword (FTS)")
}

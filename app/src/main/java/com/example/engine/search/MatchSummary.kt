package com.example.engine.search

/** One short line telling the user why a result matched, e.g. "File name + text • 3 places • p. 4". */
object MatchSummary {

    fun describe(
        type: MatchType,
        matchCount: Int,
        matchedInName: Boolean,
        matchedInText: Boolean,
        pageLabel: String?
    ): String {
        if (type == MatchType.NONE) return ""
        val what = when {
            type == MatchType.FILE_NAME && matchedInText -> "File name + text"
            type != MatchType.FILE_NAME && type != MatchType.SIMILAR_MEANING && matchedInName -> "${type.label} + file name"
            else -> type.label
        }
        val parts = ArrayList<String>(3)
        parts.add(what)
        if (matchCount > 1) parts.add("$matchCount places")
        if (!pageLabel.isNullOrBlank()) parts.add(pageLabel)
        return parts.joinToString(" • ")
    }
}

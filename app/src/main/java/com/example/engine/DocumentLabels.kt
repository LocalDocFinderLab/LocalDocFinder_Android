package com.example.engine

/**
 * Predefined categories users can assign to documents and filter search results by.
 *
 * They are always offered in the tag filter row and the tagging dialog, even before any document
 * carries them; labels created by the user (free-form tags) are merged in after these.
 */
object DocumentLabels {

    val PREDEFINED: List<String> = listOf(
        "Work",
        "Personal",
        "Finance",
        "Legal",
        "Medical",
        "Education",
        "Receipts",
        "Travel",
        "Research"
    )

    /** Predefined labels first (in their fixed order), then any other tags in use, without duplicates. */
    fun merge(tagsInUse: List<String>): List<String> {
        val result = ArrayList<String>(PREDEFINED.size + tagsInUse.size)
        val seen = HashSet<String>()
        for (label in PREDEFINED + tagsInUse) {
            val clean = label.trim()
            if (clean.isNotEmpty() && seen.add(clean.lowercase())) {
                result.add(clean)
            }
        }
        return result
    }

    fun isPredefined(tag: String): Boolean = PREDEFINED.any { it.equals(tag.trim(), ignoreCase = true) }
}

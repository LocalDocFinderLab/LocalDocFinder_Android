package com.example.engine

/**
 * Filter configuration for device document scanning and indexing.
 * Allows users to choose which document formats to index from the device.
 * By default, all OS files, system artifacts, packages, databases, and caches are strictly excluded.
 */
data class DocumentTypeFilter(
    val includePdf: Boolean = true,
    val includeTextMarkdown: Boolean = true,
    val includeOffice: Boolean = true,
    val includeCodeData: Boolean = true,
    val includeImages: Boolean = false,
    val excludeSystemOsFiles: Boolean = true
) {
    companion object {
        val PDF_EXTENSIONS = setOf("pdf")
        val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "text", "rst")
        val OFFICE_EXTENSIONS = setOf("docx", "doc", "pptx", "ppt", "xlsx", "xls")
        val CODE_DATA_EXTENSIONS = setOf("json", "csv", "tsv", "html", "htm", "xml", "log")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "dng", "bmp", "tiff", "tif")
    }

    /**
     * Checks if a given file extension is accepted by the active filter.
     */
    fun isAllowed(extension: String): Boolean {
        val ext = extension.lowercase().trim().removePrefix(".")
        if (includePdf && ext in PDF_EXTENSIONS) return true
        if (includeTextMarkdown && ext in TEXT_EXTENSIONS) return true
        if (includeOffice && ext in OFFICE_EXTENSIONS) return true
        if (includeCodeData && ext in CODE_DATA_EXTENSIONS) return true
        if (includeImages && ext in IMAGE_EXTENSIONS) return true
        return false
    }

    /**
     * Human-readable summary of selected formats.
     */
    fun formatSummary(): String {
        val selected = mutableListOf<String>()
        if (includePdf) selected.add("PDF")
        if (includeTextMarkdown) selected.add("Text/MD")
        if (includeOffice) selected.add("Office")
        if (includeCodeData) selected.add("Code/Data")
        if (includeImages) selected.add("Images")
        return if (selected.isEmpty()) "None selected" else selected.joinToString(", ")
    }
}

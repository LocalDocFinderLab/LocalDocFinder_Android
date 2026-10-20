package com.example.engine

/** Readable names for stored document URIs (`content://…/document/primary%3ADownload%2FTax.pdf`). */
object UriNames {

    private fun decode(uri: String): String =
        try { java.net.URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { uri }

    /** `…/primary%3ADownload%2FTax.pdf` -> `Tax.pdf`. */
    fun displayName(uri: String): String =
        decode(uri).trimEnd('/').substringAfterLast('/').substringAfterLast(':').ifBlank { uri }

    /** The folder part of the same path (`primary:Download`), to tell copies of one file apart. */
    fun location(uri: String): String {
        val decoded = decode(uri).trimEnd('/')
        val path = decoded.substringAfter("document/", decoded)
        return path.substringBeforeLast('/', "").ifBlank { "/" }
    }
}

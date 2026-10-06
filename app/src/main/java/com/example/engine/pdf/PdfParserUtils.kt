package com.example.engine.pdf

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

/**
 * PDF parsing utility to extract raw text content, structural pages, and metadata
 * from local PDF documents for BERT embedding generation and vector search indexing.
 *
 * Provides a lightweight, high-performance, self-contained extraction pipeline compatible
 * with Apache PDFBox paradigms without external native binary bloat.
 */
object PdfParserUtils {

    private const val TAG = "PdfParserUtils"

    /**
     * Extracts all raw text content from a local PDF file.
     *
     * @param file Local file pointing to a PDF document
     * @return Extracted plain text content, or empty string if unreadable
     */
    @JvmStatic
    fun extractRawText(file: File): String {
        return try {
            if (!file.exists() || file.length() == 0L) return ""
            file.inputStream().use { stream ->
                extractRawText(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting text from file ${file.absolutePath}: ${e.message}", e)
            ""
        }
    }

    /**
     * Extracts raw text from an input stream.
     *
     * @param inputStream Open stream to PDF bytes
     * @return Extracted plain text content
     */
    @JvmStatic
    fun extractRawText(inputStream: InputStream): String {
        return try {
            PdfTextExtractor.extractText(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting text from stream: ${e.message}", e)
            ""
        }
    }

    /**
     * Extracts raw text from a raw PDF byte array.
     *
     * @param bytes ByteArray containing PDF binary data
     * @return Extracted plain text content
     */
    @JvmStatic
    fun extractRawText(bytes: ByteArray): String {
        return try {
            val result = PdfTextExtractor.extractFromBytes(bytes)
            result.fullText
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting text from byte array: ${e.message}", e)
            ""
        }
    }

    /**
     * Extracts raw text from an Android content or file URI.
     *
     * @param context Application context for ContentResolver
     * @param uri URI of the PDF document
     * @return Extracted plain text content
     */
    @JvmStatic
    fun extractRawText(context: Context, uri: Uri): String {
        return try {
            PdfTextExtractor.extractText(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting text from URI $uri: ${e.message}", e)
            ""
        }
    }

    /**
     * Extracts raw text from a SAF [DocumentFile].
     *
     * @param context Application context
     * @param docFile DocumentFile representation of the PDF
     * @return Extracted plain text content
     */
    @JvmStatic
    fun extractRawText(context: Context, docFile: DocumentFile): String {
        return extractRawText(context, docFile.uri)
    }

    /**
     * Performs full document extraction including text split by pages and document metadata.
     */
    @JvmStatic
    fun extractDocument(context: Context, uri: Uri): PdfTextExtractor.ExtractionResult {
        return PdfTextExtractor.extractDocument(context, uri)
    }

    /**
     * Performs full document extraction from an input stream.
     */
    @JvmStatic
    fun extractDocument(inputStream: InputStream): PdfTextExtractor.ExtractionResult {
        val bytes = inputStream.readBytes()
        return PdfTextExtractor.extractFromBytes(bytes)
    }

    /**
     * Performs full document extraction from a local file.
     */
    @JvmStatic
    fun extractDocument(file: File): PdfTextExtractor.ExtractionResult {
        return file.inputStream().use { extractDocument(it) }
    }

    /**
     * Extracts metadata (title, author, subject, keywords, page count) from an input stream.
     */
    @JvmStatic
    fun extractMetadata(inputStream: InputStream): PdfTextExtractor.PdfMetadata {
        val doc = extractDocument(inputStream)
        return doc.metadata
    }

    /**
     * Extracts metadata from a local PDF file.
     */
    @JvmStatic
    fun extractMetadata(file: File): PdfTextExtractor.PdfMetadata {
        return file.inputStream().use { extractMetadata(it) }
    }

    /**
     * Cleans and normalizes extracted PDF text for optimal BERT embedding generation.
     *
     * - Merges hyphenated words broken across line wraps (e.g. "trans-\nformer" -> "transformer")
     * - Collapses irregular whitespace runs and page separator noise
     * - Strips non-printable ASCII control codes while preserving Unicode glyphs
     * - Formats clean paragraphs suitable for semantic tokenization
     *
     * @param rawText Raw text extracted from PDF streams
     * @return Normalized, clean text ready for embedding models
     */
    @JvmStatic
    fun cleanPdfTextForEmbedding(rawText: String): String {
        if (rawText.isBlank()) return ""

        // Standardize CRLF to LF
        val normalized = rawText
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        // Merge hyphenated words at line breaks (e.g. "embed-\ning" -> "embedding")
        val unhyphenated = Regex("([A-Za-z0-9]+)-\\s*\\n\\s*([A-Za-z0-9]+)").replace(normalized) { match ->
            match.groupValues[1] + match.groupValues[2]
        }

        // Remove non-printable control characters except newline and tab
        val cleanChars = unhyphenated.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), " ")

        // Collapse multiple spaces/tabs within lines
        val lines = cleanChars.lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }

        // Format paragraphs cleanly
        val result = StringBuilder()
        var hasBlank = false

        for (line in lines) {
            if (line.isEmpty()) {
                hasBlank = true
            } else {
                if (result.isNotEmpty()) {
                    if (hasBlank) {
                        result.append("\n\n")
                    } else {
                        result.append(" ")
                    }
                }
                result.append(line)
                hasBlank = false
            }
        }

        return result.toString().trim()
    }

    /**
     * Prepares semantic text chunks from PDF content for batch embedding inference.
     *
     * @param rawPdfText Extracted raw or cleaned PDF text
     * @param targetChunkSize Target character size per chunk (~250-384 tokens)
     * @param overlap Character overlap between adjacent chunks
     * @return List of clean text chunk strings ready for BERT tokenization
     */
    @JvmStatic
    fun prepareChunksForEmbedding(
        rawPdfText: String,
        targetChunkSize: Int = 1000,
        overlap: Int = 150
    ): List<String> {
        val cleaned = cleanPdfTextForEmbedding(rawPdfText)
        if (cleaned.length <= targetChunkSize) {
            return if (cleaned.isNotBlank()) listOf(cleaned) else emptyList()
        }

        val chunks = mutableListOf<String>()
        val paragraphs = cleaned.split("\n\n").filter { it.isNotBlank() }
        var currentChunk = StringBuilder()

        for (p in paragraphs) {
            if (currentChunk.length + p.length + 2 <= targetChunkSize) {
                if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                currentChunk.append(p)
            } else {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString().trim())
                    val overlapText = currentChunk.takeLast(overlap)
                    val spaceIdx = overlapText.indexOf(' ')
                    currentChunk = if (spaceIdx != -1 && spaceIdx < overlapText.length - 1) {
                        StringBuilder(overlapText.substring(spaceIdx + 1))
                    } else {
                        StringBuilder(overlapText)
                    }
                    if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                }
                if (p.length > targetChunkSize) {
                    val words = p.split(" ")
                    for (w in words) {
                        if (currentChunk.length + w.length + 1 <= targetChunkSize) {
                            if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                            currentChunk.append(w)
                        } else {
                            if (currentChunk.isNotEmpty()) {
                                chunks.add(currentChunk.toString().trim())
                                currentChunk = StringBuilder()
                            }
                            currentChunk.append(w)
                        }
                    }
                } else {
                    currentChunk.append(p)
                }
            }
        }

        if (currentChunk.isNotBlank()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks
    }

    /**
     * Checks if a byte array has a valid PDF file header (`%PDF-`).
     */
    @JvmStatic
    fun isPdf(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        val header = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        return header.contains("%PDF-")
    }

    /**
     * Checks if a file is a PDF based on extension or magic bytes.
     */
    @JvmStatic
    fun isPdf(file: File): Boolean {
        if (!file.exists() || file.length() < 5) return false
        if (file.extension.lowercase(Locale.ROOT) == "pdf") return true
        return try {
            file.inputStream().use { stream ->
                val buf = ByteArray(1024)
                val read = stream.read(buf)
                if (read > 0) {
                    val header = String(buf, 0, read, Charsets.ISO_8859_1)
                    header.contains("%PDF-")
                } else false
            }
        } catch (_: Exception) {
            false
        }
    }
}

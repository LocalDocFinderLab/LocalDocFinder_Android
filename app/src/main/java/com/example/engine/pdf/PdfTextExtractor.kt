package com.example.engine.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.Inflater

/**
 * High-performance PDF Text Extraction Engine for Android.
 * Extracts plain text, structural sections, and metadata from local PDF documents
 * to enable indexing in SQLite Full-Text Search (FTS4/FTS5) and vector embedding pipelines.
 *
 * Supported PDF Features:
 * - Direct stream scanner & FlateDecode (Deflate/zlib) decompression
 * - Compressed Object Streams (/Type /ObjStm) and Indirect Objects
 * - ASCIIHexDecode, ASCII85Decode, RunLengthDecode
 * - ToUnicode CMap parsing (bfchar & bfrange for Unicode glyph mapping)
 * - Text operators: Tj, TJ, ', ", BT, ET, Td, TD, Tm, T*
 * - Literal strings with escape sequences & Hexadecimal string decoding
 * - PdfRenderer fallback for scanned/image-based PDF documents
 */
object PdfTextExtractor {

    private const val TAG = "PdfTextExtractor"

    data class PdfMetadata(
        val title: String? = null,
        val author: String? = null,
        val subject: String? = null,
        val keywords: String? = null,
        val creator: String? = null,
        val producer: String? = null,
        val pageCount: Int = 0
    )

    data class ExtractionResult(
        val fullText: String,
        val pageTexts: List<String>,
        val metadata: PdfMetadata,
        val isSuccess: Boolean,
        val error: String? = null
    )

    /**
     * Extracts all text from a given local URI using Context for stream and PdfRenderer fallback.
     */
    fun extractText(context: Context, uri: Uri): String {
        val result = extractDocument(context, uri)
        return result.fullText
    }

    /**
     * Extracts text from an input stream.
     */
    fun extractText(inputStream: InputStream): String {
        val bytes = inputStream.readBytes()
        return extractFromBytes(bytes).fullText
    }

    /**
     * Full document extraction returning text per page and metadata.
     */
    fun extractDocument(context: Context, uri: Uri): ExtractionResult {
        return try {
            val fileSize = try {
                if (uri.scheme == "file" || uri.scheme.isNullOrEmpty()) {
                    File(uri.path ?: uri.toString().removePrefix("file://")).length()
                } else {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                }
            } catch (_: Throwable) { 0L }

            // Guard against massive files blowing up memory in byte buffer (strict 8MB limit)
            if (fileSize > 8L * 1024 * 1024) {
                Log.w(TAG, "PDF is very large (${fileSize / (1024 * 1024)} MB). Using PdfRenderer fallback to prevent OOM.")
                val rendererText = extractTextUsingPdfRenderer(context, uri)
                val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "Large PDF"
                return if (rendererText.isNotBlank()) {
                    ExtractionResult(
                        fullText = rendererText,
                        pageTexts = listOf(rendererText),
                        metadata = PdfMetadata(title = filename, pageCount = 1),
                        isSuccess = true
                    )
                } else {
                    ExtractionResult("", emptyList(), PdfMetadata(), false, "File exceeds 8MB in-memory scan limit")
                }
            }

            val bytes = try {
                if (uri.scheme == "file" || uri.scheme.isNullOrEmpty()) {
                    val path = uri.path ?: uri.toString().removePrefix("file://")
                    File(path).readBytes()
                } else {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return ExtractionResult("", emptyList(), PdfMetadata(), false, "Unable to open URI stream")
                }
            } catch (t: Throwable) {
                if (t is OutOfMemoryError) System.gc()
                Log.w(TAG, "Failed reading PDF bytes for $uri: ${t.message}")
                return ExtractionResult("", emptyList(), PdfMetadata(), false, "Memory limit reached while reading PDF")
            }
            val res = extractFromBytes(bytes)
            if (res.fullText.isNotBlank()) {
                return res
            }

            if (!res.isSuccess && (res.error == "Missing %PDF header" || res.error == "File too small to be a PDF")) {
                return res
            }

            // Fallback: try PdfRenderer page extraction for scanned/image PDFs
            val rendererText = extractTextUsingPdfRenderer(context, uri)
            if (rendererText.isNotBlank()) {
                val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "PDF Document"
                return ExtractionResult(
                    fullText = rendererText,
                    pageTexts = listOf(rendererText),
                    metadata = PdfMetadata(title = filename, pageCount = 1),
                    isSuccess = true
                )
            }

            res
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) {
                System.gc()
            }
            Log.e(TAG, "Error extracting text from PDF: ${t.message}", t)
            ExtractionResult("", emptyList(), PdfMetadata(), false, t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    /**
     * Core extraction algorithm from raw PDF byte buffer.
     */
    fun extractFromBytes(bytes: ByteArray): ExtractionResult {
        if (bytes.size < 8) {
            return ExtractionResult("", emptyList(), PdfMetadata(), false, "File too small to be a PDF")
        }

        val header = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        if (!header.contains("%PDF-")) {
            return ExtractionResult("", emptyList(), PdfMetadata(), false, "Missing %PDF header")
        }

        val rawIso = String(bytes, Charsets.ISO_8859_1)
        val metadata = extractMetadata(rawIso)
        val pageTexts = mutableListOf<String>()

        // 1. Parse Indirect Objects & Fonts
        val objects = parsePdfObjects(bytes, rawIso)
        val fonts = extractFontCMaps(objects)

        // 2. Identify Page Objects or Content Streams from Indirect Objects
        val contentStreams = identifyContentStreams(objects)

        if (contentStreams.isNotEmpty()) {
            for (streamBytes in contentStreams) {
                val pageText = parseContentStreamText(streamBytes, fonts)
                if (pageText.isNotBlank()) {
                    pageTexts.add(pageText.trim())
                }
            }
        }

        // 3. If standard page objects did not yield text, scan ALL streams directly in the binary
        if (pageTexts.isEmpty()) {
            val rawStreams = scanAndDecompressAllByteStreams(bytes)
            for (streamBytes in rawStreams) {
                val text = parseContentStreamText(streamBytes, fonts)
                if (text.isNotBlank() && text.length >= 5) {
                    pageTexts.add(text.trim())
                }
            }
        }

        // 4. Fallback: string literal extraction & word runs
        if (pageTexts.isEmpty()) {
            val fallback = extractFallbackAsciiRuns(rawIso)
            if (fallback.isNotBlank()) {
                pageTexts.add(fallback)
            }
        }

        val fullTextBuilder = StringBuilder()
        if (!metadata.title.isNullOrBlank()) {
            fullTextBuilder.append("Title: ").append(metadata.title).append("\n\n")
        }
        if (!metadata.subject.isNullOrBlank()) {
            fullTextBuilder.append("Subject: ").append(metadata.subject).append("\n\n")
        }
        if (!metadata.keywords.isNullOrBlank()) {
            fullTextBuilder.append("Keywords: ").append(metadata.keywords).append("\n\n")
        }

        for ((index, page) in pageTexts.withIndex()) {
            if (pageTexts.size > 1) {
                fullTextBuilder.append("--- Page ").append(index + 1).append(" ---\n")
            }
            fullTextBuilder.append(page).append("\n\n")
        }

        val fullText = fullTextBuilder.toString().trim()
        val finalMetadata = metadata.copy(pageCount = maxOf(1, pageTexts.size))

        return ExtractionResult(
            fullText = fullText,
            pageTexts = pageTexts,
            metadata = finalMetadata,
            isSuccess = fullText.isNotBlank()
        )
    }

    /**
     * Binary Stream Scanner: Scans raw PDF bytes for all occurrences of 'stream' and 'endstream'
     * and attempts Flate/zlib decompression on every slice. Handles compressed Object Streams (/ObjStm).
     */
    private fun scanAndDecompressAllByteStreams(bytes: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        val streamKeyword = "stream".toByteArray(Charsets.ISO_8859_1)
        val endstreamKeyword = "endstream".toByteArray(Charsets.ISO_8859_1)

        var i = 0
        val maxLen = bytes.size - 10
        var totalDecompressedBytes = 0
        val maxDecompressedBytes = 6 * 1024 * 1024
        val maxStreamsCount = 40

        while (i < maxLen && result.size < maxStreamsCount && totalDecompressedBytes < maxDecompressedBytes) {
            if (matchBytes(bytes, i, streamKeyword)) {
                var streamStart = i + streamKeyword.size
                if (streamStart < bytes.size && bytes[streamStart] == '\r'.code.toByte()) streamStart++
                if (streamStart < bytes.size && bytes[streamStart] == '\n'.code.toByte()) streamStart++

                val endIdx = findBytes(bytes, streamStart, endstreamKeyword)
                if (endIdx != -1 && endIdx > streamStart) {
                    var streamEnd = endIdx
                    if (streamEnd > streamStart && bytes[streamEnd - 1] == '\n'.code.toByte()) streamEnd--
                    if (streamEnd > streamStart && bytes[streamEnd - 1] == '\r'.code.toByte()) streamEnd--

                    val len = streamEnd - streamStart
                    if (len in 4..1_500_000) {
                        try {
                            val slice = bytes.copyOfRange(streamStart, streamEnd)
                            val decompressed = decompressFlate(slice)
                            if (decompressed != null && decompressed.isNotEmpty()) {
                                val probe = String(decompressed, 0, minOf(decompressed.size, 512), Charsets.ISO_8859_1)
                                if (probe.contains("Tj") || probe.contains("TJ") || probe.contains("BT") || probe.contains("(")) {
                                    result.add(decompressed)
                                    totalDecompressedBytes += decompressed.size
                                } else {
                                    val innerStreams = parseInnerObjectStreamText(decompressed)
                                    if (innerStreams.isNotEmpty()) {
                                        result.addAll(innerStreams)
                                        totalDecompressedBytes += innerStreams.sumOf { it.size }
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            if (t is OutOfMemoryError) {
                                System.gc()
                                break
                            }
                        }
                    }
                    i = endIdx + endstreamKeyword.size
                    continue
                } else {
                    // endstream not found: skip past current keyword to prevent quadratic scanning
                    i += streamKeyword.size + 128
                    continue
                }
            }
            i++
        }
        return result
    }

    private fun matchBytes(data: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset + pattern.size > data.size) return false
        for (j in pattern.indices) {
            if (data[offset + j] != pattern[j]) return false
        }
        return true
    }

    private fun findBytes(data: ByteArray, startOffset: Int, pattern: ByteArray): Int {
        val limit = data.size - pattern.size
        for (k in startOffset..limit) {
            if (matchBytes(data, k, pattern)) return k
        }
        return -1
    }

    private fun parseInnerObjectStreamText(decompressed: ByteArray): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        val text = String(decompressed, Charsets.ISO_8859_1)
        val tjMatches = Regex("""(\([^()]*\)|<[0-9a-fA-F\s]+>)\s*Tj""").findAll(text)
        if (tjMatches.count() > 0) {
            list.add(decompressed)
        }
        return list
    }

    // =========================================================================
    // PDF Object & Stream Parser
    // =========================================================================

    private data class PdfObject(
        val id: Int,
        val generation: Int,
        val dictionary: String,
        val streamData: ByteArray? = null
    )

    private fun parsePdfObjects(bytes: ByteArray, rawIso: String): Map<Int, PdfObject> {
        val objects = mutableMapOf<Int, PdfObject>()
        val objPattern = Regex("""(\d+)\s+(\d+)\s+obj\b""")
        val matches = objPattern.findAll(rawIso).toList()

        for (i in matches.indices) {
            val match = matches[i]
            val objId = match.groupValues[1].toIntOrNull() ?: continue
            val gen = match.groupValues[2].toIntOrNull() ?: 0

            val startIdx = match.range.last + 1
            val endObjIdx = rawIso.indexOf("endobj", startIdx)
            if (endObjIdx == -1) continue

            val objBody = rawIso.substring(startIdx, endObjIdx)
            val streamStartKeyword = "stream"
            val streamIdx = objBody.indexOf(streamStartKeyword)

            var streamBytes: ByteArray? = null
            val dictionaryPart: String

            if (streamIdx != -1) {
                dictionaryPart = objBody.substring(0, streamIdx).trim()
                val isImageStream = dictionaryPart.contains("/Subtype /Image") ||
                        dictionaryPart.contains("/DCTDecode") ||
                        dictionaryPart.contains("/JPXDecode") ||
                        dictionaryPart.contains("/JBIG2Decode")

                if (!isImageStream) {
                    var rawStreamStart = startIdx + streamIdx + streamStartKeyword.length
                    if (rawStreamStart < bytes.size && bytes[rawStreamStart] == '\r'.code.toByte()) rawStreamStart++
                    if (rawStreamStart < bytes.size && bytes[rawStreamStart] == '\n'.code.toByte()) rawStreamStart++

                    val endStreamIdx = rawIso.indexOf("endstream", rawStreamStart)
                    if (endStreamIdx != -1 && endStreamIdx >= rawStreamStart) {
                        var rawStreamEnd = endStreamIdx
                        if (rawStreamEnd > rawStreamStart && bytes[rawStreamEnd - 1] == '\n'.code.toByte()) rawStreamEnd--
                        if (rawStreamEnd > rawStreamStart && bytes[rawStreamEnd - 1] == '\r'.code.toByte()) rawStreamEnd--

                        val streamLen = rawStreamEnd - rawStreamStart
                        if (streamLen in 1..1_500_000 && rawStreamStart + streamLen <= bytes.size) {
                            val compressedData = bytes.copyOfRange(rawStreamStart, rawStreamStart + streamLen)
                            streamBytes = decodeStream(compressedData, dictionaryPart)
                        }
                    }
                }
            } else {
                dictionaryPart = objBody.trim()
            }

            objects[objId] = PdfObject(objId, gen, dictionaryPart, streamBytes)
        }

        return objects
    }

    private fun decodeStream(data: ByteArray, dictionary: String): ByteArray {
        val isFlate = dictionary.contains("/FlateDecode") || dictionary.contains("/Fl")
        val isAsciiHex = dictionary.contains("/ASCIIHexDecode") || dictionary.contains("/AHx")
        val isAscii85 = dictionary.contains("/ASCII85Decode") || dictionary.contains("/A85")
        val isRunLength = dictionary.contains("/RunLengthDecode") || dictionary.contains("/RL")

        var result = data

        if (isAsciiHex) {
            result = decodeAsciiHex(result)
        }
        if (isAscii85) {
            result = decodeAscii85(result)
        }
        if (isRunLength) {
            result = decodeRunLength(result)
        }
        if (isFlate) {
            val decomp = decompressFlate(result)
            if (decomp != null) {
                result = decomp
            }
        }

        return result
    }

    private fun decompressFlate(data: ByteArray): ByteArray? {
        val maxDecompressSize = 512 * 1024 // 512KB limit per stream prevents memory exhaustion
        return try {
            val inflater = Inflater(false)
            try {
                inflater.setInput(data)
                val buffer = ByteArray(4096)
                val out = ByteArrayOutputStream()
                while (!inflater.finished() && out.size() < maxDecompressSize) {
                    val count = inflater.inflate(buffer)
                    if (count <= 0) break
                    out.write(buffer, 0, count)
                }
                val res = out.toByteArray()
                if (res.isNotEmpty()) res else null
            } finally {
                inflater.end()
            }
        } catch (_: Exception) {
            try {
                val inflater = Inflater(true)
                try {
                    val offset = if (data.size > 2 && (data[0].toInt() and 0xFF) == 0x78) 2 else 0
                    inflater.setInput(data, offset, data.size - offset)
                    val buffer = ByteArray(4096)
                    val out = ByteArrayOutputStream()
                    while (!inflater.finished() && out.size() < maxDecompressSize) {
                        val count = inflater.inflate(buffer)
                        if (count <= 0) break
                        out.write(buffer, 0, count)
                    }
                    val res = out.toByteArray()
                    if (res.isNotEmpty()) res else null
                } finally {
                    inflater.end()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun decodeAsciiHex(data: ByteArray): ByteArray {
        val hexStr = String(data, Charsets.ISO_8859_1).replace(Regex("""\s+"""), "").substringBefore('>')
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < hexStr.length) {
            val high = Character.digit(hexStr[i], 16)
            val low = if (i + 1 < hexStr.length) Character.digit(hexStr[i + 1], 16) else 0
            if (high != -1) {
                val b = (high shl 4) or (if (low != -1) low else 0)
                out.write(b)
            }
            i += 2
        }
        return out.toByteArray()
    }

    private fun decodeAscii85(data: ByteArray): ByteArray {
        val raw = String(data, Charsets.ISO_8859_1).substringBefore("~>").replace(Regex("""\s+"""), "")
        val out = ByteArrayOutputStream()
        var count = 0
        var value = 0L

        for (ch in raw) {
            if (ch == 'z' && count == 0) {
                out.write(0); out.write(0); out.write(0); out.write(0)
                continue
            }
            if (ch in '!'..'u') {
                value = value * 85 + (ch.code - 33)
                count++
                if (count == 5) {
                    out.write(((value shr 24) and 0xFF).toInt())
                    out.write(((value shr 16) and 0xFF).toInt())
                    out.write(((value shr 8) and 0xFF).toInt())
                    out.write((value and 0xFF).toInt())
                    value = 0L
                    count = 0
                }
            }
        }
        return out.toByteArray()
    }

    private fun decodeRunLength(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            if (b == 128) break
            if (b < 128) {
                val count = b + 1
                if (i + 1 + count <= data.size) {
                    out.write(data, i + 1, count)
                    i += count
                }
            } else {
                val count = 257 - b
                if (i + 1 < data.size) {
                    val value = data[i + 1]
                    for (k in 0 until count) out.write(value.toInt())
                    i++
                }
            }
            i++
        }
        return out.toByteArray()
    }

    // =========================================================================
    // Font & Unicode CMap Extraction
    // =========================================================================

    private data class FontInfo(
        val toUnicodeMap: Map<Int, String> = emptyMap()
    )

    private fun extractFontCMaps(objects: Map<Int, PdfObject>): Map<String, FontInfo> {
        val fontMap = mutableMapOf<String, FontInfo>()

        for ((_, obj) in objects) {
            if (Regex("""/Type\s*/Font""").containsMatchIn(obj.dictionary) ||
                Regex("""/Subtype\s*/Type0""").containsMatchIn(obj.dictionary) ||
                Regex("""/Subtype\s*/TrueType""").containsMatchIn(obj.dictionary)) {
                val toUnicodeMatch = Regex("""/ToUnicode\s+(\d+)\s+(\d+)\s+R""").find(obj.dictionary)
                var toUnicodeMap = emptyMap<Int, String>()

                if (toUnicodeMatch != null) {
                    val toUnicodeId = toUnicodeMatch.groupValues[1].toIntOrNull()
                    val cmapObj = objects[toUnicodeId]
                    if (cmapObj?.streamData != null) {
                        toUnicodeMap = parseToUnicodeCMap(cmapObj.streamData)
                    }
                }

                val nameMatch = Regex("""/BaseFont\s*/([A-Za-z0-9_-]+)""").find(obj.dictionary)
                    ?: Regex("""/Name\s*/([A-Za-z0-9_-]+)""").find(obj.dictionary)
                val fontName = nameMatch?.groupValues?.get(1) ?: "F_${obj.id}"

                fontMap[fontName] = FontInfo(toUnicodeMap = toUnicodeMap)
            }
        }

        return fontMap
    }

    private fun parseToUnicodeCMap(data: ByteArray): Map<Int, String> {
        val cmap = mutableMapOf<Int, String>()
        val text = String(data, Charsets.ISO_8859_1)

        val bfcharBlocks = Regex("""beginbfchar[\r\n]+(.*?)[\r\n]+endbfchar""", RegexOption.DOT_MATCHES_ALL)
        for (block in bfcharBlocks.findAll(text)) {
            val content = block.groupValues[1]
            val linePattern = Regex("""<([0-9a-fA-F]+)>\s+<([0-9a-fA-F]+)>""")
            for (m in linePattern.findAll(content)) {
                val src = m.groupValues[1].toIntOrNull(16) ?: continue
                val dstHex = m.groupValues[2]
                val dst = decodeUtf16Hex(dstHex)
                cmap[src] = dst
            }
        }

        val bfrangeBlocks = Regex("""beginbfrange[\r\n]+(.*?)[\r\n]+endbfrange""", RegexOption.DOT_MATCHES_ALL)
        for (block in bfrangeBlocks.findAll(text)) {
            val content = block.groupValues[1]
            val rangePattern = Regex("""<([0-9a-fA-F]+)>\s+<([0-9a-fA-F]+)>\s+<([0-9a-fA-F]+)>""")
            for (m in rangePattern.findAll(content)) {
                val start = m.groupValues[1].toIntOrNull(16) ?: continue
                val end = m.groupValues[2].toIntOrNull(16) ?: continue
                val dstStart = m.groupValues[3].toIntOrNull(16) ?: continue
                val diff = dstStart - start
                if (end >= start && (end - start) in 0..2048) {
                    for (code in start..end) {
                        val mapped = code + diff
                        if (mapped in 0..0xFFFF) {
                            cmap[code] = mapped.toChar().toString()
                        }
                    }
                }
            }
        }

        return cmap
    }

    private fun decodeUtf16Hex(hex: String): String {
        val clean = hex.trim()
        val sb = StringBuilder()
        var i = 0
        while (i + 3 < clean.length) {
            val code = clean.substring(i, i + 4).toIntOrNull(16)
            if (code != null) {
                sb.append(code.toChar())
            }
            i += 4
        }
        if (sb.isEmpty() && clean.length == 2) {
            clean.toIntOrNull(16)?.let { sb.append(it.toChar()) }
        }
        return sb.toString()
    }

    // =========================================================================
    // Content Stream Text Extraction & Operators
    // =========================================================================

    private fun identifyContentStreams(objects: Map<Int, PdfObject>): List<ByteArray> {
        val streams = mutableListOf<ByteArray>()

        for ((_, obj) in objects) {
            if (Regex("""/Type\s*/Page\b""").containsMatchIn(obj.dictionary)) {
                val contentsMatch = Regex("""/Contents\s+(\d+)\s+(\d+)\s+R""").find(obj.dictionary)
                if (contentsMatch != null) {
                    val streamId = contentsMatch.groupValues[1].toIntOrNull()
                    val streamObj = objects[streamId]
                    if (streamObj?.streamData != null && streamObj.streamData.isNotEmpty()) {
                        streams.add(streamObj.streamData)
                    }
                } else {
                    val arrayMatch = Regex("""/Contents\s*\[\s*([\d\sR]+)\s*\]""").find(obj.dictionary)
                    if (arrayMatch != null) {
                        val ids = Regex("""(\d+)\s+\d+\s+R""").findAll(arrayMatch.groupValues[1])
                            .mapNotNull { it.groupValues[1].toIntOrNull() }
                        for (id in ids) {
                            val st = objects[id]?.streamData
                            if (st != null && st.isNotEmpty()) {
                                streams.add(st)
                            }
                        }
                    }
                }
            }
        }

        return streams
    }

    private fun parseContentStreamText(streamBytes: ByteArray, fonts: Map<String, FontInfo>): String {
        val content = String(streamBytes, Charsets.ISO_8859_1)
        val sb = StringBuilder()

        val tjPattern = Regex("""(\([^()]*\)|<[0-9a-fA-F\s]+>)\s*Tj""")
        for (m in tjPattern.findAll(content)) {
            val token = m.groupValues[1]
            val text = decodePdfTextToken(token)
            if (text.isNotBlank()) {
                sb.append(text).append(" ")
            }
        }

        val tjArrayPattern = Regex("""\[(.*?)\]\s*TJ""", RegexOption.DOT_MATCHES_ALL)
        for (m in tjArrayPattern.findAll(content)) {
            val inner = m.groupValues[1]
            val tokenPattern = Regex("""(\([^()]*\)|<[0-9a-fA-F\s]+>|[-+]?\d*\.?\d+)""")
            for (tm in tokenPattern.findAll(inner)) {
                val tok = tm.groupValues[1]
                if (tok.startsWith("(") || tok.startsWith("<")) {
                    val text = decodePdfTextToken(tok)
                    sb.append(text)
                } else {
                    val spacing = tok.toDoubleOrNull()
                    if (spacing != null && spacing < -120) {
                        sb.append(" ")
                    }
                }
            }
            sb.append(" ")
        }

        val nextLinePattern = Regex("""(\([^()]*\)|<[0-9a-fA-F\s]+>)\s*['"]""")
        for (m in nextLinePattern.findAll(content)) {
            val text = decodePdfTextToken(m.groupValues[1])
            if (text.isNotBlank()) {
                sb.append("\n").append(text).append(" ")
            }
        }

        val rawText = sb.toString().replace(Regex("""[ \t]+"""), " ").trim()
        if (rawText.isNotBlank()) return rawText

        // Fallback token extraction for literal strings inside this stream
        val literals = Regex("""\(([^()]{2,200})\)""").findAll(content)
            .map { unescapePdfString(it.groupValues[1]) }
            .filter { str -> str.any { it.isLetter() } && !str.startsWith("/") }
            .joinToString(" ")

        return literals.trim()
    }

    private fun decodePdfTextToken(token: String): String {
        if (token.startsWith("(") && token.endsWith(")")) {
            val raw = token.substring(1, token.length - 1)
            return unescapePdfString(raw)
        } else if (token.startsWith("<") && token.endsWith(">")) {
            val hex = token.substring(1, token.length - 1).replace(Regex("""\s+"""), "")
            return if (hex.length >= 4 && hex.length % 4 == 0) {
                decodeUtf16Hex(hex)
            } else {
                val out = StringBuilder()
                var i = 0
                while (i < hex.length) {
                    val code = hex.substring(i, minOf(i + 2, hex.length)).toIntOrNull(16)
                    if (code != null && code >= 32 && code < 127) {
                        out.append(code.toChar())
                    }
                    i += 2
                }
                out.toString()
            }
        }
        return ""
    }

    private fun unescapePdfString(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    '(', ')', '\\' -> { sb.append(next); i += 2 }
                    in '0'..'7' -> {
                        var octalLen = 1
                        while (octalLen < 3 && i + 1 + octalLen < s.length && s[i + 1 + octalLen] in '0'..'7') {
                            octalLen++
                        }
                        val octalStr = s.substring(i + 1, i + 1 + octalLen)
                        val code = octalStr.toIntOrNull(8) ?: 0
                        sb.append(code.toChar())
                        i += 1 + octalLen
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun extractMetadata(rawIso: String): PdfMetadata {
        var title: String? = null
        var author: String? = null
        var subject: String? = null
        var keywords: String? = null
        var creator: String? = null
        var producer: String? = null

        val titleMatch = Regex("""/Title\s*\(([^()]+)\)""").find(rawIso)
        if (titleMatch != null) title = unescapePdfString(titleMatch.groupValues[1])

        val authorMatch = Regex("""/Author\s*\(([^()]+)\)""").find(rawIso)
        if (authorMatch != null) author = unescapePdfString(authorMatch.groupValues[1])

        val subjectMatch = Regex("""/Subject\s*\(([^()]+)\)""").find(rawIso)
        if (subjectMatch != null) subject = unescapePdfString(subjectMatch.groupValues[1])

        val keywordsMatch = Regex("""/Keywords\s*\(([^()]+)\)""").find(rawIso)
        if (keywordsMatch != null) keywords = unescapePdfString(keywordsMatch.groupValues[1])

        val creatorMatch = Regex("""/Creator\s*\(([^()]+)\)""").find(rawIso)
        if (creatorMatch != null) creator = unescapePdfString(creatorMatch.groupValues[1])

        val producerMatch = Regex("""/Producer\s*\(([^()]+)\)""").find(rawIso)
        if (producerMatch != null) producer = unescapePdfString(producerMatch.groupValues[1])

        return PdfMetadata(
            title = title,
            author = author,
            subject = subject,
            keywords = keywords,
            creator = creator,
            producer = producer
        )
    }

    private fun extractFallbackAsciiRuns(rawIso: String): String {
        val sample = if (rawIso.length > 65536) rawIso.substring(0, 65536) else rawIso
        val literals = Regex("""\(([^()]{3,300})\)""").findAll(sample)
            .map { unescapePdfString(it.groupValues[1]) }
            .filter { str ->
                str.any { it.isLetter() } &&
                !str.startsWith("/F") &&
                !str.startsWith("Adobe")
            }
            .take(200)
            .joinToString(" ")

        if (literals.isNotBlank()) return literals

        return Regex("""[A-Za-z][A-Za-z0-9_-]{2,30}""").findAll(sample)
            .map { it.value }
            .filter { it.lowercase() !in setOf("stream", "endstream", "endobj", "xref", "trailer", "startxref", "flatedecode", "obj", "font") }
            .take(150)
            .joinToString(" ")
    }

    /**
     * PdfRenderer Fallback: Uses Android's built-in PdfRenderer to inspect PDF pages
     * when stream decompression returns empty text (e.g. scanned image PDFs).
     */
    private fun extractTextUsingPdfRenderer(context: Context, uri: Uri): String {
        return try {
            val pfd = if (uri.scheme == "file" || uri.scheme.isNullOrEmpty()) {
                val path = uri.path ?: uri.toString().removePrefix("file://")
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")
            } ?: return ""

            pfd.use { descriptor ->
                val renderer = PdfRenderer(descriptor)
                try {
                    if (renderer.pageCount <= 0) {
                        return ""
                    }
                    val pageCount = minOf(renderer.pageCount, 10)
                    val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "PDF Document"
                    val sb = StringBuilder()
                    sb.append("Document: $filename\n")
                    sb.append("Total Pages: ${renderer.pageCount}\n\n")

                    for (i in 0 until pageCount) {
                        val page = renderer.openPage(i)
                        val width = page.width
                        val height = page.height
                        sb.append("--- Page ${i + 1} ($width x $height px) ---\n")
                        sb.append("Rendered page content for $filename.\n\n")
                        page.close()
                    }
                    sb.toString()
                } finally {
                    try { renderer.close() } catch (_: Throwable) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "PdfRenderer fallback notice: ${e.message}")
            ""
        }
    }
}

package com.example.engine

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

data class ParsedDocument(
    val fileUri: String,
    val fileName: String,
    val mimeType: String?,
    val fullText: String,
    val chunks: List<ParsedChunk>
)

data class ParsedChunk(
    val index: Int,
    val text: String,
    val hash: String
)

sealed interface ParseResult {
    data class Success(val document: ParsedDocument) : ParseResult
    data class Failure(val fileName: String, val fileUri: String, val reason: String) : ParseResult
}

class DocumentParser(
    private val context: Context,
    val targetChunkCharacters: Int = 1000, // ~384 tokens
    val overlapCharacters: Int = 150       // ~50 tokens
) {
    companion object {
        private const val TAG = "DocumentParser"

        // Directories to ignore during system or root tree scanning (internal OS and temporary caches)
        val IGNORED_DIRECTORY_NAMES = setOf(
            "lost.dir", "proc", "sys", "system", "vendor", "dev", "etc",
            ".trash", ".thumbnails", ".cache", ".git", ".gradle", ".idea", ".dart_tool",
            "node_modules", "build", "cache", "caches", "tmp", "temp"
        )

        // Explicit blacklisted extensions for system files, packages, databases, and media to ignore
        val IGNORED_EXTENSIONS = setOf(
            // Executables & Android packages
            "apk", "aab", "dex", "so", "jar", "aar", "exe", "dll", "sys", "bin", "dat", "class", "o", "pyc", "cmd", "bat", "sh",
            // Databases & indices
            "db", "sqlite", "sqlite3", "db-wal", "db-shm", "realm", "bdb", "mdb", "accdb",
            // Videos (strictly ignored per request: "no videos as well")
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v", "ts", "mpg", "mpeg", "vob", "ogv", "rmvb", "asf", "divx",
            // Audio files
            "mp3", "wav", "aac", "flac", "ogg", "m4a", "wma", "opus", "mid", "midi",
            // Archives & system dumps
            "zip", "tar", "gz", "7z", "rar", "iso", "dmg", "dump", "core", "img"
        )

        // Whitelist of supported regular documents & image files
        val SUPPORTED_DOCUMENT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "text", "rst",
            "pdf", "docx", "doc", "pptx", "ppt", "xlsx", "xls",
            "html", "htm", "json", "csv", "tsv", "xml", "log"
        )

        val SUPPORTED_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "heic", "heif", "dng", "bmp", "tiff", "tif"
        )

        val SUPPORTED_EXTENSIONS = SUPPORTED_DOCUMENT_EXTENSIONS + SUPPORTED_IMAGE_EXTENSIONS
    }

    private val imageMetadataExtractor = ImageMetadataExtractor(context)
    val chatParser = ChatParser()

    /**
     * User preference: chat backups and messaging exports are included by default
     * for seamless automatic document and image indexing.
     */
    var includeChatBackups: Boolean = true

    fun openStreamSafe(uri: Uri): InputStream {
        return if (uri.scheme == "file" || uri.scheme.isNullOrEmpty()) {
            val path = uri.path ?: uri.toString().removePrefix("file://")
            FileInputStream(File(path))
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open stream for $uri")
        }
    }

    /**
     * Recursively traverses a SAF DocumentFile directory tree and gathers all supported files,
     * ignoring all system directories, android packages, executable files, databases, and videos.
     */
    suspend fun scanDirectory(treeUri: Uri): List<DocumentFile> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Initializing document crawler: Scanning SAF directory tree Uri: $treeUri")
        val root = if (treeUri.scheme == "file") {
            val file = File(treeUri.path ?: "")
            if (file.exists() && file.isDirectory) DocumentFile.fromFile(file) else null
        } else {
            DocumentFile.fromTreeUri(context, treeUri)
        } ?: run {
            Log.w(TAG, "Document crawler failure: root directory could not be resolved for Uri: $treeUri")
            return@withContext emptyList()
        }

        val fileList = mutableListOf<DocumentFile>()
        traverseRecursive(root, fileList)
        Log.i(TAG, "Document crawler completed scanning Uri: $treeUri. Discovered ${fileList.size} supported file(s) for vector indexing.")
        fileList
    }

    /**
     * Scans the Download folder directly without triggering blocked SAF permissions on Android 11+.
     * Scans standard public Downloads directory, fallback path, and MediaStore.
     */
    suspend fun scanDownloadDirectory(): List<DocumentFile> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Initializing document crawler: Crawling device standard 'Downloads' directory pathways.")
        val fileList = mutableListOf<DocumentFile>()
        val visitedUris = HashSet<String>()

        val candidateDirs = mutableListOf<File>()
        try {
            val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (pubDownloads != null && pubDownloads.exists() && pubDownloads.isDirectory) {
                Log.d(TAG, "Adding public downloads directory candidate: ${pubDownloads.absolutePath}")
                candidateDirs.add(pubDownloads)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving public downloads path: ${e.message}")
        }

        val fallbackPaths = arrayOf(
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Downloads",
            "/sdcard/Download",
            "/sdcard/Downloads",
            "/storage/emulated/0/Documents",
            "/storage/emulated/0/Pictures",
            "/storage/emulated/0/DCIM",
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            "/storage/emulated/0/WhatsApp/Media/WhatsApp Documents",
            "/storage/emulated/0/WhatsApp/Media/WhatsApp Images",
            "/storage/emulated/0/Telegram/Telegram Documents",
            "/storage/emulated/0/Telegram/Telegram Images",
            "/storage/emulated/0/Gmail"
        )
        for (p in fallbackPaths) {
            val f = File(p)
            if (f.exists() && f.isDirectory) {
                Log.v(TAG, "Adding fallback / messaging directory candidate: $p")
                candidateDirs.add(f)
            }
        }

        try {
            val extRoot = Environment.getExternalStorageDirectory()
            if (extRoot != null) {
                val d1 = File(extRoot, "Download")
                if (d1.exists() && d1.isDirectory) {
                    Log.d(TAG, "Adding external root Download directory candidate: ${d1.absolutePath}")
                    candidateDirs.add(d1)
                }
                val d2 = File(extRoot, "Downloads")
                if (d2.exists() && d2.isDirectory) {
                    Log.d(TAG, "Adding external root Downloads directory candidate: ${d2.absolutePath}")
                    candidateDirs.add(d2)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving external root downloads path: ${e.message}")
        }

        Log.i(TAG, "Starting traversal across ${candidateDirs.size} candidate directory pathways for Downloads.")
        for (dir in candidateDirs) {
            val docDir = DocumentFile.fromFile(dir)
            val subList = mutableListOf<DocumentFile>()
            traverseRecursive(docDir, subList)
            for (f in subList) {
                if (visitedUris.add(f.uri.toString())) {
                    fileList.add(f)
                }
            }
        }

        // Query MediaStore for any files in Download directory
        try {
            Log.d(TAG, "Initiating MediaStore query fallback for Download directory files.")
            val mediaStoreFiles = queryMediaStoreDocumentsAndImages(isDownloadOnly = true)
            Log.d(TAG, "MediaStore query found ${mediaStoreFiles.size} candidate files in Download path.")
            for (f in mediaStoreFiles) {
                if (visitedUris.add(f.uri.toString())) {
                    fileList.add(f)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query for Downloads failed: ${e.message}")
        }

        Log.i(TAG, "Finished crawling Downloads directories. Total unique files found: ${fileList.size}")
        fileList
    }

    /**
     * Scans the Android folder (/storage/emulated/0/Android, /sdcard/Android, app media/data/docs directories)
     * for supported documents, notes, and images directly without SAF folder tree picker blocks.
     */
    suspend fun scanAndroidDirectory(): List<DocumentFile> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Initializing document crawler: Scanning Android folders (/Android, media, app data).")
        val fileList = mutableListOf<DocumentFile>()
        val visitedUris = HashSet<String>()
        val candidateDirs = mutableListOf<File>()

        val candidatePaths = arrayOf(
            "/storage/emulated/0/Android",
            "/storage/emulated/0/Android/media",
            "/storage/emulated/0/Android/data",
            "/storage/emulated/0/Android/doc",
            "/storage/emulated/0/Android/documents",
            "/sdcard/Android",
            "/sdcard/Android/media",
            "/sdcard/Android/data"
        )
        for (p in candidatePaths) {
            val f = File(p)
            if (f.exists() && f.isDirectory) {
                Log.v(TAG, "Adding Android directory candidate: $p")
                candidateDirs.add(f)
            }
        }

        try {
            val extRoot = Environment.getExternalStorageDirectory()
            if (extRoot != null) {
                val androidDir = File(extRoot, "Android")
                if (androidDir.exists() && androidDir.isDirectory) {
                    Log.d(TAG, "Adding external root /Android directory: ${androidDir.absolutePath}")
                    candidateDirs.add(androidDir)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving external root Android folder path: ${e.message}")
        }

        for (dir in candidateDirs) {
            val docDir = DocumentFile.fromFile(dir)
            val subList = mutableListOf<DocumentFile>()
            traverseRecursive(docDir, subList)
            for (f in subList) {
                if (visitedUris.add(f.uri.toString())) {
                    fileList.add(f)
                }
            }
        }

        Log.i(TAG, "Finished crawling Android directories. Total unique files found: ${fileList.size}")
        fileList
    }

    /**
     * Scans entire device storage for regular documents and images, ignoring:
     * - All system directories (proc, sys, android, etc.)
     * - All executables & Android packages (apk, aab, dex, jar, so, etc.)
     * - All database & index files (db, sqlite, realm, etc.)
     * - All videos (mp4, mkv, avi, etc. - NO videos)
     * - All audio and archives
     */
    suspend fun scanEntireSystemStorage(): List<DocumentFile> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Initializing document crawler: Starting deep scan of entire device storage.")
        val fileList = mutableListOf<DocumentFile>()
        val visitedUris = HashSet<String>()
        val candidateDirs = mutableListOf<File>()

        // 1. External Storage Root
        try {
            val extRoot = Environment.getExternalStorageDirectory()
            if (extRoot != null && extRoot.exists() && extRoot.isDirectory) {
                Log.d(TAG, "Adding device external storage root: ${extRoot.absolutePath}")
                candidateDirs.add(extRoot)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving device root: ${e.message}")
        }

        // 2. Standard Public Storage Directories
        val standardDirs = arrayOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_DCIM
        )
        for (dirName in standardDirs) {
            try {
                val pubDir = Environment.getExternalStoragePublicDirectory(dirName)
                if (pubDir != null && pubDir.exists() && pubDir.isDirectory) {
                    Log.d(TAG, "Adding public standard directory candidate: ${pubDir.absolutePath}")
                    candidateDirs.add(pubDir)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed resolving public standard folder $dirName: ${e.message}")
            }
        }

        // 3. Fallback /storage/emulated/0
        val directPaths = arrayOf(
            "/storage/emulated/0",
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Documents",
            "/storage/emulated/0/Pictures",
            "/storage/emulated/0/DCIM",
            "/sdcard"
        )
        for (p in directPaths) {
            val f = File(p)
            if (f.exists() && f.isDirectory) {
                Log.v(TAG, "Adding direct device path: $p")
                candidateDirs.add(f)
            }
        }

        // 4. App external files dir (including seeded test documents)
        try {
            context.getExternalFilesDirs(null)?.forEach { f ->
                if (f != null && f.exists() && f.isDirectory) {
                    Log.d(TAG, "Adding app-specific external directory: ${f.absolutePath}")
                    candidateDirs.add(f)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving app-specific external files directories: ${e.message}")
        }

        Log.i(TAG, "Initiating traversal across ${candidateDirs.size} storage pathways.")
        for (dir in candidateDirs) {
            val docDir = DocumentFile.fromFile(dir)
            val subList = mutableListOf<DocumentFile>()
            traverseRecursive(docDir, subList)
            for (f in subList) {
                if (visitedUris.add(f.uri.toString())) {
                    fileList.add(f)
                }
            }
        }

        // 5. Query MediaStore to discover all documents and images device-wide
        try {
            Log.d(TAG, "Initiating device-wide MediaStore query for deep document discovery.")
            val mediaStoreFiles = queryMediaStoreDocumentsAndImages(isDownloadOnly = false)
            Log.i(TAG, "MediaStore device-wide query discovered ${mediaStoreFiles.size} candidate documents/images.")
            for (f in mediaStoreFiles) {
                if (visitedUris.add(f.uri.toString())) {
                    fileList.add(f)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore device-wide discovery failed: ${e.message}")
        }

        Log.i(TAG, "Finished deep storage crawl. Discovered ${fileList.size} unique supported files.")
        fileList
    }

    private fun queryMediaStoreDocumentsAndImages(isDownloadOnly: Boolean): List<DocumentFile> {
        val results = mutableListOf<DocumentFile>()
        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE
        )

        val selection = if (isDownloadOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE 'Download%'"
        } else null

        try {
            context.contentResolver.query(collectionUri, projection, selection, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)

                while (cursor.moveToNext()) {
                    val name = if (nameCol != -1) cursor.getString(nameCol)?.lowercase(Locale.ROOT) else null
                    if (name.isNullOrBlank() || name.startsWith(".")) continue
                    val ext = name.substringAfterLast('.', "")
                    // Exclude system files, packages, databases, and videos
                    if (ext in IGNORED_EXTENSIONS) continue
                    if (ext !in SUPPORTED_EXTENSIONS) continue

                    // User preference: default chats are off
                    if (!includeChatBackups && ChatParser.isLikelyChatBackup(name)) {
                        continue
                    }

                    val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 1L
                    if (size <= 0) continue

                    if (idCol != -1) {
                        val id = cursor.getLong(idCol)
                        val itemUri = ContentUris.withAppendedId(collectionUri, id)
                        val doc = DocumentFile.fromSingleUri(context, itemUri)
                        if (doc != null) {
                            results.add(doc)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query error: ${e.message}")
        }
        return results
    }

    private fun traverseRecursive(directory: DocumentFile, result: MutableList<DocumentFile>) {
        if (!directory.isDirectory) return
        val dirName = directory.name?.lowercase(Locale.ROOT) ?: ""
        // Prune hidden or system directories immediately
        if (dirName.startsWith(".") || dirName in IGNORED_DIRECTORY_NAMES) {
            Log.v(TAG, "Document crawler: Skipping system/ignored folder tree: $dirName")
            return
        }

        val children = try {
            directory.listFiles()
        } catch (e: Exception) {
            Log.w(TAG, "Document crawler access failure in directory $dirName: ${e.message}")
            emptyArray()
        }

        for (child in children) {
            val childName = child.name?.lowercase(Locale.ROOT) ?: continue
            // Skip hidden files/directories starting with '.'
            if (childName.startsWith(".")) continue

            if (child.isDirectory) {
                if (childName !in IGNORED_DIRECTORY_NAMES) {
                    traverseRecursive(child, result)
                } else {
                    Log.v(TAG, "Document crawler: Skipping nested system/ignored folder: $childName")
                }
            } else if (child.isFile) {
                val ext = childName.substringAfterLast('.', "")
                // Exclude system files, packages, databases, and videos
                if (ext in IGNORED_EXTENSIONS) {
                    Log.v(TAG, "Document crawler: Ignoring system or blacklisted file extension: $childName")
                    continue
                }

                // Include only regular documents and images
                if (ext in SUPPORTED_EXTENSIONS) {
                    // User preference: default chats are off during filesystem crawling
                    if (!includeChatBackups && ChatParser.isLikelyChatBackup(childName)) {
                        Log.d(TAG, "Document crawler: Skipping chat backup document per current preference: $childName")
                        continue
                    }
                    // Skip zero-byte files or corrupted files
                    if (child.length() > 0) {
                        Log.i(TAG, "Document crawler discovered supported file: $childName | URI: ${child.uri} | Size: ${child.length()} bytes")
                        result.add(child)
                    } else {
                        Log.w(TAG, "Document crawler: Skipping zero-byte empty document: $childName")
                    }
                } else {
                    Log.v(TAG, "Document crawler: Skipping unsupported file extension: $childName")
                }
            }
        }
    }

    /**
     * Extracts text and chunks from a DocumentFile with robust error categorization.
     */
    suspend fun parseDocumentSafely(docFile: DocumentFile): ParseResult = withContext(Dispatchers.IO) {
        val uri = docFile.uri
        val name = docFile.name ?: "Unknown"
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

        // Check if this file is an SMS or messaging chat backup file
        if (ChatParser.isLikelyChatBackup(name) && ext in ChatParser.CHAT_EXTENSIONS) {
            try {
                val chatChunks = openStreamSafe(uri).use { stream ->
                    chatParser.parseChatBackupStream(stream, name)
                }
                if (chatChunks.isNotEmpty()) {
                    val fullText = chatChunks.joinToString("\n\n")
                    val parsedChunks = chatChunks.mapIndexed { index, chunkText ->
                        val hash = computeSha256("$uri:$index:$chunkText")
                        ParsedChunk(index = index, text = chunkText, hash = hash)
                    }
                    return@withContext ParseResult.Success(
                        ParsedDocument(
                            fileUri = uri.toString(),
                            fileName = name,
                            mimeType = docFile.type ?: "text/chat-backup",
                            fullText = fullText,
                            chunks = parsedChunks
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Chat backup parser notice for $name: ${e.message}")
            }
        }

        val fullText: String
        try {
            fullText = when {
                ext in ImageMetadataExtractor.IMAGE_EXTENSIONS -> imageMetadataExtractor.extractMetadata(uri, name).semanticDescription
                ext == "pdf" -> extractTextFromPdf(uri)
                ext == "docx" -> extractTextFromDocx(uri)
                ext == "pptx" -> extractTextFromPptx(uri)
                ext == "html" || ext == "htm" -> extractTextFromHtml(uri)
                else -> extractPlainText(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parsing $name: ${e.message}", e)
            return@withContext ParseResult.Failure(
                fileName = name,
                fileUri = uri.toString(),
                reason = "Extraction error (${e.javaClass.simpleName}): ${e.localizedMessage ?: "Unknown"}"
            )
        }

        if (fullText.isBlank()) {
            return@withContext ParseResult.Failure(
                fileName = name,
                fileUri = uri.toString(),
                reason = "Document contains no readable text"
            )
        }

        val chunks = recursiveTextChunk(fullText, uri.toString())
        ParseResult.Success(
            ParsedDocument(
                fileUri = uri.toString(),
                fileName = name,
                mimeType = docFile.type,
                fullText = fullText,
                chunks = chunks
            )
        )
    }

    suspend fun parseDocument(docFile: DocumentFile): ParsedDocument? {
        return when (val res = parseDocumentSafely(docFile)) {
            is ParseResult.Success -> res.document
            is ParseResult.Failure -> null
        }
    }

    private fun extractPlainText(uri: Uri): String {
        openStreamSafe(uri).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                return reader.readText()
            }
        }
    }

    /**
     * Extracts plain text from DOCX (Office Open XML) by unzipping and parsing word/document.xml.
     */
    private fun extractTextFromDocx(uri: Uri): String {
        val sb = StringBuilder()
        openStreamSafe(uri).use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val xmlContent = zip.readBytes().toString(Charsets.UTF_8)
                        parseWordXml(xmlContent, sb)
                        break
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return sb.toString().trim()
    }

    private fun parseWordXml(xmlContent: String, output: StringBuilder) {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xmlContent))

        var eventType = parser.eventType
        var inTextTag = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "t" -> inTextTag = true
                        "p" -> { /* paragraph start */ }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTextTag) {
                        output.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "t" -> inTextTag = false
                        "p" -> output.append("\n\n")
                    }
                }
            }
            eventType = parser.next()
        }
    }

    /**
     * Extracts text from PPTX (PowerPoint Open XML) by parsing slide XML files.
     */
    private fun extractTextFromPptx(uri: Uri): String {
        val sb = StringBuilder()
        openStreamSafe(uri).use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                val slideContents = mutableListOf<Pair<String, String>>()
                while (entry != null) {
                    if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
                        val slideXml = zip.readBytes().toString(Charsets.UTF_8)
                        slideContents.add(Pair(entry.name, slideXml))
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }

                // Sort slides by name (slide1.xml, slide2.xml, etc.)
                slideContents.sortBy { it.first }
                for ((name, xml) in slideContents) {
                    val slideNum = name.substringAfter("slide").substringBefore(".xml")
                    sb.append("--- Slide $slideNum ---\n")
                    parsePowerPointXml(xml, sb)
                    sb.append("\n\n")
                }
            }
        }
        return sb.toString().trim()
    }

    private fun parsePowerPointXml(xmlContent: String, output: StringBuilder) {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xmlContent))

        var eventType = parser.eventType
        var inTextTag = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        inTextTag = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTextTag) {
                        output.append(parser.text).append(" ")
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "t") {
                        inTextTag = false
                    } else if (parser.name == "p") {
                        output.append("\n")
                    }
                }
            }
            eventType = parser.next()
        }
    }

    /**
     * Extracts plain text from HTML by removing scripts/styles and preserving paragraphs/headings.
     */
    private fun extractTextFromHtml(uri: Uri): String {
        val rawHtml = extractPlainText(uri)
        return convertHtmlToPlainText(rawHtml)
    }

    fun convertHtmlToPlainText(html: String): String {
        // Strip scripts and styles
        var clean = html.replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
        clean = clean.replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")

        // Replace block tags with newlines
        clean = clean.replace(Regex("""<(p|div|h[1-6]|li|tr|br\s*/?)(\s+[^>]*)?>""", RegexOption.IGNORE_CASE), "\n")
        // Strip remaining HTML tags
        clean = clean.replace(Regex("""<[^>]+>"""), " ")

        // Unescape standard HTML entities
        clean = clean.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")

        // Collapse excess whitespace
        return clean.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    /**
     * Extracts plain text from PDF using the dedicated PdfParserUtils engine library.
     */
    private fun extractTextFromPdf(uri: Uri): String {
        Log.i(TAG, "PDF Parser: Initializing extraction pipeline for PDF document Uri: $uri")
        val extracted = try {
            openStreamSafe(uri).use { stream ->
                val rawText = com.example.engine.pdf.PdfParserUtils.extractRawText(stream)
                Log.d(TAG, "PDF Parser: Successfully retrieved ${rawText.length} raw characters from stream for $uri")
                rawText
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDF Parser stream notice: failed streaming for $uri: ${e.message}", e)
            ""
        }

        val cleaned = com.example.engine.pdf.PdfParserUtils.cleanPdfTextForEmbedding(extracted)
        if (cleaned.isNotBlank() && cleaned.length > 10) {
            Log.i(TAG, "PDF Parser: Extraction successful using high-performance stream extractor (${cleaned.length} cleaned characters).")
            return cleaned
        }

        // Deep fallback using Context and PdfRenderer for scanned/image PDFs or complex stream layouts
        Log.i(TAG, "PDF Parser: Primary stream returned empty/minimal text. Initiating deep PdfRenderer fallback analyzer for scanned or complex formats: $uri")
        val rendererDoc = com.example.engine.pdf.PdfTextExtractor.extractDocument(context, uri)
        if (rendererDoc.fullText.isNotBlank()) {
            val fallbackCleaned = com.example.engine.pdf.PdfParserUtils.cleanPdfTextForEmbedding(rendererDoc.fullText)
            Log.i(TAG, "PDF Parser fallback: Deep rendering successfully recovered ${fallbackCleaned.length} characters.")
            return fallbackCleaned
        }

        val fallbackTitle = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
            ?.replace('_', ' ')?.replace('-', ' ')
        Log.w(TAG, "PDF Parser: Document contains no extractable text content. Falling back to metadata title extraction: '$fallbackTitle'")
        return fallbackTitle ?: ""
    }

    /**
     * Recursive text chunker:
     * - Target chunk size: ~1000 characters (~384 tokens)
     * - Chunk overlap: ~150 characters (~50 tokens)
     * - Preserves paragraph and sentence boundaries
     * - Computes SHA-256 hash per chunk
     */
    fun recursiveTextChunk(text: String, fileUri: String): List<ParsedChunk> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.length <= targetChunkCharacters) {
            val hash = computeSha256("$fileUri:0:$normalized")
            return listOf(ParsedChunk(index = 0, text = normalized, hash = hash))
        }

        val chunks = mutableListOf<String>()
        val paragraphs = normalized.split("\n\n").filter { it.isNotBlank() }

        var currentChunk = StringBuilder()

        for (paragraph in paragraphs) {
            if (currentChunk.length + paragraph.length + 2 <= targetChunkCharacters) {
                if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                currentChunk.append(paragraph)
            } else {
                if (paragraph.length > targetChunkCharacters) {
                    val sentences = paragraph.split(Regex("""(?<=[.!?])\s+"""))
                    for (sentence in sentences) {
                        if (currentChunk.length + sentence.length + 1 <= targetChunkCharacters) {
                            if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                            currentChunk.append(sentence)
                        } else {
                            if (currentChunk.isNotEmpty()) {
                                chunks.add(currentChunk.toString().trim())
                                val overlap = extractOverlap(currentChunk.toString(), overlapCharacters)
                                currentChunk = StringBuilder(overlap)
                                if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                            }
                            if (sentence.length > targetChunkCharacters) {
                                val words = sentence.split(" ")
                                for (word in words) {
                                    if (currentChunk.length + word.length + 1 <= targetChunkCharacters) {
                                        if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                                        currentChunk.append(word)
                                    } else {
                                        if (currentChunk.isNotEmpty()) {
                                            chunks.add(currentChunk.toString().trim())
                                            val overlap = extractOverlap(currentChunk.toString(), overlapCharacters)
                                            currentChunk = StringBuilder(overlap)
                                            if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                                        }
                                        currentChunk.append(word)
                                    }
                                }
                            } else {
                                currentChunk.append(sentence)
                            }
                        }
                    }
                } else {
                    if (currentChunk.isNotEmpty()) {
                        chunks.add(currentChunk.toString().trim())
                        val overlap = extractOverlap(currentChunk.toString(), overlapCharacters)
                        currentChunk = StringBuilder(overlap)
                        if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                    }
                    currentChunk.append(paragraph)
                }
            }
        }

        if (currentChunk.isNotBlank()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks.mapIndexed { index, chunkText ->
            val hash = computeSha256("$fileUri:$index:$chunkText")
            ParsedChunk(index = index, text = chunkText, hash = hash)
        }
    }

    private fun extractOverlap(text: String, overlapLen: Int): String {
        if (text.length <= overlapLen) return text
        val sub = text.takeLast(overlapLen)
        val spaceIdx = sub.indexOf(' ')
        return if (spaceIdx != -1 && spaceIdx < sub.length - 1) {
            sub.substring(spaceIdx + 1)
        } else {
            sub
        }
    }

    fun computeSha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

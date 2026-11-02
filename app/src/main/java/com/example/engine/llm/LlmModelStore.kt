package com.example.engine.llm

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.example.privacy.NetworkAudit
import com.example.privacy.NetworkGuard
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Where chat models live on the phone, which one is selected, and how they are downloaded.
 *
 * Files are stored in `<app files>/llm_models/<model id>/<file>.task`. A model can also be `adb push`ed to
 * `<app external files>/llm_models/<model id>/`. Downloads go to a `.part` file that is resumed with an HTTP
 * Range request after a lost connection, and is only renamed into place once complete and validated.
 */
class LlmModelStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "LlmModelStore"
        private const val PREFS = "llm_chat"
        private const val ROOT = "llm_models"
        private const val KEY_SELECTED = "selected_model"
        private const val KEY_AUTO_DOWNLOAD_OFF = "auto_download_off"
        private const val KEY_ALLOW_MOBILE_DATA = "allow_mobile_data"
        private const val PART_SUFFIX = ".part"
        /** Space kept free on top of the model itself so the phone (and the index) never runs out. */
        private const val FREE_SPACE_MARGIN = 200L * 1024 * 1024

        /** `.task` bundles are zip archives. */
        internal fun looksLikeTaskFile(header: ByteArray): Boolean =
            header.size >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
    }

    // --- selection & preferences ---

    var selectedModel: ChatModel
        get() = ChatModel.fromId(prefs.getString(KEY_SELECTED, null)) ?: ChatModel.DEFAULT
        set(value) = prefs.edit().putString(KEY_SELECTED, value.id).apply()

    /** True once the user cancelled or deleted the default model: it is then not downloaded again by itself. */
    var autoDownloadDisabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD_OFF, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_OFF, value).apply()

    /** Model downloads wait for Wi-Fi unless the user allowed mobile data. */
    var allowMobileData: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_MOBILE_DATA, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_MOBILE_DATA, value).apply()

    // --- files ---

    private fun internalDir(model: ChatModel) = File(File(appContext.filesDir, ROOT), model.directory)

    private fun externalDir(model: ChatModel): File? =
        appContext.getExternalFilesDir(null)?.let { File(File(it, ROOT), model.directory) }

    /** The installed `.task` file of [model], or null. */
    fun modelFile(model: ChatModel): File? =
        listOfNotNull(internalDir(model), externalDir(model)).asSequence()
            .mapNotNull { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".task") }?.maxByOrNull { it.length() } }
            .firstOrNull()

    fun isInstalled(model: ChatModel): Boolean = modelFile(model) != null

    fun installedModels(): List<ChatModel> = ChatModel.entries.filter { isInstalled(it) }

    fun installedSizeBytes(model: ChatModel): Long = modelFile(model)?.length() ?: 0L

    fun delete(model: ChatModel) {
        internalDir(model).deleteRecursively()
    }

    fun freeBytes(): Long = try {
        StatFs(appContext.filesDir.absolutePath).availableBytes
    } catch (_: Exception) {
        Long.MAX_VALUE
    }

    // --- download ---

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .addNetworkInterceptor(NetworkGuard.interceptor(NetworkAudit.Purpose.MODEL_DOWNLOAD))
            .build()
    }

    class DownloadException(message: String, val retryable: Boolean) : Exception(message)

    /**
     * Downloads [model] (resuming a previous partial download). [onProgress] gets bytes done and total
     * (total is an estimate until the server answers). Returns the installed file; throws [DownloadException].
     */
    suspend fun download(model: ChatModel, onProgress: suspend (done: Long, total: Long) -> Unit): File {
        modelFile(model)?.let { return it }
        val dir = internalDir(model).apply { mkdirs() }
        val fileName = resolveFileName(model)
        val target = File(dir, fileName)
        val part = File(dir, fileName + PART_SUFFIX)
        // Leftovers of another file name (the repository renamed its file) are useless now.
        dir.listFiles()?.filter { it.name != part.name && it.name != target.name }?.forEach { it.delete() }

        val needed = model.approxSizeBytes - part.length() + FREE_SPACE_MARGIN
        if (freeBytes() < needed) {
            throw DownloadException(
                "Not enough free space: ${model.shortName} needs about ${ChatModel.formatSize(needed)} free.",
                retryable = false
            )
        }

        val already = if (part.isFile) part.length() else 0L
        val request = Request.Builder()
            .url(model.downloadUrl(fileName))
            .header("User-Agent", "LocalDocFinder/2.0 (Android; OnDeviceChat)")
            .apply { if (already > 0) header("Range", "bytes=$already-") }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val append = response.code == 206 && already > 0
                when {
                    response.code == 416 && already > 0 -> { /* the .part is already complete */ }
                    response.code == 401 || response.code == 403 ->
                        throw DownloadException("${model.shortName} needs a Hugging Face login; choose another model.", false)
                    response.code == 404 -> throw DownloadException("${model.shortName} is no longer available on Hugging Face.", false)
                    !response.isSuccessful -> throw DownloadException("Download failed: HTTP ${response.code}", response.code >= 500 || response.code == 429)
                    else -> {
                        val body = response.body ?: throw DownloadException("Empty response", true)
                        val start = if (append) already else 0L
                        val total = if (body.contentLength() > 0) start + body.contentLength() else model.approxSizeBytes
                        var done = start
                        var lastReport = 0L
                        body.byteStream().use { input ->
                            FileOutputStream(part, append).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    done += n
                                    if (done - lastReport >= 1024 * 1024) {
                                        lastReport = done
                                        onProgress(done, total)
                                    }
                                }
                            }
                        }
                        onProgress(done, total)
                        if (body.contentLength() > 0 && done < total) throw DownloadException("Connection lost", true)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download of ${model.id} interrupted at ${part.length()} bytes: ${e.message}")
            throw DownloadException("Connection lost: ${e.message}", true)
        }

        val header = part.inputStream().use { ByteArray(4).also { buf -> it.read(buf) } }
        if (!looksLikeTaskFile(header)) {
            part.delete()
            throw DownloadException("The downloaded file is not a model bundle.", false)
        }
        if (!part.renameTo(target)) throw DownloadException("Could not store the model file.", false)
        Log.i(TAG, "Installed ${model.id}: ${target.name} (${target.length() / (1024 * 1024)} MB)")
        return target
    }

    /** Bytes of an unfinished download, so the UI can show "resumes at 40%". */
    fun partialBytes(model: ChatModel): Long =
        internalDir(model).listFiles { f -> f.name.endsWith(PART_SUFFIX) }?.sumOf { it.length() } ?: 0L

    /**
     * The expected file name, checked against the repository's file list when there is a partial download
     * of nothing yet: if Hugging Face renamed the file, the closest `.task` file is used instead.
     */
    private fun resolveFileName(model: ChatModel): String {
        val dir = internalDir(model)
        dir.listFiles { f -> f.name.endsWith(PART_SUFFIX) }?.firstOrNull()?.let { return it.name.removeSuffix(PART_SUFFIX) }
        val head = Request.Builder().url(model.downloadUrl()).head().build()
        val exists = try {
            client.newCall(head).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            return model.fileName // offline: the download itself will report the error and be retried
        }
        if (exists) return model.fileName
        return try {
            val files = listRepoFiles(model)
            ChatModel.pickTaskFile(files, model.fileName)
                ?: throw DownloadException("${model.shortName} has no downloadable model file.", false)
        } catch (e: DownloadException) {
            throw e
        } catch (_: Exception) {
            model.fileName
        }
    }

    private fun listRepoFiles(model: ChatModel): List<String> {
        val request = Request.Builder().url(model.repoApiUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val json = response.body?.string() ?: return emptyList()
            return parseSiblings(json)
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun parseSiblings(json: String): List<String> {
        val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
        val root = adapter.fromJson(json) ?: return emptyList()
        val siblings = root["siblings"] as? List<Map<String, Any?>> ?: return emptyList()
        return siblings.mapNotNull { it["rfilename"] as? String }
    }
}

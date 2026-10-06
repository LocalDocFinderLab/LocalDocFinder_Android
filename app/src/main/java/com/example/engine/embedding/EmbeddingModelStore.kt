package com.example.engine.embedding

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.engine.model.EmbeddingModelType
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Finds, imports and removes the on-disk files of a TFLite embedding model.
 *
 * A model is two files in a folder named [EmbeddingModelType.directory]:
 *  - `model.tflite` — the exported transformer
 *  - `vocab.txt` — its WordPiece vocabulary
 *
 * They are looked up in, in order:
 *  1. `<app files>/embedding_models/<dir>/` — where [importModel] puts imported files
 *  2. `<app external files>/embedding_models/<dir>/` — so a developer can `adb push` them
 *  3. the APK assets, `models/<dir>/` — if the model was bundled at build time
 *
 * Nothing is downloaded by the app itself; model files only ever arrive from the user or the build.
 */
class EmbeddingModelStore(private val context: Context) {

    companion object {
        private const val TAG = "EmbeddingModelStore"
        const val MODEL_FILE = "model.tflite"
        const val VOCAB_FILE = "vocab.txt"
        private const val ROOT = "embedding_models"
        private const val ASSET_ROOT = "models"

        /** Every `.tflite` flatbuffer carries the file identifier "TFL3" at byte offset 4. */
        internal fun looksLikeTflite(header: ByteArray): Boolean =
            header.size >= 8 && header[4] == 'T'.code.toByte() && header[5] == 'F'.code.toByte() &&
                header[6] == 'L'.code.toByte() && header[7] == '3'.code.toByte()

        internal fun looksLikeVocab(text: String): Boolean =
            text.contains(WordPieceTokenizer.CLS) && text.contains(WordPieceTokenizer.SEP) && text.contains(WordPieceTokenizer.UNK)
    }

    private fun internalDir(model: EmbeddingModelType) = File(File(context.filesDir, ROOT), model.directory.orEmpty())

    private fun externalDir(model: EmbeddingModelType): File? =
        context.getExternalFilesDir(null)?.let { File(File(it, ROOT), model.directory.orEmpty()) }

    private fun assetDir(model: EmbeddingModelType) = "$ASSET_ROOT/${model.directory.orEmpty()}"

    private fun fileDirWithModel(model: EmbeddingModelType): File? =
        listOfNotNull(internalDir(model), externalDir(model)).firstOrNull {
            File(it, MODEL_FILE).isFile && File(it, VOCAB_FILE).isFile
        }

    private fun hasBundledAssets(model: EmbeddingModelType): Boolean = try {
        val names = context.assets.list(assetDir(model))?.toSet().orEmpty()
        MODEL_FILE in names && VOCAB_FILE in names
    } catch (_: Exception) {
        false
    }

    /** True when the model's files can be loaded. The built-in embedder is always installed. */
    fun isInstalled(model: EmbeddingModelType): Boolean =
        model.isBuiltIn || fileDirWithModel(model) != null || hasBundledAssets(model)

    /** Maps the `.tflite` into memory (no copy onto the Java heap). Throws if the model is not installed. */
    fun openModel(model: EmbeddingModelType): ByteBuffer {
        require(!model.isBuiltIn) { "${model.id} has no model file" }
        fileDirWithModel(model)?.let { dir ->
            java.io.RandomAccessFile(File(dir, MODEL_FILE), "r").use { raf ->
                return raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            }
        }
        val fd = context.assets.openFd("${assetDir(model)}/$MODEL_FILE")
        fd.use {
            java.io.FileInputStream(it.fileDescriptor).use { stream ->
                return stream.channel.map(FileChannel.MapMode.READ_ONLY, it.startOffset, it.declaredLength)
            }
        }
    }

    fun openVocab(model: EmbeddingModelType): InputStream {
        require(!model.isBuiltIn) { "${model.id} has no vocabulary" }
        fileDirWithModel(model)?.let { return File(it, VOCAB_FILE).inputStream() }
        return context.assets.open("${assetDir(model)}/$VOCAB_FILE")
    }

    /**
     * Copies a model the user picked into private app storage. Both files are validated first
     * (`.tflite` flatbuffer header, vocabulary containing the BERT special tokens) and installed
     * atomically, so a bad pick never leaves a half-installed model.
     *
     * @return null on success, otherwise a message to show the user
     */
    fun importModel(model: EmbeddingModelType, tflite: Uri, vocab: Uri): String? {
        if (model.isBuiltIn) return "The built-in embedder has no files to import."
        val dir = internalDir(model)
        val tmpDir = File(dir.parentFile, "${dir.name}.tmp")
        return try {
            tmpDir.deleteRecursively()
            tmpDir.mkdirs()

            val modelFile = File(tmpDir, MODEL_FILE)
            context.contentResolver.openInputStream(tflite)?.use { input ->
                modelFile.outputStream().use { input.copyTo(it) }
            } ?: return "Could not open the model file."
            val header = modelFile.inputStream().use { ByteArray(8).also { buf -> it.read(buf) } }
            if (!looksLikeTflite(header)) return "That file is not a TensorFlow Lite model."

            val vocabFile = File(tmpDir, VOCAB_FILE)
            context.contentResolver.openInputStream(vocab)?.use { input ->
                vocabFile.outputStream().use { input.copyTo(it) }
            } ?: return "Could not open the vocabulary file."
            if (!looksLikeVocab(vocabFile.readText())) return "That file is not a BERT vocab.txt."

            dir.deleteRecursively()
            if (!tmpDir.renameTo(dir)) return "Could not store the model files."
            Log.i(TAG, "Imported ${model.id} (${File(dir, MODEL_FILE).length() / 1024} KiB)")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Import of ${model.id} failed: ${e.message}")
            "Import failed: ${e.message}"
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** Deletes imported files for [model] (bundled assets and adb-pushed files are left alone). */
    fun removeImported(model: EmbeddingModelType) {
        if (!model.isBuiltIn) internalDir(model).deleteRecursively()
    }
}

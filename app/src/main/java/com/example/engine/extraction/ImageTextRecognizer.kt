package com.example.engine.extraction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the text inside pictures (photos of receipts, screenshots, whiteboards) and inside scanned PDFs
 * that have no text layer, using ML Kit's bundled on-device text recognizer. The model ships inside the
 * APK, so nothing is downloaded and no pixels leave the phone.
 *
 * Every call blocks, so call it from a background thread. Failures return an empty string: a picture
 * whose text can't be read is still indexed by its name and photo details.
 */
class ImageTextRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "ImageTextRecognizer"

        /** Larger images are scaled down first; text stays readable and memory stays bounded. */
        private const val MAX_SIDE = 2048
        private const val TIMEOUT_SECONDS = 20L

        /** Scanned PDFs: only the first pages are read, to stay inside the per-file indexing time limit. */
        const val MAX_SCANNED_PDF_PAGES = 12
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Text found in the image at [uri], one line per recognized line; "" when there is none. */
    fun recognize(uri: Uri): String {
        val bitmap = try {
            decodeScaled(uri)
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) System.gc()
            Log.w(TAG, "Could not decode $uri for text recognition: ${t.message}")
            return ""
        }
        return try {
            recognize(bitmap).also { if (it.isNotEmpty()) Log.i(TAG, "Recognized ${it.length} characters in $uri") }
        } finally {
            bitmap.recycle()
        }
    }

    fun recognize(bitmap: Bitmap): String = try {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), TIMEOUT_SECONDS, TimeUnit.SECONDS)
        result.textBlocks.joinToString("\n") { block -> block.lines.joinToString("\n") { it.text.trim() } }.trim()
    } catch (t: Throwable) {
        Log.w(TAG, "Text recognition failed: ${t.message}")
        ""
    }

    /**
     * Renders the first pages of a PDF that has no text layer and reads them. Returns only pages where text
     * was found; empty when nothing could be read.
     */
    fun recognizePdfPages(uri: Uri, keepGoing: () -> Boolean = { true }): List<ExtractedPage> {
        val pages = ArrayList<ExtractedPage>()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd: ParcelFileDescriptor ->
                PdfRenderer(pfd).use { renderer ->
                    val count = min(renderer.pageCount, MAX_SCANNED_PDF_PAGES)
                    for (i in 0 until count) {
                        if (!keepGoing()) break
                        renderer.openPage(i).use { page ->
                            // Render at roughly 200 dpi (PDF units are 1/72 inch), capped to MAX_SIDE.
                            val scale = min(200f / 72f, MAX_SIDE.toFloat() / max(page.width, page.height))
                            val w = max(1, (page.width * scale).toInt())
                            val h = max(1, (page.height * scale).toInt())
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val text = recognize(bmp)
                                if (text.isNotBlank()) pages.add(ExtractedPage(i + 1, text))
                            } finally {
                                bmp.recycle()
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) System.gc()
            Log.w(TAG, "Could not read scanned PDF $uri: ${t.message}")
        }
        if (pages.isNotEmpty()) Log.i(TAG, "Read ${pages.size} scanned page(s) from $uri")
        return pages
    }

    /** Decodes with EXIF rotation applied (ImageDecoder does that) and downsampled to at most [MAX_SIDE]. */
    private fun decodeScaled(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                val f = MAX_SIDE.toFloat() / longest
                decoder.setTargetSize(max(1, (info.size.width * f).toInt()), max(1, (info.size.height * f).toInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
}

package com.example.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Opens an indexed file in whatever app handles it.
 *
 * Files found by folder and Downloads scans are stored as `file://` URIs. Android refuses to hand those to
 * another app (it throws FileUriExposedException), so they are shared through this app's FileProvider as
 * `content://` URIs instead. Folder-picker files already are `content://` URIs and are passed on as they are.
 */
object DocumentOpener {

    private const val TAG = "DocumentOpener"

    /**
     * @param showErrors show a toast explaining why the file could not be opened.
     * @return true if another app was started.
     */
    fun open(context: Context, uriString: String, showErrors: Boolean = true): Boolean {
        fun fail(message: String): Boolean {
            if (showErrors) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return false
        }

        val parsed = try { Uri.parse(uriString) } catch (_: Throwable) { return fail("This file's location is invalid") }
        val displayName = parsed.lastPathSegment?.substringAfterLast('/') ?: "this file"

        val shareUri: Uri
        if (parsed.scheme == "file" || parsed.scheme.isNullOrEmpty()) {
            val file = File(parsed.path ?: uriString.removePrefix("file://"))
            if (!file.exists()) return fail("File not found. It may have been moved or deleted: $displayName")
            shareUri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Throwable) {
                Log.w(TAG, "Could not share $file: ${e.message}")
                return fail("Cannot share this file with another app: $displayName")
            }
        } else {
            shareUri = parsed
        }

        val mime = mimeTypeFor(context, shareUri, displayName)
        return try {
            context.startActivity(viewIntent(shareUri, mime))
            true
        } catch (_: ActivityNotFoundException) {
            // Nothing claims this exact type: let the user pick any app that can take a file.
            try {
                context.startActivity(Intent.createChooser(viewIntent(shareUri, "*/*"), "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Throwable) {
                fail("No app installed can open this type of file")
            }
        } catch (_: SecurityException) {
            fail("Permission to this file was lost. Add its folder again from the main screen.")
        } catch (e: Throwable) {
            Log.w(TAG, "Could not open $uriString: ${e.message}")
            fail("Cannot open this file: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    private fun viewIntent(uri: Uri, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun mimeTypeFor(context: Context, uri: Uri, displayName: String): String {
        val fromResolver = try { context.contentResolver.getType(uri) } catch (_: Throwable) { null }
        if (!fromResolver.isNullOrBlank() && fromResolver != "application/octet-stream") return fromResolver
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: fromResolver ?: "*/*"
    }
}

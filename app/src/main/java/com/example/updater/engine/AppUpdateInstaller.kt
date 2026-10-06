package com.example.updater.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object AppUpdateInstaller {
    private const val TAG = "AppUpdateInstaller"

    /**
     * Checks if the app currently has permission to install unknown apps (APKs directly).
     * On Android 8.0+ (Oreo / API 26+), requires user authorization in system settings.
     */
    fun canInstallApks(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Opens the system settings screen allowing the user to grant "Install unknown apps" permission
     * specifically for this application.
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open specific package unknown app settings, opening general settings", e)
                try {
                    val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (fallbackError: Exception) {
                    Toast.makeText(context, "Please enable 'Install unknown apps' in System Settings", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Triggers the Android package installer UI with the downloaded APK file.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                Toast.makeText(context, "Update APK file is missing or corrupted", Toast.LENGTH_SHORT).show()
                return false
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            installApkUri(context, apkUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate APK install: ${e.message}", e)
            Toast.makeText(context, "Install failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * Triggers the Android package installer UI with a FileProvider content:// URI.
     */
    fun installApkUri(context: Context, apkUri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start package installer: ${e.message}", e)
            Toast.makeText(context, "Could not launch package installer: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            false
        }
    }
}

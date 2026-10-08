package com.example.updater.model

import android.net.Uri
import java.io.File

enum class UpdateSourceType(val displayName: String, val description: String) {
    GITHUB_RELEASES(
        "GitHub Releases",
        "Checks latest GitHub Release tags and APK assets (owner/repo)"
    ),
    JSON_MANIFEST(
        "Direct JSON URL",
        "Custom JSON update metadata feed from your own server or raw CDN"
    ),
    SIMULATION(
        "Test Simulation",
        "Simulate updates locally to test the in-app update experience"
    )
}

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseTitle: String = "Version $versionName",
    val releaseNotes: String,
    val downloadUrl: String,
    val fileSizeBytes: Long = 0L,
    val sha256Hash: String? = null,
    val isMandatory: Boolean = false,
    val publishedAt: String = "",
    val sourceType: UpdateSourceType = UpdateSourceType.GITHUB_RELEASES,
    val sourceName: String = "",
    val assetFileName: String = "update.apk"
) {
    val formattedFileSize: String
        get() {
            if (fileSizeBytes <= 0) return "Direct APK"
            val mb = fileSizeBytes.toDouble() / (1024 * 1024)
            return if (mb >= 1.0) {
                String.format("%.1f MB", mb)
            } else {
                val kb = fileSizeBytes.toDouble() / 1024
                String.format("%.0f KB", kb)
            }
        }
}

sealed interface UpdateCheckResult {
    data object Idle : UpdateCheckResult
    data object Checking : UpdateCheckResult
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateCheckResult
    data class UpToDate(
        val currentVersionCode: Int,
        val currentVersionName: String,
        val checkedAtMillis: Long = System.currentTimeMillis()
    ) : UpdateCheckResult
    data class Error(val message: String, val throwable: Throwable? = null) : UpdateCheckResult
}

sealed interface DownloadState {
    data object NotStarted : DownloadState
    data object Connecting : DownloadState
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percent: Int,
        val speedFormatted: String = ""
    ) : DownloadState
    data class ReadyToInstall(
        val apkUri: Uri,
        val apkFile: File,
        val info: AppUpdateInfo
    ) : DownloadState
    data class Failed(val error: String) : DownloadState
}

data class UpdateConfig(
    val sourceType: UpdateSourceType = UpdateSourceType.GITHUB_RELEASES,
    val githubRepo: String = "localdocfinderlab/localdocfinder_android",
    val customManifestUrl: String = "https://raw.githubusercontent.com/localdocfinderlab/localdocfinder_android/main/update.json",
    val autoCheckEnabled: Boolean = true,
    val checkOnWifiOnly: Boolean = false,
    val lastCheckTimestamp: Long = 0L,
    val dismissedVersionCode: Int = 0
)

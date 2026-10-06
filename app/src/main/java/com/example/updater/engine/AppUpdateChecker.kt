package com.example.updater.engine

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.updater.model.AppUpdateInfo
import com.example.updater.model.UpdateCheckResult
import com.example.updater.model.UpdateConfig
import com.example.updater.model.UpdateSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AppUpdateChecker(
    private val context: Context,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    suspend fun checkForUpdates(
        config: UpdateConfig,
        forceCheck: Boolean = false
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val currentVersionCode = BuildConfig.VERSION_CODE
        val currentVersionName = BuildConfig.VERSION_NAME

        try {
            when (config.sourceType) {
                UpdateSourceType.GITHUB_RELEASES -> checkGitHubReleases(config.githubRepo, currentVersionCode, currentVersionName)
                UpdateSourceType.JSON_MANIFEST -> checkJsonManifest(config.customManifestUrl, currentVersionCode, currentVersionName)
                UpdateSourceType.SIMULATION -> checkSimulationUpdate(currentVersionCode, currentVersionName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}", e)
            UpdateCheckResult.Error(
                message = e.localizedMessage ?: "Failed to reach update server",
                throwable = e
            )
        }
    }

    private fun checkGitHubReleases(
        repo: String,
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateCheckResult {
        val cleanRepo = repo.trim().removePrefix("https://github.com/").trim('/')
        if (!cleanRepo.contains('/')) {
            return UpdateCheckResult.Error("Invalid GitHub repository format. Expected: 'owner/repo' (e.g. 'developer/localdoc-finder')")
        }

        val apiUrl = "https://api.github.com/repos/$cleanRepo/releases/latest"
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "LocalDoc-Finder-Android/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 404) {
                return UpdateCheckResult.Error("No releases found on GitHub repo '$cleanRepo'. Publish a release with an APK asset attached.")
            }
            if (!response.isSuccessful) {
                return UpdateCheckResult.Error("GitHub API error: HTTP ${response.code} (${response.message})")
            }

            val responseBody = response.body?.string() ?: return UpdateCheckResult.Error("Empty response received from GitHub")
            val json = JSONObject(responseBody)

            val tagName = json.optString("tag_name", "").trim()
            val releaseName = json.optString("name", tagName).ifBlank { tagName }
            val releaseBody = json.optString("body", "• Maintenance update and stability enhancements.")
            val publishedAt = json.optString("published_at", "")
            val isPrerelease = json.optBoolean("prerelease", false)

            val assets = json.optJSONArray("assets") ?: JSONArray()
            var apkDownloadUrl: String? = null
            var apkFileName = "localdoc-update.apk"
            var apkSize = 0L

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkDownloadUrl = asset.optString("browser_download_url")
                    apkFileName = name
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }

            // If no direct APK asset in release, fall back to release page or direct zipball
            val downloadUrl = apkDownloadUrl ?: json.optString("html_url", "https://github.com/$cleanRepo/releases/latest")

            val targetVersionCode = parseVersionCodeFromTagOrBody(tagName, releaseName, releaseBody, currentVersionCode)
            val cleanVersionName = tagName.removePrefix("v").removePrefix("V").ifBlank { releaseName }

            val isNewer = targetVersionCode > currentVersionCode ||
                    isVersionNameNewer(cleanVersionName, currentVersionName)

            if (isNewer) {
                val info = AppUpdateInfo(
                    versionCode = targetVersionCode,
                    versionName = cleanVersionName,
                    releaseTitle = releaseName,
                    releaseNotes = releaseBody,
                    downloadUrl = downloadUrl,
                    fileSizeBytes = apkSize,
                    publishedAt = publishedAt,
                    sourceType = UpdateSourceType.GITHUB_RELEASES,
                    sourceName = "GitHub: $cleanRepo",
                    assetFileName = apkFileName
                )
                return UpdateCheckResult.UpdateAvailable(info)
            } else {
                return UpdateCheckResult.UpToDate(currentVersionCode, currentVersionName)
            }
        }
    }

    private fun checkJsonManifest(
        manifestUrl: String,
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateCheckResult {
        if (!manifestUrl.startsWith("http://") && !manifestUrl.startsWith("https://")) {
            return UpdateCheckResult.Error("Invalid manifest URL. Must start with http:// or https://")
        }

        val request = Request.Builder()
            .url(manifestUrl)
            .header("User-Agent", "LocalDoc-Finder-Android/${BuildConfig.VERSION_NAME}")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return UpdateCheckResult.Error("Failed to fetch manifest: HTTP ${response.code}")
            }

            val body = response.body?.string() ?: return UpdateCheckResult.Error("Manifest body was empty")
            val json = JSONObject(body)

            val remoteVersionCode = json.optInt("versionCode", 0)
            val remoteVersionName = json.optString("versionName", "1.0")
            val releaseNotes = json.optString("releaseNotes", json.optString("changelog", "• General enhancements and bug fixes."))
            val releaseTitle = json.optString("releaseTitle", "Version $remoteVersionName")
            val downloadUrl = json.optString("downloadUrl", "")
            val fileSize = json.optLong("fileSizeBytes", json.optLong("fileSize", 0L))
            val sha256 = if (json.has("sha256") && !json.isNull("sha256")) json.optString("sha256") else null
            val isMandatory = json.optBoolean("isMandatory", json.optBoolean("mandatory", false))
            val publishedAt = json.optString("publishedAt", "")

            if (downloadUrl.isBlank()) {
                return UpdateCheckResult.Error("Manifest missing valid 'downloadUrl' property")
            }

            val isNewer = remoteVersionCode > currentVersionCode ||
                    isVersionNameNewer(remoteVersionName, currentVersionName)

            if (isNewer) {
                val info = AppUpdateInfo(
                    versionCode = remoteVersionCode,
                    versionName = remoteVersionName,
                    releaseTitle = releaseTitle,
                    releaseNotes = releaseNotes,
                    downloadUrl = downloadUrl,
                    fileSizeBytes = fileSize,
                    sha256Hash = sha256,
                    isMandatory = isMandatory,
                    publishedAt = publishedAt,
                    sourceType = UpdateSourceType.JSON_MANIFEST,
                    sourceName = manifestUrl
                )
                return UpdateCheckResult.UpdateAvailable(info)
            } else {
                return UpdateCheckResult.UpToDate(currentVersionCode, currentVersionName)
            }
        }
    }

    fun checkSimulationUpdate(
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateCheckResult {
        val simulatedCode = currentVersionCode + 1
        val simulatedName = "1.1.0"
        val simulatedNotes = """
            ### 🚀 What's New in v1.1.0 (APK Release)
            
            • **Ultra-Fast Vector Inference**: 2.4x speedup on on-device quantized embeddings.
            • **Real-Time Folder Monitor**: Automatic incremental indexing when new PDFs/DOCX are saved.
            • **In-App APK Self-Updater**: Direct OTA updates without Google Play Services or store fees.
            • **Glossy HUD & Dark Theme**: Refined Material 3 glassmorphic design and memory optimizations.
            • **Offline Privacy Guarantee**: 100% on-device search with zero cloud telemetry.
        """.trimIndent()

        val info = AppUpdateInfo(
            versionCode = simulatedCode,
            versionName = simulatedName,
            releaseTitle = "LocalDoc Finder v$simulatedName",
            releaseNotes = simulatedNotes,
            downloadUrl = "https://example.com/builds/localdoc-v$simulatedName.apk",
            fileSizeBytes = 18_420_000L, // ~18.4 MB
            publishedAt = "Just now",
            sourceType = UpdateSourceType.SIMULATION,
            sourceName = "Simulation Test Release",
            assetFileName = "LocalDocFinder-v$simulatedName.apk"
        )

        return UpdateCheckResult.UpdateAvailable(info)
    }

    private fun parseVersionCodeFromTagOrBody(
        tag: String,
        name: String,
        body: String,
        currentCode: Int
    ): Int {
        // Check for explicit "versionCode: 42" or "build: 42" in release body or name
        val regex = Regex("""(?:versionCode|buildCode|build|code)\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(body) ?: regex.find(name)
        if (match != null) {
            return match.groupValues[1].toIntOrNull() ?: currentCode
        }

        // Parse semantic version from tag (e.g., "v1.2.3" -> 1*10000 + 2*100 + 3 = 10203)
        val clean = tag.removePrefix("v").removePrefix("V").trim()
        val parts = clean.split('.').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        if (parts.isNotEmpty()) {
            val major = parts.getOrElse(0) { 0 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            return major * 10000 + minor * 100 + patch
        }

        return currentCode
    }

    private fun isVersionNameNewer(remote: String, local: String): Boolean {
        try {
            val rParts = remote.removePrefix("v").removePrefix("V").split('.').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val lParts = local.removePrefix("v").removePrefix("V").split('.').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val maxLen = maxOf(rParts.size, lParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val l = lParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
        } catch (_: Exception) {}
        return false
    }

    companion object {
        private const val TAG = "AppUpdateChecker"
    }
}

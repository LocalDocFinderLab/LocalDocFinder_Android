package com.example.updater.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.updater.model.UpdateConfig
import com.example.updater.model.UpdateSourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UpdatePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<UpdateConfig> = _config.asStateFlow()

    private fun readConfig(): UpdateConfig {
        val sourceStr = prefs.getString(KEY_SOURCE_TYPE, UpdateSourceType.GITHUB_RELEASES.name)
            ?: UpdateSourceType.GITHUB_RELEASES.name
        val sourceType = try {
            UpdateSourceType.valueOf(sourceStr)
        } catch (_: Exception) {
            UpdateSourceType.GITHUB_RELEASES
        }

        return UpdateConfig(
            sourceType = sourceType,
            githubRepo = prefs.getString(KEY_GITHUB_REPO, "localdocfinderlab/localdocfinder_android") ?: "localdocfinderlab/localdocfinder_android",
            customManifestUrl = prefs.getString(
                KEY_CUSTOM_MANIFEST_URL,
                "https://raw.githubusercontent.com/localdocfinderlab/localdocfinder_android/main/update.json"
            ) ?: "https://raw.githubusercontent.com/localdocfinderlab/localdocfinder_android/main/update.json",
            autoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK, true),
            checkOnWifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false),
            lastCheckTimestamp = prefs.getLong(KEY_LAST_CHECK, 0L),
            dismissedVersionCode = prefs.getInt(KEY_DISMISSED_VERSION, 0)
        )
    }

    fun updateConfig(
        sourceType: UpdateSourceType? = null,
        githubRepo: String? = null,
        customManifestUrl: String? = null,
        autoCheckEnabled: Boolean? = null,
        checkOnWifiOnly: Boolean? = null,
        lastCheckTimestamp: Long? = null,
        dismissedVersionCode: Int? = null
    ) {
        val editor = prefs.edit()
        val current = _config.value

        val newSource = sourceType ?: current.sourceType
        val newRepo = (githubRepo ?: current.githubRepo).trim()
        val newUrl = (customManifestUrl ?: current.customManifestUrl).trim()
        val newAuto = autoCheckEnabled ?: current.autoCheckEnabled
        val newWifi = checkOnWifiOnly ?: current.checkOnWifiOnly
        val newLastCheck = lastCheckTimestamp ?: current.lastCheckTimestamp
        val newDismissed = dismissedVersionCode ?: current.dismissedVersionCode

        editor.putString(KEY_SOURCE_TYPE, newSource.name)
        editor.putString(KEY_GITHUB_REPO, newRepo)
        editor.putString(KEY_CUSTOM_MANIFEST_URL, newUrl)
        editor.putBoolean(KEY_AUTO_CHECK, newAuto)
        editor.putBoolean(KEY_WIFI_ONLY, newWifi)
        editor.putLong(KEY_LAST_CHECK, newLastCheck)
        editor.putInt(KEY_DISMISSED_VERSION, newDismissed)
        editor.apply()

        _config.value = UpdateConfig(
            sourceType = newSource,
            githubRepo = newRepo,
            customManifestUrl = newUrl,
            autoCheckEnabled = newAuto,
            checkOnWifiOnly = newWifi,
            lastCheckTimestamp = newLastCheck,
            dismissedVersionCode = newDismissed
        )
    }

    fun recordLastCheckTime(timestamp: Long = System.currentTimeMillis()) {
        updateConfig(lastCheckTimestamp = timestamp)
    }

    fun dismissVersion(versionCode: Int) {
        updateConfig(dismissedVersionCode = versionCode)
    }

    companion object {
        private const val KEY_SOURCE_TYPE = "pref_source_type"
        private const val KEY_GITHUB_REPO = "pref_github_repo"
        private const val KEY_CUSTOM_MANIFEST_URL = "pref_custom_manifest_url"
        private const val KEY_AUTO_CHECK = "pref_auto_check"
        private const val KEY_WIFI_ONLY = "pref_wifi_only"
        private const val KEY_LAST_CHECK = "pref_last_check"
        private const val KEY_DISMISSED_VERSION = "pref_dismissed_version"
    }
}

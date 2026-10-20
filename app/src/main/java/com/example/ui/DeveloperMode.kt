package com.example.ui

import android.content.Context
import android.util.Log

/**
 * Developer mode shows every engine option: search mode chips (Hybrid / Vector / Keyword), ranking weights,
 * the individual folder crawlers, sample loaders and hardware diagnostics.
 *
 * It is off by default. Without it the app shows a short "add documents" menu and one search box, and
 * picks the words-vs-meaning balance per query (see [com.example.engine.search.SmartSearch]).
 */
object DeveloperMode {
    private const val TAG = "DeveloperMode"
    private const val PREFS_NAME = "app_ui_prefs"
    private const val KEY_ENABLED = "developer_mode"

    fun isEnabled(context: Context): Boolean =
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
        } catch (_: Exception) {
            false
        }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist developer mode: ${e.message}")
        }
    }
}

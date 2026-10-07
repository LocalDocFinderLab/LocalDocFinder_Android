package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.R
import com.example.data.local.AppDatabase
import com.example.ui.overlay.GlossySearchOverlayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * DocuVectorWidget (LocalDoc Finder Widget)
 *
 * Sleek, glossy Google-bar-style home screen widget.
 * Features:
 * - LocalDoc Finder glossy emblem on left
 * - Search query bar in center
 * - Live offline chunk count badge
 * - Voice search microphone button on right
 * - Direct launch into the translucent GlossySearchOverlayActivity over the home screen
 */
class DocuVectorWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_UPDATE_STATS, AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                updateAllWidgets(context)
            }
        }
    }

    companion object {
        const val EXTRA_FOCUS_SEARCH = "extra_focus_search"
        const val EXTRA_START_VOICE = "extra_start_voice"
        const val EXTRA_QUERY = "extra_query"
        const val ACTION_UPDATE_STATS = "com.example.widget.ACTION_UPDATE_STATS"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager?,
            appWidgetId: Int
        ) {
            if (appWidgetManager == null) return
            try {
                // Intent to open translucent search overlay over home screen
                val searchIntent = Intent(context, GlossySearchOverlayActivity::class.java).apply {
                    action = Intent.ACTION_SEARCH
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_FOCUS_SEARCH, true)
                }

                val pendingSearchIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    searchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // Intent to trigger Voice Search immediately in the overlay
                val voiceIntent = Intent(context, GlossySearchOverlayActivity::class.java).apply {
                    action = Intent.ACTION_SEARCH
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_FOCUS_SEARCH, true)
                    putExtra(EXTRA_START_VOICE, true)
                }

                val pendingVoiceIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId + 500,
                    voiceIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val views = RemoteViews(context.packageName, R.layout.docuvector_widget).apply {
                    // Set non-overlapping click targets
                    setOnClickPendingIntent(R.id.widget_symbol, pendingSearchIntent)
                    setOnClickPendingIntent(R.id.widget_search_bar_clickable, pendingSearchIntent)
                    setOnClickPendingIntent(R.id.widget_badge, pendingSearchIntent)
                    setOnClickPendingIntent(R.id.widget_btn_voice, pendingVoiceIntent)
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)

                // Query live hardware and indexing status in IO background
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val metrics = com.example.engine.HardwareMonitor.metrics.value
                        val wm = androidx.work.WorkManager.getInstance(context)
                        val indexWork = try {
                            val activeWorkers = listOf(
                                "DocumentIndexWorker",
                                "DownloadsFileObserverWorker",
                                "FolderMonitorWorker",
                                "PdfSyncWorker",
                                "document_indexing_worker"
                            )
                            activeWorkers.firstNotNullOfOrNull { tag ->
                                wm.getWorkInfosByTag(tag).get().firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
                            }
                        } catch (_: Throwable) { null }

                        val isIndexing = indexWork != null || com.example.engine.HardwareMonitor.isEmbeddingActive.value || metrics.isNpuActive

                        val searchPrompt: String
                        val badgeText: String
                        val subText: String

                        if (isIndexing) {
                            val progress = indexWork?.progress
                            val currentFile = progress?.getString("current_file") ?: progress?.getString("key_current_file")
                            val percent = progress?.getInt("percent", progress.getInt("key_progress_percent", 0)) ?: 0

                            searchPrompt = if (percent > 0) "Indexing files ($percent%)…" else "Indexing files…"
                            badgeText = if (percent > 0) "$percent%" else "Indexing"
                            subText = if (!currentFile.isNullOrBlank()) {
                                "Indexing $currentFile"
                            } else {
                                "Processing vector embeddings • CPU ${metrics.cpuUsagePercent}%"
                            }
                        } else {
                            val totalDocs = try {
                                com.example.data.local.AppDatabase.getInstance(context).documentChunkDao().getTotalFilesCountDirect()
                            } catch (_: Throwable) { 0 }
                            searchPrompt = "Search local documents"
                            badgeText = if (totalDocs > 0) "$totalDocs files" else "Ready"
                            subText = "100% private offline neural search"
                        }

                        val updatedViews = RemoteViews(context.packageName, R.layout.docuvector_widget).apply {
                            setOnClickPendingIntent(R.id.widget_symbol, pendingSearchIntent)
                            setOnClickPendingIntent(R.id.widget_search_bar_clickable, pendingSearchIntent)
                            setOnClickPendingIntent(R.id.widget_badge, pendingSearchIntent)
                            setOnClickPendingIntent(R.id.widget_btn_voice, pendingVoiceIntent)
                            setTextViewText(R.id.widget_search_text, searchPrompt)
                            setTextViewText(R.id.widget_badge, badgeText)
                            setTextViewText(R.id.widget_sub_text, subText)
                        }
                        appWidgetManager.updateAppWidget(appWidgetId, updatedViews)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        fun updateAllWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, DocuVectorWidget::class.java)
                val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                if (allWidgetIds != null && allWidgetIds.isNotEmpty()) {
                    for (id in allWidgetIds) {
                        updateAppWidget(context, appWidgetManager, id)
                    }
                }
            } catch (_: Exception) {}
        }
    }
}

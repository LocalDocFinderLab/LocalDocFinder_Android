package com.example.worker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.example.DocuVectorApp
import com.example.MainActivity

/**
 * Builds, posts and clears the "indexing in progress" status-bar notification.
 *
 * Every notification is ongoing, silent, shows progress and carries a Stop action. Callers must
 * [cancel] their notification when their work ends (workers do so in a `finally` block), so the
 * notification disappears by itself as soon as indexing is done.
 */
object IndexingNotifier {

    const val ACTION_STOP_INDEXING = "com.example.action.STOP_INDEXING"

    /** Manual / sample / picked-file indexing run by [DocumentIndexWorker] (foreground notification). */
    const val ID_DOCUMENT_INDEX = DocuVectorApp.NOTIFICATION_ID

    /** Background scan for new or changed files run by [FolderMonitorWorker]. */
    const val ID_FOLDER_SCAN = FolderMonitorWorker.NOTIFICATION_ID

    const val ID_PDF_SYNC = 1003
    const val ID_DOWNLOADS_SCAN = 1004

    private val ALL_IDS = listOf(ID_DOCUMENT_INDEX, ID_FOLDER_SCAN, ID_PDF_SYNC, ID_DOWNLOADS_SCAN)

    /**
     * @param percent 0..100 for a determinate bar, or null for an indeterminate one (e.g. while discovering files)
     */
    fun build(context: Context, title: String, text: String, percent: Int?): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, IndexingControlReceiver::class.java).setAction(ACTION_STOP_INDEXING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, DocuVectorApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)

        if (percent != null) {
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun foregroundInfo(id: Int, notification: Notification): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    /** Posts or updates a notification. Silently does nothing if the user has notifications turned off. */
    @SuppressLint("MissingPermission")
    fun show(context: Context, id: Int, title: String, text: String, percent: Int?) {
        try {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            manager.notify(id, build(context, title, text, percent))
        } catch (_: Exception) {
            // A missing notification must never break indexing.
        }
    }

    fun cancel(context: Context, id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(id)
        } catch (_: Exception) {
        }
    }

    fun cancelAll(context: Context) {
        ALL_IDS.forEach { cancel(context, it) }
    }
}

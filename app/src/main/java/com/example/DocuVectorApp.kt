package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Configuration

class DocuVectorApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        com.example.updater.worker.AppUpdateCheckWorker.ensureNotificationChannel(this)
        try {
            com.example.worker.ContinuousSyncWorker.scheduleContinuousSync(this)
            com.example.worker.FolderMonitorWorker.schedulePeriodicMonitor(this)
            com.example.worker.DownloadsFileObserverWorker.scheduleDownloadsObserver(this)
            com.example.updater.worker.AppUpdateCheckWorker.schedulePeriodicCheck(this)
        } catch (e: Throwable) {
            Log.w("DocuVectorApp", "Worker scheduling deferred: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Document Indexing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while processing and embedding documents offline"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "document_indexer_channel"
        const val NOTIFICATION_ID = 1001
    }
}

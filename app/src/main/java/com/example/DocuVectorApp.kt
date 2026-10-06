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
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                com.example.engine.FailedDocumentRegistry.handleUncaughtCrash(this, throwable)
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
        try {
            com.example.engine.FailedDocumentRegistry.init(this)
        } catch (e: Throwable) {
            Log.w("DocuVectorApp", "FailedDocumentRegistry init: ${e.message}")
        }
        createNotificationChannel()
        com.example.updater.worker.AppUpdateCheckWorker.ensureNotificationChannel(this)
        try {
            // Track charging / screen-idle / thermal state so the indexer can go full-throttle when
            // the phone is charging and unused, and slow down the moment the user picks it up.
            com.example.engine.IndexingPowerPolicy.start(this)
        } catch (e: Throwable) {
            Log.w("DocuVectorApp", "Indexing power policy unavailable: ${e.message}")
        }
        try {
            // Background indexing stays off if the user stopped it or safe mode is active
            if (!com.example.worker.IndexingController.isStoppedByUser(this) &&
                !com.example.engine.FailedDocumentRegistry.isSafeModeActive(this)) {
                com.example.worker.ContinuousSyncWorker.scheduleContinuousSync(this)
                com.example.worker.FolderMonitorWorker.schedulePeriodicMonitor(this)
            }
            // The Downloads and PDF-sync workers used to run every 15 minutes next to the folder scan,
            // re-scanning the same folders. The folder scan covers them, so retire the old schedules.
            val workManager = androidx.work.WorkManager.getInstance(this)
            workManager.cancelUniqueWork(com.example.worker.DownloadsFileObserverWorker.WORK_NAME)
            workManager.cancelUniqueWork(com.example.worker.PdfSyncWorker.WORK_NAME)
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

package com.example.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the "Stop" button on the indexing notification.
 */
class IndexingControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == IndexingNotifier.ACTION_STOP_INDEXING) {
            IndexingController.stopAll(context)
        }
    }
}

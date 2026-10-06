package com.example

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.worker.FolderMonitorWorker
import com.example.worker.IndexingController
import com.example.worker.IndexingNotifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndexingControlTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        IndexingController.resume(context)
        IndexingNotifier.cancelAll(context)
    }

    @After
    fun tearDown() {
        IndexingController.resume(context)
        IndexingNotifier.cancelAll(context)
    }

    @Test
    fun stopAllIsRememberedAndResumeClearsIt() {
        assertFalse(IndexingController.isStoppedByUser(context))

        IndexingController.stopAll(context)
        assertTrue(IndexingController.isStoppedByUser(context))
        assertTrue(IndexingController.stoppedByUserFlow(context).value)

        IndexingController.resume(context)
        assertFalse(IndexingController.isStoppedByUser(context))
        assertFalse(IndexingController.stoppedByUserFlow(context).value)
    }

    @Test
    fun backgroundScanDoesNothingWhileStopped() = runBlocking {
        IndexingController.stopAll(context)

        val worker = TestListenableWorkerBuilder<FolderMonitorWorker>(context).build()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val message = (result as ListenableWorker.Result.Success).outputData
            .getString(FolderMonitorWorker.KEY_SCAN_MESSAGE)
        assertTrue("Unexpected message: $message", message?.startsWith("Skipped") == true)
    }

    @Test
    fun progressNotificationIsOngoingWithProgressAndStopAction() {
        val notification = IndexingNotifier.build(context, "Indexing", "report.pdf (2/10)", 20)

        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(20, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(1, notification.actions.size)
        assertEquals("Stop", notification.actions[0].title.toString())
    }

    @Test
    fun notificationClearsWhenCancelled() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        IndexingNotifier.show(context, IndexingNotifier.ID_FOLDER_SCAN, "Indexing", "a.txt", 50)
        assertEquals(1, shadowOf(manager).size())

        IndexingNotifier.cancel(context, IndexingNotifier.ID_FOLDER_SCAN)
        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun stopAllClearsEveryIndexingNotification() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        IndexingNotifier.show(context, IndexingNotifier.ID_FOLDER_SCAN, "Indexing", "a.txt", 10)
        IndexingNotifier.show(context, IndexingNotifier.ID_PDF_SYNC, "Indexing", "b.pdf", 20)
        assertEquals(2, shadowOf(manager).size())

        IndexingController.stopAll(context)

        assertEquals(0, shadowOf(manager).size())
    }
}

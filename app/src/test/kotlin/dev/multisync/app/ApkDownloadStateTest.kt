package dev.multisync.app

import androidx.work.Data
import androidx.work.WorkInfo
import dev.multisync.app.data.Settings
import dev.multisync.app.sync.ApkDownloadWorker
import dev.multisync.app.sync.apkConnectionKey
import dev.multisync.app.ui.apkDownloadState
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ApkDownloadStateTest {
    private val tags = setOf(ApkDownloadWorker.PACKAGE_TAG + "org.example.app", ApkDownloadWorker.SHA_TAG + "a".repeat(64), ApkDownloadWorker.NAME_TAG + "Example_1.2.apk")
    private fun work(state: WorkInfo.State, output: Data = Data.EMPTY) = WorkInfo(UUID.randomUUID(), state, tags, output, Data.EMPTY, 0, 0)

    @Test fun queuedRunningCancelledAndCompletedTasksRestoreTheirStatusAndFileIdentity() {
        assertTrue(apkDownloadState(work(WorkInfo.State.ENQUEUED)).active)
        assertTrue(apkDownloadState(work(WorkInfo.State.RUNNING)).active)
        val cancelled = apkDownloadState(work(WorkInfo.State.CANCELLED))
        assertFalse(cancelled.active)
        assertEquals("下载已取消", cancelled.status)
        val completed = apkDownloadState(work(WorkInfo.State.SUCCEEDED, Data.Builder().putString(ApkDownloadWorker.SAVED_URI, "content://downloads/document/1").putString(ApkDownloadWorker.FILE_NAME, "Example_1.2.apk").build()))
        assertEquals("org.example.app", completed.packageName)
        assertEquals("Example_1.2.apk", completed.fileName)
        assertEquals("content://downloads/document/1", completed.savedUri)
        assertFalse(completed.active)
    }

    @Test fun connectionOrSourceChangesInvalidateAQueuedDownloadButDisplayChangesDoNot() {
        val original = Settings(baseUrl = "https://example.com/dav/", username = "account", deviceName = "phone")
        assertEquals(apkConnectionKey(original), apkConnectionKey(original.copy(theme = "dark", deviceName = "tablet")))
        assertNotEquals(apkConnectionKey(original), apkConnectionKey(original.copy(baseUrl = "https://other.example/dav/")))
        assertNotEquals(apkConnectionKey(original), apkConnectionKey(original.copy(softwareUploadPath = "other/apps")))
        assertNotEquals(apkConnectionKey(original), apkConnectionKey(original.copy(username = "other")))
    }
}

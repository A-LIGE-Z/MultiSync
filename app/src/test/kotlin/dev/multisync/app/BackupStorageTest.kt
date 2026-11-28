package dev.multisync.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import dev.multisync.app.data.BackupStorage
import dev.multisync.app.data.DeviceStorageRoot
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupStorageTest {
    @Test fun deviceDirectoriesCanBeInspectedReadAndCleanedWithinTheSelectedRoot() {
        val context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("backup-storage").toFile().canonicalFile
        val file = File(root, "backup.bin").apply { writeText("complete backup") }
        val sub = File(root, "sub").apply { mkdir() }
        try {
            val storage = BackupStorage(context, { listOf(DeviceStorageRoot("test", root.path)) }, { true })
            val uri = Uri.fromFile(root).toString()
            val info = storage.inspect(uri, true)
            assertEquals(1, info.files)
            assertEquals(1, info.directories)
            val node = storage.source(uri).children().single { !it.stat().directory }
            assertEquals("complete backup", node.open().use { it.reader().readText() })
            assertTrue(node.delete())
            assertFalse(file.exists())
            assertThrows(IllegalStateException::class.java) { node.stat() }
            assertThrows(IllegalArgumentException::class.java) { storage.browse(root.parent.orEmpty()) }
        } finally { file.delete(); sub.delete(); root.delete() }
    }

    @Test fun deniedDeviceAccessIsAnErrorInsteadOfAnEmptyDirectory() {
        val storage = BackupStorage(RuntimeEnvironment.getApplication(), { emptyList() }, { false })
        assertTrue(assertThrows(IllegalArgumentException::class.java) { storage.browse("") }.message!!.contains("文件访问"))
        assertThrows(IllegalArgumentException::class.java) { storage.source("file:///storage/emulated/0/Download") }
    }

    @Test fun missingPersistentSystemDirectoryGrantIsReportedBeforeScanning() {
        val storage = BackupStorage(RuntimeEnvironment.getApplication())
        val uri = "content://test.storage/tree/primary%3ADownload"
        assertTrue(assertThrows(IllegalArgumentException::class.java) { storage.source(uri) }.message!!.contains("长期读取授权"))
    }

    @Test fun aProviderReadFailureIsPropagatedInsteadOfBeingTreatedAsNoFiles() {
        val context = RuntimeEnvironment.getApplication()
        val provider = DeniedProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "test.storage" })
        ShadowContentResolver.registerProviderInternal("test.storage", provider)
        val uri = Uri.parse("content://test.storage/tree/primary%3ADownload")
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val node = BackupStorage(context).source(uri.toString(), true)
        assertThrows(SecurityException::class.java) { node.children() }
    }

    class DeniedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = throw SecurityException("denied")
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}

package dev.multisync.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import dev.multisync.app.data.*
import dev.multisync.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConfigurationBackupStoreTest {
    private val app get() = RuntimeEnvironment.getApplication() as SyncApplication
    private val rule = AppRule("software", "测试软件", "org.example.backup", "apps/test")
    private fun installApp() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = rule.packageName
            applicationInfo = ApplicationInfo().apply { packageName = rule.packageName; nonLocalizedLabel = rule.name }
        })
    }
    private fun backup(sourceDevice: String = app.settings.state.value.deviceId) = ConfigurationBackup(createdAt = 1, sourceDeviceId = sourceDevice,
        settings = BackupSettings(deviceName = "来源设备", softwareUploadPath = "team/apps", fileUploadPath = "team/files"), apps = listOf(rule))

    @Test fun previewDoesNotChangeCurrentSettingsAndImportReplacesRulesButKeepsDeviceIdentity() = runBlocking {
        installApp()
        val store = ConfigurationBackupStore(app)
        val before = app.settings.state.value
        app.database.dao().putRule(RuleEntity("old", "app", SyncJson.encodeToString(rule.copy(id = "old"))))
        app.database.dao().putObservation(Observation("old-observation", 10, 20, 30))
        val prepared = store.prepare(backup())
        assertEquals(before, app.settings.state.value)
        assertEquals("old", app.database.dao().rules().single().id)
        store.apply(prepared)
        assertEquals(before.deviceId, app.settings.state.value.deviceId)
        assertEquals("team/apps", app.settings.state.value.softwareUploadPath)
        assertEquals("software", app.database.dao().rules().single().id)
        assertNull(app.database.dao().observation("old-observation"))
    }

    @Test fun otherDevicesKeepTheirNameAndSuspendBackupDirectoriesAndUnavailableSoftware() = runBlocking {
        val current = app.settings.state.value
        val source = backup("another-device").copy(backups = listOf(BackupRule("files", "下载备份", "file:///storage/emulated/0/Download", "files")),
            apps = listOf(rule.copy(exportTreeUri = "content://example.documents/tree/primary%3ADownload")))
        val prepared = ConfigurationBackupStore(app).prepare(source)
        assertEquals(current.deviceId, prepared.settings.deviceId)
        assertEquals(current.deviceName, prepared.settings.deviceName)
        assertFalse(prepared.apps.single().enabled)
        assertEquals("", prepared.apps.single().exportTreeUri)
        assertFalse(prepared.backups.single().enabled)
        assertTrue(prepared.preview.warnings.any { it.contains("重新") })
    }

    @Test fun signatureMismatchAndLostDirectoryPermissionsSuspendAffectedRules() = runBlocking {
        installApp()
        val source = backup().copy(apps = listOf(rule.copy(trustedSigners = listOf("a".repeat(64)))),
            backups = listOf(BackupRule("files", "备份", "content://example.documents/tree/primary%3ADownload", "files")))
        val prepared = ConfigurationBackupStore(app).prepare(source)
        assertFalse(prepared.apps.single().enabled)
        assertFalse(prepared.backups.single().enabled)
        assertTrue(prepared.preview.warnings.any { it.contains("签名") })
    }

    @Test fun invalidConfigurationAndChangedPreviewNeverOverwriteCurrentConfiguration() = runBlocking {
        val store = ConfigurationBackupStore(app)
        val current = app.settings.state.value
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.prepare(backup().copy(settings = BackupSettings(fileUploadPath = "bad/../path"))) } }
        assertEquals(current, app.settings.state.value)
        val prepared = store.prepare(backup())
        app.settings.save(current.copy(theme = "dark"), null)
        assertThrows(IllegalStateException::class.java) { runBlocking { store.apply(prepared) } }
        assertEquals("dark", app.settings.state.value.theme)
        assertTrue(app.database.dao().rules().isEmpty())
    }

    @Test fun databaseWriteFailureRollsBackRulesAndDoesNotPartiallySaveSettings() = runBlocking {
        val store = ConfigurationBackupStore(app)
        val original = RuleEntity("old", "app", SyncJson.encodeToString(rule.copy(id = "old")))
        app.database.dao().putRule(original)
        val before = app.settings.state.value
        val prepared = store.prepare(backup())
        app.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON rules WHEN NEW.id = 'software' BEGIN SELECT RAISE(ABORT, 'fixture import failure'); END")
        try {
            assertThrows(Exception::class.java) { runBlocking { store.apply(prepared) } }
            assertEquals(listOf(original), app.database.dao().rules())
            assertEquals(before, app.settings.state.value)
        } finally { app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_import") }
    }

    @Test fun plainBackupsKeepCredentialsForTheSameConnectionAndClearThemWhenTheAccountChanges() = runBlocking {
        val prefs = app.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("password", "encrypted-fixture").commit()
        app.settings.save(app.settings.state.value.copy(baseUrl = "https://original.example/dav/", username = "original"), null)
        val store = ConfigurationBackupStore(app)
        val exported = store.capture(false)
        assertNull(exported.credentials)
        assertFalse(ConfigurationBackupCodec.encode(exported).toString(Charsets.UTF_8).contains("encrypted-fixture"))
        store.apply(store.prepare(exported))
        assertEquals("encrypted-fixture", prefs.getString("password", null))
        store.apply(store.prepare(exported.copy(settings = exported.settings.copy(baseUrl = "https://different.example/dav/"))))
        assertFalse(prefs.contains("password"))
        assertFalse(app.settings.state.value.hasPassword)
    }

    @Test fun configurationCannotBeReplacedWhileASyncHoldsTheCoordinatorLock() = runBlocking {
        val store = ConfigurationBackupStore(app)
        val prepared = store.prepare(backup())
        app.coordinator.withConfigurationLock {
            assertThrows(IllegalStateException::class.java) { runBlocking { store.apply(prepared) } }
        }
        assertEquals(prepared.beforeSettings, app.settings.state.value)
    }
}

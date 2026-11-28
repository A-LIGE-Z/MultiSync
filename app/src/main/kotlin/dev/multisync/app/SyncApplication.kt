package dev.multisync.app

import android.app.Application
import androidx.room.Room
import dev.multisync.app.data.SettingsStore
import dev.multisync.app.data.SyncDatabase
import dev.multisync.app.sync.SyncCoordinator
import dev.multisync.app.sync.SyncScheduler
import android.content.pm.ApplicationInfo
import dev.multisync.app.data.TaskLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SyncApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, SyncDatabase::class.java, "multisync.db").addMigrations(SyncDatabase.MIGRATION_1_2, SyncDatabase.MIGRATION_2_3).build() }
    val settings by lazy { SettingsStore(this) }
    val coordinator by lazy { SyncCoordinator(this) }
    override fun onCreate() {
        super.onCreate()
        // Create the managed Download/MultiSync folder as soon as storage access is available.
        runCatching { dev.multisync.app.data.ManagedStorage.ensure(this) }
        // Development builds can consume a one-use import placed in this app's private cache by adb.
        // No exported intent accepts credentials, and production builds use the system file picker.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val cancel = java.io.File(cacheDir, "cancel-manual-sync")
            if (cancel.isFile) {
                androidx.work.WorkManager.getInstance(this).cancelUniqueWork("manual-sync")
                cancel.delete()
            }
            val pending = java.io.File(cacheDir, "pending-ssh-import.json")
            if (pending.isFile) {
                val success = try { pending.inputStream().use(SettingsStore::parseTunnel).let(settings::saveTunnel); true }
                catch (_: Exception) { false }
                finally { pending.delete() }
                CoroutineScope(Dispatchers.IO).launch { database.dao().addLog(TaskLog(ruleName = "连接",
                    stage = if (success) "上传通道已导入" else "上传通道导入失败",
                    message = if (success) "凭据已加密保存" else "请在设置中重新导入对应服务的通道", level = if (success) "success" else "error")) }
            }
        }
        if (settings.state.value.baseUrl.isNotBlank()) SyncScheduler.schedule(this, settings.state.value)
    }
}

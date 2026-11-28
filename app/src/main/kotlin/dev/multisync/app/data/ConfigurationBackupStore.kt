package dev.multisync.app.data

import android.net.Uri
import androidx.room.withTransaction
import dev.multisync.app.SyncApplication
import dev.multisync.core.*
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class ConfigurationImportPreview(
    val createdAt: Long, val sameDevice: Boolean, val appCount: Int, val backupCount: Int,
    val disabledApps: Int, val disabledBackups: Int, val containsCredentials: Boolean,
    val baseUrl: String, val softwarePath: String, val filePath: String, val warnings: List<String>,
)

internal class PreparedConfiguration(
    val settings: Settings, val apps: List<AppRule>, val backups: List<BackupRule>,
    val credentials: BackupCredentials?, val preserveCredentials: Boolean,
    val beforeSettings: Settings, val beforeRules: List<RuleEntity>, val preview: ConfigurationImportPreview,
) {
    override fun toString() = "PreparedConfiguration([hidden])"
}

internal class ConfigurationBackupStore(private val app: SyncApplication) {
    private val dao get() = app.database.dao()

    suspend fun capture(includeCredentials: Boolean): ConfigurationBackup = app.coordinator.withConfigurationLock {
        val settings = app.settings.state.value
        val rules = dao.rules()
        val backup = ConfigurationBackup(createdAt = System.currentTimeMillis(), sourceDeviceId = settings.deviceId,
            settings = BackupSettings(settings.baseUrl, settings.username, settings.deviceName, settings.intervalMinutes,
                settings.unmeteredOnly, settings.allowHttp, settings.theme, settings.softwareUploadPath, settings.fileUploadPath, settings.sshEnabled),
            apps = rules.filter { it.kind == "app" }.map { SyncJson.decodeFromString<AppRule>(it.payload) },
            backups = rules.filter { it.kind == "backup" }.map { SyncJson.decodeFromString<BackupRule>(it.payload) },
            credentials = if (includeCredentials) BackupCredentials(app.settings.password(),
                if (settings.hasSshTunnel) app.settings.tunnel() else null) else null)
        ConfigurationBackupCodec.validate(backup)
        backup
    }

    suspend fun prepare(backup: ConfigurationBackup): PreparedConfiguration {
        ConfigurationBackupCodec.validate(backup)
        val current = app.settings.state.value
        val existing = dao.rules()
        val sameDevice = backup.sourceDeviceId == current.deviceId
        val source = backup.settings
        val preserveCredentials = backup.credentials == null && source.baseUrl.trim().trimEnd('/') == current.baseUrl.trim().trimEnd('/') &&
            source.username.trim() == current.username.trim()
        val warnings = mutableListOf<String>()
        if (!sameDevice) warnings.add("保留当前设备名称与标识；备份目录需在本机重新确认，软件导出目录使用本机默认位置。")
        if (backup.credentials == null) warnings.add(if (preserveCredentials) "此文件不含连接凭据，同一连接已保存的密码与通道会保留。"
            else "此文件不含连接凭据；连接已改变，导入后需填写密码并重新导入上传通道。")
        val hasTunnel = backup.credentials?.sshTunnel != null || (preserveCredentials && current.hasSshTunnel)
        val tunnelValid = if (backup.credentials?.sshTunnel != null) true else if (hasTunnel)
            runCatching { app.settings.tunnel().validate(source.baseUrl); true }.getOrDefault(false) else false
        if (source.sshEnabled && !tunnelValid) warnings.add("上传通道不可用，导入后暂时关闭 SSH 通道。")
        val settings = current.copy(baseUrl = source.baseUrl.trim(), username = source.username.trim(),
            deviceName = if (sameDevice) source.deviceName else current.deviceName,
            intervalMinutes = source.intervalMinutes, unmeteredOnly = source.unmeteredOnly, allowHttp = source.allowHttp,
            theme = source.theme, softwareUploadPath = normalizeRemoteDirectory(source.softwareUploadPath),
            fileUploadPath = normalizeRemoteDirectory(source.fileUploadPath), sshEnabled = source.sshEnabled && tunnelValid)
        val apps = backup.apps.map { rule ->
            var enabled = rule.enabled
            var export = rule.exportTreeUri
            if (rule.packageName == app.packageName) {
                enabled = false
                warnings.add("${rule.name}：客户端自身同步已暂停。")
            } else {
                try {
                    val info = app.coordinator.packageInfo(rule.packageName)
                    val signers = app.coordinator.signers(info).toSet()
                    if (rule.trustedSigners.isNotEmpty() && signers != rule.trustedSigners.toSet()) {
                        enabled = false
                        warnings.add("${rule.name}：本机软件签名与备份不一致，规则已暂停。")
                    }
                    if (rule.subscribe && rule.trustedSigners.isEmpty()) {
                        enabled = false
                        warnings.add("${rule.name}：未保存受信任签名，请重新选择软件确认。")
                    }
                    if (rule.publish && !info.applicationInfo?.splitSourceDirs.isNullOrEmpty()) {
                        enabled = false
                        warnings.add("${rule.name}：本机使用分包安装，发布规则已暂停。")
                    }
                } catch (_: Exception) {
                    enabled = false
                    warnings.add("${rule.name}：本机未安装或无法读取软件，规则已暂停。")
                }
            }
            if (export.isNotBlank() && (!sameDevice || !hasExportPermission(export))) {
                export = ""
                warnings.add("${rule.name}：APK 导出目录需重新授权，暂用应用默认目录。")
            }
            rule.copy(enabled = enabled, exportTreeUri = export)
        }
        val storage = BackupStorage(app)
        val backups = backup.backups.map { rule ->
            val available = sameDevice && runCatching { storage.inspect(rule.sourceTreeUri, rule.deleteAfterVerify); true }.getOrDefault(false)
            if (!available) warnings.add("${rule.name}：备份目录需要重新选择或授权，规则已暂停。")
            rule.copy(enabled = rule.enabled && available)
        }
        val preview = ConfigurationImportPreview(backup.createdAt, sameDevice, apps.size, backups.size,
            apps.count { !it.enabled }, backups.count { !it.enabled }, backup.credentials != null,
            settings.baseUrl, settings.softwareUploadPath, settings.fileUploadPath, warnings)
        return PreparedConfiguration(settings, apps, backups, backup.credentials, preserveCredentials, current, existing, preview)
    }

    suspend fun apply(prepared: PreparedConfiguration) = app.coordinator.withConfigurationLock {
        check(app.settings.state.value == prepared.beforeSettings && dao.rules() == prepared.beforeRules) { "当前配置已变化，请重新选择备份文件并预览" }
        val replacement = app.settings.prepareConfiguration(prepared.settings, prepared.credentials, prepared.preserveCredentials)
        // Once approved, finish the short commit even if the activity closes.
        // Cancellation must not split the Room transaction and preference update.
        withContext(NonCancellable) {
            var preferencesWritten = false
            try {
                app.database.withTransaction {
                    dao.clearRules()
                    dao.clearObservations()
                    prepared.apps.forEach { dao.putRule(RuleEntity(it.id, "app", SyncJson.encodeToString(it))) }
                    prepared.backups.forEach { dao.putRule(RuleEntity(it.id, "backup", SyncJson.encodeToString(it))) }
                    app.settings.commitConfiguration(replacement)
                    preferencesWritten = true
                }
            } catch (exception: Exception) {
                if (preferencesWritten) {
                    try { app.settings.rollbackConfiguration(replacement) }
                    catch (rollback: Exception) { exception.addSuppressed(rollback); error("导入未完成，请检查当前配置后重试") }
                }
                throw exception
            }
        }
    }

    private fun hasExportPermission(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }.getOrDefault(false)
}

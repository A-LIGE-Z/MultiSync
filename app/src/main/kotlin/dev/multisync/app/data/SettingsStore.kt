package dev.multisync.app.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.multisync.core.normalizeRemoteDirectory
import dev.multisync.core.normalizeWebDavUrl
import dev.multisync.core.SshTunnelConfig
import dev.multisync.core.AppRule
import dev.multisync.core.SyncJson
import dev.multisync.core.BackupCredentials
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

data class Settings(
    val baseUrl: String = "",
    val username: String = "",
    val deviceId: String = "",
    val deviceName: String = Build.MODEL,
    val intervalMinutes: Long = 30,
    val unmeteredOnly: Boolean = true,
    val allowHttp: Boolean = false,
    val theme: String = "system",
    val hasPassword: Boolean = false,
    val softwareUploadPath: String = "多端同步/apps",
    val fileUploadPath: String = "多端同步/backups",
    val sshEnabled: Boolean = false,
    val hasSshTunnel: Boolean = false,
    val autoInstallDownloads: Boolean = true,
)

internal class ConnectionSnapshot(val settings: Settings, val password: String, val tunnel: SshTunnelConfig?) {
    override fun toString() = "ConnectionSnapshot([hidden])"
}

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("sync_settings", Context.MODE_PRIVATE)
    private val alias = "multisync_webdav_password"
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    private fun load(): Settings {
        val id = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("device_id", it).commit()
        }
        return Settings(
            baseUrl = (preferences.getString("base_url", "") ?: "").let { raw ->
                if (raw.isBlank()) "" else runCatching { normalizeWebDavUrl(raw) }.getOrDefault(raw)
            },
            username = preferences.getString("username", "") ?: "",
            deviceId = id, deviceName = preferences.getString("device_name", Build.MODEL) ?: Build.MODEL,
            intervalMinutes = preferences.getLong("interval", 30),
            unmeteredOnly = preferences.getBoolean("unmetered", true),
            allowHttp = preferences.getBoolean("allow_http", false),
            theme = preferences.getString("theme", "system") ?: "system",
            hasPassword = preferences.contains("password"),
            softwareUploadPath = preferences.getString("software_upload_path", "多端同步/apps") ?: "多端同步/apps",
            fileUploadPath = preferences.getString("file_upload_path", "多端同步/backups") ?: "多端同步/backups",
            sshEnabled = preferences.getBoolean("ssh_enabled", false), hasSshTunnel = preferences.contains("ssh_tunnel"),
            autoInstallDownloads = preferences.getBoolean("auto_install_downloads", true),
        )
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized fun save(settings: Settings, newPassword: String?) {
        require(settings.intervalMinutes >= 15) { "检查周期不能少于 15 分钟" }
        if (settings.sshEnabled) tunnel().validate(settings.baseUrl)
        val editor = preferences.edit().putString("base_url", if (settings.baseUrl.isBlank()) "" else normalizeWebDavUrl(settings.baseUrl))
            .putString("username", settings.username.trim()).putString("device_name", settings.deviceName.trim())
            .putLong("interval", settings.intervalMinutes).putBoolean("unmetered", settings.unmeteredOnly)
            .putBoolean("allow_http", settings.allowHttp).putString("theme", settings.theme)
            .putString("software_upload_path", normalizeRemoteDirectory(settings.softwareUploadPath))
            .putString("file_upload_path", normalizeRemoteDirectory(settings.fileUploadPath))
            .putBoolean("ssh_enabled", settings.sshEnabled)
            .putBoolean("auto_install_downloads", settings.autoInstallDownloads)
        if (!newPassword.isNullOrEmpty()) {
            editor.putString("password", seal(newPassword))
        }
        check(editor.commit()) { "设置保存失败" }
        mutable.value = load()
    }

    fun defaultAppRule(): AppRule? = preferences.getString("default_app_rule", null)?.let { runCatching { SyncJson.decodeFromString<AppRule>(it) }.getOrNull() }

    @Synchronized fun saveDefaultAppRule(rule: AppRule) {
        check(preferences.edit().putString("default_app_rule", SyncJson.encodeToString(rule)).commit()) { "默认规则保存失败" }
    }

    fun password(): String {
        val encrypted = preferences.getString("password", null) ?: return ""
        return unseal(encrypted)
    }

    @Synchronized internal fun connectionSnapshot(): ConnectionSnapshot {
        val settings = state.value
        return ConnectionSnapshot(settings, if (settings.baseUrl.isBlank()) "" else password(),
            if (settings.sshEnabled) tunnel() else null)
    }

    @Synchronized fun saveTunnel(config: SshTunnelConfig) {
        config.validate(state.value.baseUrl)
        val encrypted = seal(SyncJson.encodeToString(config))
        check(preferences.edit().putString("ssh_tunnel", encrypted).putBoolean("ssh_enabled", true).commit()) { "上传通道保存失败" }
        mutable.value = load()
    }

    fun tunnel(): SshTunnelConfig {
        val encrypted = preferences.getString("ssh_tunnel", null) ?: error("请先导入 SSH 上传通道")
        return parseTunnel(unseal(encrypted).byteInputStream())
    }

    internal class ConfigurationReplacement(val before: Map<String, *>, val after: Map<String, *>) {
        override fun toString() = "ConfigurationReplacement([hidden])"
    }

    @Synchronized internal fun prepareConfiguration(value: Settings, credentials: BackupCredentials?, preserveCredentials: Boolean): ConfigurationReplacement {
        val before = preferences.all.toMap()
        val values = before.toMutableMap()
        if (credentials != null) {
            if (credentials.password.isEmpty()) values.remove("password") else values["password"] = seal(credentials.password)
            val incomingTunnel = credentials.sshTunnel
            if (incomingTunnel == null) values.remove("ssh_tunnel")
            else { incomingTunnel.validate(value.baseUrl); values["ssh_tunnel"] = seal(SyncJson.encodeToString(incomingTunnel)) }
        } else if (!preserveCredentials) {
            values.remove("password")
            values.remove("ssh_tunnel")
        }
        if (value.sshEnabled) {
            check(values.containsKey("ssh_tunnel")) { "导入配置缺少上传通道" }
            (credentials?.sshTunnel ?: tunnel()).validate(value.baseUrl)
        }
        values.putAll(mapOf(
            "base_url" to (if (value.baseUrl.isBlank()) "" else normalizeWebDavUrl(value.baseUrl)),
            "username" to value.username.trim(), "device_name" to value.deviceName,
            "interval" to value.intervalMinutes, "unmetered" to value.unmeteredOnly, "allow_http" to value.allowHttp,
            "theme" to value.theme, "software_upload_path" to normalizeRemoteDirectory(value.softwareUploadPath),
            "file_upload_path" to normalizeRemoteDirectory(value.fileUploadPath), "ssh_enabled" to value.sshEnabled,
            "auto_install_downloads" to value.autoInstallDownloads,
        ))
        // Device identity belongs to this installation and is never copied from another device.
        values["device_id"] = state.value.deviceId
        return ConfigurationReplacement(before, values.toMap())
    }

    @Synchronized internal fun commitConfiguration(replacement: ConfigurationReplacement) {
        check(preferences.all == replacement.before) { "当前配置已变化，请重新预览导入文件" }
        replacePreferences(replacement.after)
    }

    @Synchronized internal fun rollbackConfiguration(replacement: ConfigurationReplacement) {
        check(preferences.all == replacement.after) { "导入失败且配置已变化，请检查当前设置" }
        replacePreferences(replacement.before)
    }

    private fun replacePreferences(values: Map<String, *>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            null -> Unit
            else -> error("配置存储格式无效")
        } }
        check(editor.commit()) { "配置保存失败，导入未完成" }
        mutable.value = load()
    }

    private fun seal(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun unseal(encrypted: String): String {
        val parts = encrypted.split(':', limit = 2)
        check(parts.size == 2) { "保存的凭据不可用，请重新设置密码" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    companion object {
        fun parseTunnel(input: java.io.InputStream): SshTunnelConfig {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 128 * 1024) { "上传通道文件过大" }
                output.write(buffer, 0, count)
            }
            return try { SyncJson.decodeFromString<SshTunnelConfig>(output.toString("UTF-8")) }
            catch (_: kotlinx.serialization.SerializationException) { error("上传通道文件格式无效") }
        }
    }
}

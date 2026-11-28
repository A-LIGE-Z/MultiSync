package dev.multisync.core

import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.bouncycastle.jce.provider.BouncyCastleProvider

@Serializable
data class BackupSettings(
    val baseUrl: String = "", val username: String = "", val deviceName: String = "",
    val intervalMinutes: Long = 30, val unmeteredOnly: Boolean = true, val allowHttp: Boolean = false,
    val theme: String = "system", val softwareUploadPath: String = "多端同步/apps",
    val fileUploadPath: String = "多端同步/backups", val sshEnabled: Boolean = false,
    val autoInstallDownloads: Boolean = true,
)

@Serializable
data class BackupCredentials(val password: String = "", val sshTunnel: SshTunnelConfig? = null) {
    override fun toString() = "BackupCredentials([hidden])"
}

@Serializable
data class ConfigurationBackup(
    val format: String = "multisync-config", val schema: Int = 1, val createdAt: Long,
    val sourceDeviceId: String, val settings: BackupSettings,
    val apps: List<AppRule> = emptyList(), val backups: List<BackupRule> = emptyList(),
    val credentials: BackupCredentials? = null,
)

@Serializable
private data class EncryptedConfiguration(
    val format: String = "multisync-config-encrypted", val schema: Int = 1,
    val kdf: String = "PBKDF2-HMAC-SHA256", val iterations: Int = ConfigurationBackupCodec.ITERATIONS,
    val salt: String, val iv: String, val ciphertext: String,
)

object ConfigurationBackupCodec {
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    const val ITERATIONS = 210_000
    private const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val aad = "multisync-config-encrypted:1:PBKDF2-HMAC-SHA256:$ITERATIONS".toByteArray()

    fun read(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkCancellation()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_FILE_BYTES) { "配置文件过大，最大支持 4 MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun isEncrypted(bytes: ByteArray): Boolean = when (format(bytes)) {
        "multisync-config-encrypted" -> true
        "multisync-config" -> false
        else -> error("不是多端同步配置文件")
    }

    fun encode(backup: ConfigurationBackup, passphrase: CharArray? = null): ByteArray {
        validate(backup)
        require(backup.credentials == null || passphrase != null) { "包含连接凭据时必须加密导出" }
        val plain = json.encodeToString(backup).toByteArray()
        try {
            require(plain.size <= MAX_PAYLOAD_BYTES) { "配置内容过大" }
            if (passphrase == null) return plain.copyOf()
            require(passphrase.size in 8..1024) { "备份密码需为 8～1024 个字符" }
            val random = SecureRandom()
            val salt = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(12).also(random::nextBytes)
            val key = derive(passphrase, salt)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                cipher.updateAAD(aad)
                val encrypted = EncryptedConfiguration(salt = salt.toByteString().base64(), iv = iv.toByteString().base64(),
                    ciphertext = cipher.doFinal(plain).toByteString().base64())
                return json.encodeToString(encrypted).toByteArray()
            } finally { key.fill(0) }
        } finally { plain.fill(0) }
    }

    fun decode(bytes: ByteArray, passphrase: CharArray? = null): ConfigurationBackup {
        if (!isEncrypted(bytes)) {
            val backup = parsePayload(bytes)
            require(backup.credentials == null) { "普通配置文件不能包含连接凭据，请使用加密备份" }
            return backup
        }
        val envelope = try { json.decodeFromString<EncryptedConfiguration>(bytes.toString(Charsets.UTF_8)) }
        catch (_: Exception) { error("加密配置文件格式无效") }
        require(envelope.schema == 1) { "配置备份版本不受支持，请升级客户端" }
        require(envelope.kdf == "PBKDF2-HMAC-SHA256" && envelope.iterations == ITERATIONS) { "配置备份加密参数不受支持" }
        require(passphrase != null && passphrase.size in 1..1024) { "请输入备份密码" }
        val salt = envelope.salt.decodeBase64()?.toByteArray()
        val iv = envelope.iv.decodeBase64()?.toByteArray()
        val encrypted = envelope.ciphertext.decodeBase64()?.toByteArray()
        require(salt?.size == 16 && iv?.size == 12 && encrypted != null && encrypted.size in 16..(MAX_PAYLOAD_BYTES + 16)) { "加密配置文件格式无效" }
        val key = derive(passphrase, salt)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(encrypted)
        } catch (_: Exception) { error("备份密码错误或文件已损坏") }
        finally { key.fill(0) }
        return try { parsePayload(plain) } finally { plain.fill(0) }
    }

    private fun format(bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_BYTES) { "配置文件为空或超过 4 MB" }
        return try { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["format"]?.jsonPrimitive?.content.orEmpty() }
        catch (_: Exception) { error("配置文件格式无效") }
    }

    private fun parsePayload(bytes: ByteArray): ConfigurationBackup {
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "配置内容过大" }
        val backup = try { json.decodeFromString<ConfigurationBackup>(bytes.toString(Charsets.UTF_8)) }
        catch (_: Exception) { error("配置文件内容无效") }
        validate(backup)
        return backup
    }

    private fun derive(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        try {
            val factory = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }
            catch (_: java.security.NoSuchAlgorithmException) {
                // Android 7 has no platform implementation for this KDF.
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", BouncyCastleProvider())
            }
            return factory.generateSecret(spec).encoded
        } finally { spec.clearPassword() }
    }

    fun validate(backup: ConfigurationBackup) {
        require(backup.format == "multisync-config" && backup.schema == 1) { "配置备份版本不受支持，请升级客户端" }
        require(backup.createdAt >= 0 && backup.sourceDeviceId.isNotBlank() && backup.sourceDeviceId.length <= 128) { "配置备份设备信息无效" }
        val settings = backup.settings
        require(settings.intervalMinutes in 15..(Long.MAX_VALUE / 60_000)) { "检查周期无效，至少需 15 分钟" }
        require(settings.theme in setOf("system", "light", "dark")) { "外观设置无效" }
        require(settings.deviceName.length <= 160 && settings.username.length <= 1024) { "配置内容无效" }
        normalizeRemoteDirectory(settings.softwareUploadPath)
        normalizeRemoteDirectory(settings.fileUploadPath)
        if (settings.baseUrl.isNotBlank()) {
            val url = settings.baseUrl.trim().toHttpUrlOrNull()
            require(url != null && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
                (url.isHttps || settings.allowHttp)) { "配置中的 WebDAV 地址无效" }
        }
        require(backup.apps.size + backup.backups.size <= 1000) { "配置规则过多，最多支持 1000 条" }
        val ids = backup.apps.map { it.id } + backup.backups.map { it.id }
        require(ids.all { it.isNotBlank() && it.length <= 128 } && ids.distinct().size == ids.size) { "配置规则标识无效或重复" }
        backup.apps.forEach {
            validatePackageName(it.packageName)
            require(it.name.isNotBlank() && it.name.length <= 160 && it.applicationName.length <= 160 && (it.publish || it.subscribe)) { "软件规则内容无效" }
            normalizeRemotePath(it.remotePath)
            require(it.legacyRemotePaths.size <= 20) { "软件规则历史目录过多" }
            it.legacyRemotePaths.forEach(::normalizeRemoteDirectory)
            require(it.trustedSigners.all { signer -> signer.matches(Regex("[a-f0-9]{64}")) }) { "软件规则签名格式无效" }
            if (it.exportTreeUri.isNotBlank()) validateDirectoryUri(it.exportTreeUri, allowFile = false)
        }
        backup.backups.forEach {
            require(it.name.isNotBlank() && it.name.length <= 160 && it.stableSeconds in 60..86400) { "备份规则内容无效" }
            normalizeRemoteDirectory(it.remotePath)
            validateDirectoryUri(it.sourceTreeUri, allowFile = true)
        }
        backup.credentials?.let {
            require(it.password.length <= 16 * 1024) { "连接凭据内容无效" }
            it.sshTunnel?.validate(settings.baseUrl)
        }
    }

    private fun validateDirectoryUri(value: String, allowFile: Boolean) {
        val uri = try { java.net.URI(value) } catch (_: Exception) { error("规则目录地址无效") }
        require(value.length <= 8192 && (uri.scheme == "content" || (allowFile && uri.scheme == "file")) &&
            uri.path?.startsWith('/') == true && uri.query == null && uri.fragment == null) { "规则目录地址无效" }
    }
}

package dev.multisync.core

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ConfigurationBackupTest {
    private val basic = ConfigurationBackup(createdAt = 123, sourceDeviceId = "device-one",
        settings = BackupSettings(baseUrl = "https://openlist.example/dav/", username = "user", softwareUploadPath = "软件", fileUploadPath = "文件"),
        apps = listOf(AppRule("app", "测试软件", "org.example.app", "软件/测试软件", trustedSigners = listOf("a".repeat(64)))),
        backups = listOf(BackupRule("backup", "备份文件", "file:///storage/emulated/0/Download/backup", "文件")))
    // Deliberately invalid key content for serialization tests; no usable credentials.
    private val full = basic.copy(credentials = BackupCredentials("fixture-password", SshTunnelConfig("upload.example", "upload-only",
        "a".repeat(44), "-----BEGIN OPENSSH PRIVATE KEY-----\nfixture-private-key\n-----END OPENSSH PRIVATE KEY-----",
        openlist_urls = listOf("https://openlist.example"))))
    private val passphrase get() = "备份密码-test-2026".toCharArray()

    @Test fun plainExportRoundTripsSettingsAndRulesWithoutCredentials() {
        val bytes = ConfigurationBackupCodec.encode(basic)
        assertFalse(ConfigurationBackupCodec.isEncrypted(bytes))
        assertEquals(basic, ConfigurationBackupCodec.decode(bytes))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("fixture-password"))
    }

    @Test fun fullEncryptedExportRoundTripsCredentialsWithFreshSaltAndIv() {
        val first = ConfigurationBackupCodec.encode(full, passphrase)
        val second = ConfigurationBackupCodec.encode(full, passphrase)
        assertTrue(ConfigurationBackupCodec.isEncrypted(first))
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.UTF_8).contains("fixture-password"))
        assertFalse(first.toString(Charsets.UTF_8).contains("fixture-private-key"))
        assertEquals(full, ConfigurationBackupCodec.decode(first, passphrase))
    }

    @Test fun wrongPasswordAndTamperedCiphertextFailBeforeAnyConfigurationIsReturned() {
        val bytes = ConfigurationBackupCodec.encode(full, passphrase)
        val wrong = assertThrows(IllegalStateException::class.java) { ConfigurationBackupCodec.decode(bytes, "incorrect-password".toCharArray()) }
        assertTrue(wrong.message!!.contains("密码错误"))
        assertFalse(wrong.message!!.contains("fixture-password"))
        val text = bytes.toString(Charsets.UTF_8)
        val marker = "\"ciphertext\": \""
        val start = text.indexOf(marker) + marker.length
        val modified = text.substring(0, start) + (if (text[start] == 'A') 'B' else 'A') + text.substring(start + 1)
        assertThrows(IllegalStateException::class.java) { ConfigurationBackupCodec.decode(modified.toByteArray(), passphrase) }
    }

    @Test fun credentialBearingPlaintextIsRejectedDuringBothExportAndImport() {
        assertThrows(IllegalArgumentException::class.java) { ConfigurationBackupCodec.encode(full) }
        assertThrows(IllegalArgumentException::class.java) { ConfigurationBackupCodec.decode(SyncJson.encodeToString(full).toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { ConfigurationBackupCodec.encode(full, "short".toCharArray()) }
    }

    @Test fun futureSchemasInvalidPathsAndDuplicateRuleIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ConfigurationBackupCodec.encode(basic.copy(schema = 2)) }
        assertThrows(IllegalArgumentException::class.java) { ConfigurationBackupCodec.encode(basic.copy(settings = basic.settings.copy(fileUploadPath = "a/../b"))) }
        assertThrows(IllegalArgumentException::class.java) { ConfigurationBackupCodec.encode(basic.copy(backups = listOf(basic.backups.single().copy(id = "app")))) }
        assertThrows(IllegalArgumentException::class.java) { ConfigurationBackupCodec.encode(basic.copy(backups = listOf(basic.backups.single().copy(sourceTreeUri = "https://example/backup")))) }
    }

    @Test fun malformedOversizedAndUnsupportedEncryptionParametersFailWithSafeErrors() {
        val malformed = assertThrows(IllegalStateException::class.java) { ConfigurationBackupCodec.decode("{fixture-private-key".toByteArray()) }
        assertFalse(malformed.message!!.contains("fixture-private-key"))
        assertThrows(IllegalArgumentException::class.java) { ConfigurationBackupCodec.read(ByteArray(ConfigurationBackupCodec.MAX_FILE_BYTES + 1).inputStream()) }
        val bytes = ConfigurationBackupCodec.encode(basic, passphrase).toString(Charsets.UTF_8).replace("210000", "2147483647").toByteArray()
        assertThrows(IllegalArgumentException::class.java) { ConfigurationBackupCodec.decode(bytes, passphrase) }
    }
}

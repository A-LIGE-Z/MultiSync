package dev.multisync.core

import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ApkDownloadTest {
    private class Cloud(val record: CatalogRelease, var content: ByteArray) : CloudGateway {
        var published: Release = record.release
        var apkReads = 0
        var interrupt = false
        override fun list(path: String) = when {
            path == record.directory -> listOf(RemoteEntry("releases", true, 0))
            path.endsWith("/releases") -> listOf(RemoteEntry("one.json", false, 100))
            else -> emptyList()
        }
        override fun open(path: String): InputStream {
            if (path.endsWith(".json")) return SyncJson.encodeToString(published).byteInputStream()
            apkReads++
            if (interrupt) throw InterruptedIOException("fixture cancellation")
            return content.inputStream()
        }
        override fun exists(path: String): Boolean = error("Download does not inspect unrelated files")
        override fun putFile(path: String, file: File): Unit = error("Download cannot upload")
        override fun putBytes(path: String, bytes: ByteArray): Unit = error("Download cannot commit remote records")
    }
    private fun fixture(run: (Cloud, File, CatalogRelease, ByteArray) -> Unit) {
        val content = ByteArray(512 * 1024) { (it % 251).toByte() }
        val hash = digest(content.inputStream())
        val release = Release(packageName = "org.example.app", label = "测试软件", versionCode = 12, versionName = "1.2",
            signerDigests = listOf("a".repeat(64)), apkPath = namedApkPath("apps/Test", "测试软件", "1.2", 12),
            sha256 = hash.sha256, size = hash.size, minSdk = 24, supportedAbis = emptyList(), publisherId = "phone", publishedAt = 1)
        val record = CatalogRelease("apps/Test", release)
        val file = Files.createTempFile("download-test", ".apk").toFile()
        try { run(Cloud(record, content), file, record, content) } finally { file.delete() }
    }

    @Test fun completeDownloadConfirmsTheRecordAndReportsActualBytesBeforeValidationCompletes() = fixture { cloud, file, record, bytes ->
        val events = mutableListOf<TransferProgress>()
        var validated = false
        cloud.downloadVerifiedApk(record, file, events::add, validateApk = { actual, expected ->
            assertArrayEquals(bytes, actual.readBytes())
            assertEquals(record.release, expected)
            validated = true
        })
        assertTrue(validated)
        assertEquals(bytes.size.toLong(), events.last().bytes)
        assertEquals(TransferPhase.COMPLETE, events.last().phase)
        assertEquals(1, cloud.apkReads)
    }

    @Test fun changedReleaseIsRejectedWithoutFetchingTheApk() = fixture { cloud, file, record, _ ->
        cloud.published = cloud.published.copy(publishedAt = 2)
        assertThrows(IllegalStateException::class.java) { cloud.downloadVerifiedApk(record, file, validateApk = { _, _ -> fail() }) }
        assertEquals(0, cloud.apkReads)
        assertFalse(file.exists())
    }

    @Test fun truncatedOversizedAndWrongHashFilesAreRejectedAndRemoved() = fixture { cloud, file, record, bytes ->
        for (bad in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1), bytes.copyOf().apply { this[0] = 8 })) {
            cloud.content = bad
            assertThrows(IllegalStateException::class.java) { cloud.downloadVerifiedApk(record, file, validateApk = { _, _ -> fail() }) }
            assertFalse(file.exists())
        }
    }

    @Test fun cancellationOrInvalidApkNeverLeavesACompletedSnapshot() = fixture { cloud, file, record, _ ->
        cloud.interrupt = true
        assertThrows(InterruptedIOException::class.java) { cloud.downloadVerifiedApk(record, file, validateApk = { _, _ -> }) }
        assertFalse(file.exists())
        cloud.interrupt = false
        assertThrows(IllegalStateException::class.java) { cloud.downloadVerifiedApk(record, file, validateApk = { _, _ -> error("not an APK") }) }
        assertFalse(file.exists())
    }

    @Test fun actualApkPackageVersionSignatureAndCompatibilityMetadataMustMatch() = fixture { _, _, record, _ ->
        val release = record.release
        val identity = ApkIdentity(release.packageName, release.versionCode, release.versionName, release.signerDigests.toSet(), release.minSdk, emptySet())
        validateDownloadedApk(identity, release)
        for (bad in listOf(identity.copy(packageName = "org.other.app"), identity.copy(versionCode = 13), identity.copy(versionName = "wrong"),
            identity.copy(signers = setOf("b".repeat(64))), identity.copy(minSdk = 35), identity.copy(abis = setOf("x86")))) {
            assertThrows(IllegalStateException::class.java) { validateDownloadedApk(bad, release) }
        }
    }
}

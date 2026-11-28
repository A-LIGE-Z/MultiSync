package dev.multisync.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ApkArtifactsTest {
    @Test fun versionsShareOneSoftwareDirectoryAndUseSafeNameAndVersionFilenames() {
        val first = namedApkPath("apps/示例应用", "示例应用", "1.2.3_4", 84)
        val second = namedApkPath("apps/示例应用", "示例应用", "1.2.4", 85)
        assertEquals("apps/示例应用/示例应用_1.2.3_4.apk", first)
        assertEquals(first.substringBeforeLast('/'), second.substringBeforeLast('/'))
        assertNotEquals(first, second)
        assertEquals("A_B_C_v_1_2.apk", apkFileName(" A/B\\C\n", "v/1\\2", 12))
        assertEquals("未命名应用_12.apk", apkFileName("..", "", 12))
        assertThrows(IllegalArgumentException::class.java) { namedApkPath("apps/../outside", "A", "1", 1) }
    }

    private class Cloud : CloudGateway {
        val objects = mutableMapOf<String, ByteArray>()
        var uploads = 0
        var copies = 0
        var supportsCopy = true
        var loseCopyResponse = false
        var corruptCopy = false
        override fun exists(path: String) = objects.containsKey(path)
        override fun open(path: String): InputStream = checkNotNull(objects[path]).inputStream()
        override fun list(path: String) = emptyList<RemoteEntry>()
        override fun putBytes(path: String, bytes: ByteArray) { objects[path] = bytes }
        override fun putFile(path: String, file: File) { uploads++; objects[path] = file.readBytes() }
        override fun copyFile(source: String, destination: String): Boolean {
            if (!supportsCopy) return false
            copies++
            objects[destination] = if (corruptCopy) byteArrayOf(1) else objects.getValue(source).copyOf()
            if (loseCopyResponse) throw IOException("copy response lost")
            return true
        }
    }
    private fun fixture(run: (Cloud, File, FileDigest, String, String) -> Unit) {
        val file = Files.createTempFile("apk-name", ".apk").toFile().apply { writeText("verified installed APK") }
        val expected = digest(file)
        val legacy = "apps/A/packages/${expected.sha256}.apk"
        val named = namedApkPath("apps/A", "应用名称", "1.2.3", 123)
        val cloud = Cloud().apply { objects[legacy] = file.readBytes() }
        try { run(cloud, file, expected, legacy, named) } finally { file.delete() }
    }

    @Test fun legacyArtifactIsVerifiedAndCopiedWithoutReuploadingOrRemovingOldReferences() = fixture { cloud, file, hash, old, named ->
        assertFalse(cloud.publishNamedApk(named, listOf(old), file, hash))
        assertEquals(1, cloud.copies)
        assertEquals(0, cloud.uploads)
        assertArrayEquals(file.readBytes(), cloud.objects.getValue(old))
        assertArrayEquals(file.readBytes(), cloud.objects.getValue(named))
        assertFalse(cloud.publishNamedApk(named, listOf(old), file, hash))
        assertEquals(1, cloud.copies)
        assertEquals(0, cloud.uploads)
    }

    @Test fun lostCopyResponseIsRecoveredOnlyThroughDestinationHashVerification() = fixture { cloud, file, hash, old, named ->
        cloud.loseCopyResponse = true
        assertFalse(cloud.publishNamedApk(named, listOf(old), file, hash))
        assertEquals(0, cloud.uploads)
        assertEquals(1, cloud.copies)
    }

    @Test fun corruptCopyDoesNotBecomeACommittedArtifactOrDeleteTheLocalSnapshot() = fixture { cloud, file, hash, old, named ->
        cloud.corruptCopy = true
        assertThrows(RemoteContentMismatch::class.java) { cloud.publishNamedApk(named, listOf(old), file, hash) }
        assertTrue(file.exists())
        assertArrayEquals(file.readBytes(), cloud.objects.getValue(old))
        assertEquals(0, cloud.uploads)
    }

    @Test fun unsupportedServerCopyFallsBackToVerifiedNamedUpload() = fixture { cloud, file, hash, old, named ->
        cloud.supportsCopy = false
        assertTrue(cloud.publishNamedApk(named, listOf(old), file, hash))
        assertEquals(1, cloud.uploads)
        assertEquals(hash, cloud.open(named).use(::digest))
    }

    @Test fun existingDifferentContentIsNeverOverwrittenAndLocalCopyIsRetained() = fixture { cloud, file, hash, old, named ->
        val existing = "another build with the same display version".toByteArray()
        cloud.objects[named] = existing
        val error = assertThrows(IllegalStateException::class.java) { cloud.publishNamedApk(named, listOf(old), file, hash) }
        assertTrue(error.message.orEmpty().contains("同名但内容不同"))
        assertArrayEquals(existing, cloud.objects.getValue(named))
        assertEquals(0, cloud.uploads)
        assertEquals(0, cloud.copies)
        assertTrue(file.exists())
    }

    @Test fun previousNamedHashDirectoryCanBeMigratedWithoutNetworkUpload() = fixture { cloud, file, hash, old, named ->
        val previous = legacyNamedApkPath("apps/A", hash.sha256, "应用名称")
        cloud.objects[previous] = cloud.objects.remove(old)!!
        assertFalse(cloud.publishNamedApk(named, listOf(previous, old), file, hash))
        assertEquals(1, cloud.copies)
        assertEquals(0, cloud.uploads)
        assertEquals(hash, cloud.open(named).use(::digest))
        assertTrue(cloud.exists(previous))
    }

    @Test fun corruptLegacyCandidateIsSkippedBeforeReusingAValidCandidate() = fixture { cloud, file, hash, old, named ->
        cloud.objects["old/corrupt.apk"] = byteArrayOf(1)
        assertFalse(cloud.publishNamedApk(named, listOf("old/corrupt.apk", old), file, hash))
        assertEquals(1, cloud.copies)
        assertEquals(0, cloud.uploads)
    }
}

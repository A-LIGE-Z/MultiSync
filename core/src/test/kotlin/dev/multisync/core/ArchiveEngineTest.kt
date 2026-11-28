package dev.multisync.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ArchiveEngineTest {
    @Test fun rootDirectoryArchivesAreVerifiedAndDeduplicatedWithoutAnExtraPathPrefix() {
        val cloud = FakeCloud()
        val file = snapshot()
        try {
            val engine = ArchiveEngine(cloud)
            engine.archive(file, "one.zip", "1", "/", "device-a", "rule", false, { true }, { true })
            val result = engine.archive(file, "two.zip", "2", "/", "device-b", "rule", true, { true }, { true })
            assertTrue(result.objectPath.startsWith("objects/"))
            assertFalse(result.uploaded)
            assertTrue(result.deleted)
            assertEquals(1, cloud.contentUploads)
            assertEquals(2, cloud.objects.keys.count { it.startsWith("records/") })
        } finally { file.delete() }
    }

    private class FakeCloud : CloudGateway {
        val objects = mutableMapOf<String, ByteArray>()
        var contentUploads = 0
        var corruptUpload = false
        var failRecord = false
        var corruptRecord = false
        var responseLost = false
        var recordResponseLost = false
        override fun exists(path: String) = objects.containsKey(path)
        override fun putFile(path: String, file: File) {
            contentUploads++
            objects[path] = if (corruptUpload) "truncated".toByteArray() else file.readBytes()
            if (responseLost) throw java.io.IOException("response lost after upload")
        }
        override fun putBytes(path: String, bytes: ByteArray) {
            if (failRecord) error("record write failed")
            objects[path] = if (corruptRecord) "{}".toByteArray() else bytes
            if (recordResponseLost) throw java.io.IOException("response lost after commit")
        }
        override fun open(path: String): InputStream = checkNotNull(objects[path]).inputStream()
        override fun list(path: String) = emptyList<RemoteEntry>()
    }

    private fun snapshot(): File = Files.createTempFile("backup", ".bin").toFile().apply { writeText("a complete backup") }

    @Test fun responseLossCanBeRecoveredOnlyAfterContentAndRecordAreActuallyVerified() {
        val cloud = FakeCloud().apply { responseLost = true; recordResponseLost = true }
        val file = snapshot()
        try {
            var deleted = false
            val result = ArchiveEngine(cloud).archive(file, "backup.zip", "1", "backups", "device", "rule", true, { true }, { deleted = true; true })
            assertTrue(result.uploaded)
            assertTrue(deleted)
            assertEquals(1, cloud.contentUploads)
        } finally { file.delete() }
    }

    @Test fun aTruncatedExistingHashObjectIsRepairedBeforeAnyLocalDeletion() {
        val cloud = FakeCloud()
        val file = snapshot()
        try {
            val hash = digest(file)
            cloud.objects["backups/objects/${hash.sha256.take(2)}/${hash.sha256}/blob"] = "partial".toByteArray()
            val result = ArchiveEngine(cloud).archive(file, "backup.zip", "1", "backups", "device", "rule", true, { true }, { true })
            assertTrue(result.uploaded)
            assertTrue(result.deleted)
            assertEquals(1, cloud.contentUploads)
        } finally { file.delete() }
    }

    @Test fun lostResponseWithCorruptRemoteContentDoesNotPermitDeletion() {
        val cloud = FakeCloud().apply { responseLost = true; corruptUpload = true }
        val file = snapshot()
        try {
            var deleted = false
            assertThrows(java.io.IOException::class.java) {
                ArchiveEngine(cloud).archive(file, "backup.zip", "1", "backups", "device", "rule", true, { true }, { deleted = true; true })
            }
            assertFalse(deleted)
        } finally { file.delete() }
    }

    @Test fun newUploadIsVerifiedAndRecordedBeforeDeletion() {
        val cloud = FakeCloud()
        val file = snapshot()
        var deleted = false
        try {
            val result = ArchiveEngine(cloud).archive(file, "backup.zip", "document:1", "backups", "device", "rule", true,
                sourceStillMatches = { it == digest(file) }, deleteSource = {
                    assertTrue(cloud.objects.keys.any { it.contains("/records/") })
                    deleted = true; true
                })
            assertTrue(deleted)
            assertTrue(result.uploaded)
            assertEquals(1, cloud.contentUploads)
        } finally { file.delete() }
    }

    @Test fun sameContentOnAnotherDeviceSkipsUploadAndKeepsBothSourceRecords() {
        val cloud = FakeCloud()
        val file = snapshot()
        try {
            val engine = ArchiveEngine(cloud)
            engine.archive(file, "one.zip", "1", "backups", "device-a", "rule", true, { true }, { true })
            val result = engine.archive(file, "renamed.zip", "2", "backups", "device-b", "rule", true, { true }, { true })
            assertFalse(result.uploaded)
            assertTrue(result.deleted)
            assertEquals(1, cloud.contentUploads)
            assertEquals(2, cloud.objects.keys.count { it.contains("/records/") })
        } finally { file.delete() }
    }

    @Test fun corruptedUploadNeverDeletesSourceOrPublishesRecord() {
        val cloud = FakeCloud().apply { corruptUpload = true }
        val file = snapshot()
        var deleted = false
        try {
            assertThrows(IllegalStateException::class.java) {
                ArchiveEngine(cloud).archive(file, "backup.zip", "1", "backups", "device", "rule", true, { true }, { deleted = true; true })
            }
            assertFalse(deleted)
            assertFalse(cloud.objects.keys.any { it.contains("/records/") })
        } finally { file.delete() }
    }

    @Test fun preexistingCorruptObjectIsNotTrustedWhenRepairAlsoFails() {
        val cloud = FakeCloud().apply { corruptUpload = true }
        val file = snapshot()
        var deleted = false
        try {
            val hash = digest(file).sha256
            cloud.objects["backups/objects/${hash.take(2)}/$hash/blob"] = "wrong".toByteArray()
            assertThrows(IllegalStateException::class.java) {
                ArchiveEngine(cloud).archive(file, "backup.zip", "1", "backups", "device", "rule", true, { true }, { deleted = true; true })
            }
            assertFalse(deleted)
            assertEquals(1, cloud.contentUploads)
        } finally { file.delete() }
    }

    @Test fun failedSourceRecordCommitNeverDeletesLocal() {
        val file = snapshot()
        var deleted = false
        try {
            assertThrows(IllegalStateException::class.java) {
                ArchiveEngine(FakeCloud().apply { failRecord = true }).archive(file, "backup.zip", "1", "backups", "device", "rule", true, { true }, { deleted = true; true })
            }
            assertFalse(deleted)
        } finally { file.delete() }
    }

    @Test fun unreadableSourceRecordNeverDeletesLocal() {
        val file = snapshot()
        var deleted = false
        try {
            assertThrows(Exception::class.java) {
                ArchiveEngine(FakeCloud().apply { corruptRecord = true }).archive(file, "backup.zip", "1", "backups", "device", "rule", true, { true }, { deleted = true; true })
            }
            assertFalse(deleted)
        } finally { file.delete() }
    }

    @Test fun changedSourceIsRetainedAfterSuccessfulArchive() {
        val file = snapshot()
        var deleted = false
        try {
            val result = ArchiveEngine(FakeCloud()).archive(file, "backup.zip", "1", "backups", "device", "rule", true, { false }, { deleted = true; true })
            assertTrue(result.sourceChanged)
            assertFalse(deleted)
        } finally { file.delete() }
    }

    @Test fun retryAfterCleanupFailureUsesExistingContentAndRecord() {
        val cloud = FakeCloud()
        val file = snapshot()
        try {
            val engine = ArchiveEngine(cloud)
            assertThrows(IllegalStateException::class.java) {
                engine.archive(file, "backup.zip", "1", "backups", "device", "rule", true, { true }, { false })
            }
            val result = engine.archive(file, "backup.zip", "1", "backups", "device", "rule", true, { true }, { true })
            assertTrue(result.deleted)
            assertEquals(1, cloud.contentUploads)
            assertEquals(1, cloud.objects.keys.count { it.contains("/records/") })
        } finally { file.delete() }
    }

    @Test fun renamedDocumentKeepsASeparateSourceRecordWithoutUploadingAgain() {
        val cloud = FakeCloud()
        val file = snapshot()
        try {
            val engine = ArchiveEngine(cloud)
            engine.archive(file, "old.zip", "stable-document-id", "backups", "device", "rule", false, { true }, { true })
            engine.archive(file, "new.zip", "stable-document-id", "backups", "device", "rule", true, { true }, { true })
            assertEquals(1, cloud.contentUploads)
            assertEquals(2, cloud.objects.keys.count { it.contains("/records/") })
        } finally { file.delete() }
    }

    @Test fun retryRepairsAnInterruptedSourceRecordAfterReverifyingContent() {
        val cloud = FakeCloud()
        val file = snapshot()
        try {
            val engine = ArchiveEngine(cloud)
            engine.archive(file, "backup.zip", "1", "backups", "device", "rule", false, { true }, { true })
            val path = cloud.objects.keys.single { it.contains("/records/") }
            cloud.objects[path] = "{incomplete".toByteArray()
            val result = engine.archive(file, "backup.zip", "1", "backups", "device", "rule", true, { true }, { true })
            assertTrue(result.deleted)
            assertEquals(1, cloud.contentUploads)
        } finally { file.delete() }
    }
}

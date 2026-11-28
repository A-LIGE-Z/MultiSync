package dev.multisync.core

import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

interface CloudGateway {
    fun exists(path: String): Boolean
    fun putFile(path: String, file: File)
    fun putBytes(path: String, bytes: ByteArray)
    fun open(path: String): InputStream
    fun list(path: String): List<RemoteEntry>
    fun copyFile(source: String, destination: String): Boolean = false
}

fun digest(stream: InputStream): FileDigest {
    val sha = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    var count = 0L
    while (true) {
        checkCancellation()
        val read = stream.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        sha.update(buffer, 0, read)
        count += read
    }
    return FileDigest(sha.digest().joinToString("") { "%02x".format(it) }, count)
}

fun digest(file: File): FileDigest = file.inputStream().use(::digest)

/** Stable id of an archive record, also used as its record file name under records/<device>/. */
fun archiveRecordId(deviceId: String, ruleId: String, sourceIdentity: String, originalName: String, sha256: String): String =
    UUID.nameUUIDFromBytes("$deviceId|$ruleId|$sourceIdentity|$originalName|$sha256".toByteArray()).toString()

fun checkCancellation() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("任务已取消")
}

fun CloudGateway.readBytes(path: String, maxBytes: Int = 1024 * 1024): ByteArray = open(path).use { input ->
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        checkCancellation()
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= maxBytes) { "远端记录超过大小限制" }
        output.write(buffer, 0, count)
    }
    output.toByteArray()
}

class RemoteContentMismatch : IllegalStateException("远端文件校验失败，本地文件已保留")

fun CloudGateway.verify(path: String, expected: FileDigest) {
    val actual = open(path).use(::digest)
    if (actual != expected) throw RemoteContentMismatch()
}

fun CloudGateway.uploadVerified(path: String, snapshot: File, expected: FileDigest, stage: (String) -> Unit = {}): Boolean {
    if (exists(path)) {
        stage("已有文件回读校验")
        try { verify(path, expected); return false }
        catch (_: RemoteContentMismatch) { stage("修复不完整的远端文件") }
    }
    stage("上传中")
    try { putFile(path, snapshot) }
    catch (exception: Exception) {
        if (exception !is java.io.IOException &&
            !(exception is WebDavResponseException && (exception.statusCode >= 500 || exception.statusCode == 423)) &&
            !(exception is OpenListResponseException && (exception.statusCode >= 500 || exception.statusCode == 423))) throw exception
        stage("上传响应中断，回读校验")
        try { verify(path, expected); return true }
        catch (verification: Exception) { exception.addSuppressed(verification); throw exception }
    }
    stage("远端回读校验")
    verify(path, expected)
    return true
}

fun CloudGateway.commitVerified(path: String, bytes: ByteArray) {
    try { putBytes(path, bytes) }
    catch (exception: Exception) {
        if (exception !is java.io.IOException && !(exception is WebDavResponseException && (exception.statusCode >= 500 || exception.statusCode == 423))) throw exception
        try { check(readBytes(path).contentEquals(bytes)) { "记录内容不完整" } }
        catch (verification: Exception) { exception.addSuppressed(verification); throw exception }
    }
    check(readBytes(path).contentEquals(bytes)) { "记录提交校验失败，本地文件已保留" }
}

data class ArchiveResult(val uploaded: Boolean, val deleted: Boolean, val sourceChanged: Boolean, val objectPath: String)

class ArchiveEngine(private val cloud: CloudGateway) {
    fun archive(
        snapshot: File, originalName: String, relativePath: String = "", sourceIdentity: String,
        remoteDirectory: String, deviceId: String, deviceName: String = "", ruleId: String,
        deleteAfterVerify: Boolean,
        sourceStillMatches: (FileDigest) -> Boolean,
        deleteSource: () -> Boolean,
        stage: (String) -> Unit = {},
    ): ArchiveResult {
        val root = normalizeRemoteDirectory(remoteDirectory)
        stage("计算校验值")
        val expected = digest(snapshot)
        val objectPath = remoteChildPath(root, relativePath.ifBlank { originalName })
        val uploaded = cloud.uploadVerified(objectPath, snapshot, expected, stage)
        checkCancellation()
        val recordId = archiveRecordId(deviceId, ruleId, sourceIdentity, originalName, expected.sha256)
        val recordPath = remoteChildPath(root, "records/$deviceId/$recordId.json")
        val record = ArchiveRecord(
            deviceId = deviceId, ruleId = ruleId, sourceIdentity = sourceIdentity,
            originalName = originalName, relativePath = relativePath, deviceName = deviceName, objectPath = objectPath,
            sha256 = expected.sha256, size = expected.size, archivedAt = System.currentTimeMillis(),
        )
        stage("提交来源记录")
        val previous = if (cloud.exists(recordPath)) {
            val bytes = cloud.readBytes(recordPath)
            runCatching { SyncJson.decodeFromString<ArchiveRecord>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        } else null
        if (previous?.copy(archivedAt = record.archivedAt) != record) {
            cloud.commitVerified(recordPath, SyncJson.encodeToString(record).toByteArray())
        }
        val committed = SyncJson.decodeFromString<ArchiveRecord>(cloud.readBytes(recordPath).toString(Charsets.UTF_8))
        check(committed.copy(archivedAt = record.archivedAt) == record) { "来源记录校验失败，本地文件已保留" }
        // Maintain a lightweight per-device index so listing is a couple of requests instead of one per record.
        runCatching {
            val indexPath = remoteChildPath(root, "records/$deviceId/index.json")
            val existing = if (cloud.exists(indexPath)) {
                runCatching { SyncJson.decodeFromString<List<ArchiveRecord>>(cloud.readBytes(indexPath, 4 * 1024 * 1024).toString(Charsets.UTF_8)) }.getOrDefault(emptyList())
            } else emptyList()
            val merged = (existing.filterNot { it.deviceId == record.deviceId && it.ruleId == record.ruleId &&
                it.sourceIdentity == record.sourceIdentity && it.originalName == record.originalName && it.sha256 == record.sha256 } + record)
                .sortedByDescending { it.archivedAt }.take(2000)
            cloud.commitVerified(indexPath, SyncJson.encodeToString(merged).toByteArray())
        }
        checkCancellation()
        if (!deleteAfterVerify) return ArchiveResult(uploaded, false, false, objectPath)
        stage("检查本地文件")
        if (!sourceStillMatches(expected)) return ArchiveResult(uploaded, false, true, objectPath)
        checkCancellation()
        stage("清理本地文件")
        check(deleteSource()) { "远端已归档，本地清理失败，稍后重试" }
        return ArchiveResult(uploaded, true, false, objectPath)
    }
}

package dev.multisync.core

import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

data class ArchivedFile(val directory: String, val record: ArchiveRecord)

/**
 * Reads backup archive records under one or more roots. Prefers the per-device lightweight index
 * (records/<device>/index.json) so a listing is a couple of requests instead of one GET per record;
 * falls back to scanning individual record JSONs for older archives.
 */
class RemoteArchiveCatalog(private val cloud: CloudGateway, private val parallelism: Int = 4) {
    fun read(roots: List<String>, progress: (Int, Int) -> Unit = { _, _ -> }): Pair<List<ArchivedFile>, List<CatalogProblem>> {
        val problems = Collections.synchronizedList(mutableListOf<CatalogProblem>())
        val rootsDistinct = roots.map(::normalizeRemoteDirectory).distinct()
        val devices = mutableListOf<Pair<String, String>>() // root to device name
        progress(0, rootsDistinct.size.coerceAtLeast(1))
        rootsDistinct.forEach { root ->
            checkCancellation()
            val recordsPath = remoteChildPath(root, "records")
            attemptList(recordsPath, problems).orEmpty().filter { it.isDirectory }.forEach { devices.add(root to it.name) }
        }
        val files = Collections.synchronizedList(mutableListOf<ArchivedFile>())
        val done = AtomicInteger(0)
        val total = devices.size.coerceAtLeast(1)
        parallelForEach(devices) { pair ->
            checkCancellation()
            val (root, deviceName) = pair
            val recordsPath = remoteChildPath(root, "records")
            val devicePath = remoteChildPath(recordsPath, deviceName)
            val indexPath = remoteChildPath(devicePath, "index.json")
            val entries = attemptList(devicePath, problems).orEmpty()
                .filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) && it.name != "index.json" }
            val indexed: List<ArchiveRecord>? = try {
                if (cloud.exists(indexPath)) SyncJson.decodeFromString<List<ArchiveRecord>>(cloud.readBytes(indexPath, 4 * 1024 * 1024).toString(Charsets.UTF_8)) else null
            } catch (exception: Exception) { checkCancellation(); null }
            val deviceRecords = mutableListOf<ArchiveRecord>()
            val covered = HashSet<String>()
            indexed?.forEach { record ->
                if (record.originalName.isNotBlank()) {
                    deviceRecords.add(record)
                    covered.add(archiveRecordId(record.deviceId, record.ruleId, record.sourceIdentity, record.originalName, record.sha256))
                }
            }
            var readMissing = false
            entries.forEach { entry ->
                checkCancellation()
                if (entry.name.removeSuffix(".json") in covered) return@forEach
                val path = remoteChildPath(devicePath, entry.name)
                try {
                    val record = SyncJson.decodeFromString<ArchiveRecord>(cloud.readBytes(path, 256 * 1024).toString(Charsets.UTF_8))
                    if (record.originalName.isNotBlank()) { deviceRecords.add(record); readMissing = true }
                } catch (timeout: InterruptedIOException) { checkCancellation(); problems.add(CatalogProblem(path, timeout.message ?: "读取超时")) }
                catch (exception: Exception) { checkCancellation(); problems.add(CatalogProblem(path, exception.message?.take(180) ?: "读取失败")) }
            }
            deviceRecords.forEach { files.add(ArchivedFile(root, it)) }
            // Heal the per-device index when it was missing or incomplete (best-effort).
            if (indexed == null || readMissing) {
                runCatching {
                    val all = deviceRecords.distinctBy { archiveRecordId(it.deviceId, it.ruleId, it.sourceIdentity, it.originalName, it.sha256) }
                        .sortedByDescending { it.archivedAt }.take(2000)
                    cloud.commitVerified(indexPath, SyncJson.encodeToString(all).toByteArray())
                }
            }
            progress(done.incrementAndGet(), total)
        }
        val distinct = files.distinctBy { it.directory + "|" + it.record.sha256 + "|" + it.record.originalName + "|" + it.record.archivedAt }
            .sortedByDescending { it.record.archivedAt }
        return distinct to problems.toList()
    }

    private fun attemptList(path: String, problems: MutableList<CatalogProblem>): List<RemoteEntry>? = try {
        checkCancellation(); cloud.list(path)
    } catch (timeout: InterruptedIOException) {
        checkCancellation(); problems.add(CatalogProblem(path, timeout.message ?: "目录读取超时")); null
    } catch (exception: Exception) {
        checkCancellation(); problems.add(CatalogProblem(path, exception.message?.take(180) ?: "读取失败")); null
    }

    private fun <T> parallelForEach(items: List<T>, block: (T) -> Unit) {
        if (items.isEmpty()) return
        val threads = parallelism.coerceIn(1, items.size)
        if (threads <= 1) { items.forEach(block); return }
        val pool = Executors.newFixedThreadPool(threads)
        val first = AtomicReference<Throwable?>()
        try {
            val futures = items.map { item -> pool.submit(Callable { try { block(item) } catch (t: Throwable) { first.compareAndSet(null, t); throw t } }) }
            futures.forEach { f -> try { f.get() } catch (e: ExecutionException) { first.compareAndSet(null, e.cause ?: e) } }
            first.get()?.let { throw it }
        } finally { pool.shutdownNow() }
    }
}

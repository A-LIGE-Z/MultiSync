package dev.multisync.core

import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable

@Serializable
data class CatalogRelease(val directory: String, val release: Release)
data class CatalogProblem(val path: String, val message: String)
data class RemoteCatalog(val versions: List<CatalogRelease>, val problems: List<CatalogProblem>, val directoryCount: Int)

/** Only the newest N release records per directory are downloaded; keeps the scan cheap on high-latency links. */
private const val MAX_RECORDS_PER_DIRECTORY = 30

/**
 * Reads committed release records only; it never transfers or modifies APKs.
 *
 * @param parallelism how many PROPFIND/GET requests may run at once. The default is 1 (sequential) so
 *   unit tests and small trees stay deterministic; the app raises it to cut version-scan latency.
 */
class RemoteVersionCatalog(private val cloud: CloudGateway, private val parallelism: Int = 1) {
    fun read(parentPath: String, rulePaths: List<String> = emptyList(), progress: (Int, Int) -> Unit = { _, _ -> }): RemoteCatalog {
        return readSources(listOf(parentPath), rulePaths, progress)
    }

    fun readSources(parentPaths: List<String>, rulePaths: List<String> = emptyList(), progress: (Int, Int) -> Unit = { _, _ -> }): RemoteCatalog {
        return if (parallelism <= 1) readSequential(parentPaths, rulePaths, progress)
        else readParallel(parentPaths, rulePaths, progress)
    }

    private fun readSequential(parentPaths: List<String>, rulePaths: List<String>, progress: (Int, Int) -> Unit): RemoteCatalog {
        val problems = mutableListOf<CatalogProblem>()
        val directories = collectDirectories(parentPaths, rulePaths, problems)
        progress(0, directories.size)
        val versions = mutableListOf<CatalogRelease>()
        var recordCount = 0
        directories.forEachIndexed { index, directory ->
            checkCancellation()
            val releasesPath = remoteChildPath(directory, "releases")
            val apkNames = attempt(directory, problems) { cloud.list(directory) }.orEmpty()
                .filter { !it.isDirectory }.map { it.name }.toSet()
            val entries = attempt(releasesPath, problems) { listRecordEntries(releasesPath) }.orEmpty()
            recordCount += entries.size
            check(recordCount <= 20000) { "版本记录总数过多，请缩小读取范围" }
            entries.forEach { entry ->
                checkCancellation()
                readRecord(directory, releasesPath, entry.name, problems, apkNames)?.let(versions::add)
            }
            progress(index + 1, directories.size)
        }
        return RemoteCatalog(versions.distinct(), problems, directories.size)
    }

    private fun readParallel(parentPaths: List<String>, rulePaths: List<String>, progress: (Int, Int) -> Unit): RemoteCatalog {
        val problems = java.util.Collections.synchronizedList(mutableListOf<CatalogProblem>())
        val directories = collectDirectories(parentPaths, rulePaths, problems)
        progress(0, directories.size)

        val releasesByDir = arrayOfNulls<List<RemoteEntry>>(directories.size)
        val filesByDir = arrayOfNulls<Set<String>>(directories.size)
        val listed = AtomicInteger(0)
        forEachParallel(directories) { index, directory ->
            checkCancellation()
            val releasesPath = remoteChildPath(directory, "releases")
            releasesByDir[index] = attempt(releasesPath, problems) { listRecordEntries(releasesPath) }.orEmpty()
            filesByDir[index] = attempt(directory, problems) { cloud.list(directory) }.orEmpty()
                .filter { !it.isDirectory }.map { it.name }.toSet()
            progress(listed.incrementAndGet(), directories.size)
        }

        var recordCount = 0
        val tasks = ArrayList<Triple<Int, String, String>>()
        directories.forEachIndexed { index, directory ->
            val releasesPath = remoteChildPath(directory, "releases")
            releasesByDir[index].orEmpty().forEach { entry -> tasks.add(Triple(index, releasesPath, entry.name)) }
            recordCount += releasesByDir[index].orEmpty().size
        }
        check(recordCount <= 20000) { "版本记录总数过多，请缩小读取范围" }

        val versionsByTask = arrayOfNulls<CatalogRelease>(tasks.size)
        forEachParallel(tasks) { index, task ->
            checkCancellation()
            val directory = directories[task.first]
            versionsByTask[index] = readRecord(directory, task.second, task.third, problems, filesByDir[task.first])
        }
        val versions = versionsByTask.filterNotNull()
        return RemoteCatalog(versions.distinct(), problems.toList(), directories.size)
    }

    private fun collectDirectories(parentPaths: List<String>, rulePaths: List<String>, problems: MutableList<CatalogProblem>): List<String> {
        val directories = linkedSetOf<String>()
        parentPaths.map(::normalizeRemoteDirectory).distinct().forEach { parent ->
            val children = attempt(parent, problems) { cloud.list(parent) }.orEmpty()
            check(children.count { it.isDirectory } <= 500) { "软件目录过多，请缩小读取范围" }
            if (children.any { it.isDirectory && it.name == "releases" }) directories.add(parent)
            children.filter { it.isDirectory && it.name !in setOf("releases", "packages", "objects", "records") }.forEach {
                directories.add(remoteChildPath(parent, it.name))
            }
        }
        rulePaths.forEach { directories.add(normalizeRemoteDirectory(it)) }
        check(directories.size <= 500) { "软件目录过多，请缩小读取范围" }
        return directories.toList()
    }

    private fun listRecordEntries(releasesPath: String): List<RemoteEntry> {
        val all = cloud.list(releasesPath).filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
            .also { check(it.size <= 1000) { "版本记录过多，请整理此软件目录" } }
        if (all.size <= MAX_RECORDS_PER_DIRECTORY) return all
        // Record filenames are "<versionCode>-<sha>-<deviceId>.json"; keep the newest versions only.
        return all.sortedByDescending { entry -> entry.name.substringBefore('-').toLongOrNull() ?: -1L }.take(MAX_RECORDS_PER_DIRECTORY)
    }

    private fun readRecord(directory: String, releasesPath: String, entryName: String, problems: MutableList<CatalogProblem>, apkNames: Set<String>? = null): CatalogRelease? {
        val recordPath = remoteChildPath(releasesPath, entryName)
        return attempt(recordPath, problems) {
            val release = SyncJson.decodeFromString<Release>(cloud.readBytes(recordPath, 64 * 1024).toString(Charsets.UTF_8))
            validatePackageName(release.packageName)
            check(release.schema == 1 && release.versionCode >= 0 && release.size > 0 && release.minSdk > 0 &&
                release.signerDigests.isNotEmpty() && release.sha256.matches(Regex("[a-f0-9]{64}")) &&
                isReleaseApkPath(directory, release)) { "发布记录无效或安装包路径不属于此目录" }
            // A release whose APK was removed remotely must not appear as available.
            val apkName = release.apkPath.substringAfterLast('/')
            check(if (apkNames != null) apkNames.contains(apkName) else cloud.exists(release.apkPath)) { "安装包已删除" }
            CatalogRelease(directory, release)
        }
    }

    private fun <T> forEachParallel(items: List<T>, block: (Int, T) -> Unit) {
        if (items.isEmpty()) return
        val threads = parallelism.coerceIn(1, items.size)
        if (threads <= 1) { items.forEachIndexed(block); return }
        val pool = Executors.newFixedThreadPool(threads)
        val first = AtomicReference<Throwable?>()
        try {
            val futures = ArrayList<Future<*>>(items.size)
            items.forEachIndexed { index, item ->
                futures.add(pool.submit(Callable { try { block(index, item); Unit } catch (t: Throwable) { first.compareAndSet(null, t); throw t } }))
            }
            futures.forEach { future ->
                try { future.get() } catch (exception: ExecutionException) { first.compareAndSet(null, exception.cause ?: exception) }
            }
            first.get()?.let { throw it }
        } finally { pool.shutdownNow() }
    }

    private fun <T> attempt(path: String, problems: MutableList<CatalogProblem>, read: () -> T): T? {
        var attemptIndex = 0
        while (true) {
            try {
                checkCancellation()
                return read()
            } catch (cancelled: InterruptedIOException) {
                checkCancellation()
                problems.add(CatalogProblem(path, cancelled.message ?: "目录读取超时"))
                return null
            } catch (exception: Exception) {
                checkCancellation()
                val message = exception.message ?: "读取失败"
                val throttled = message.contains("429") || message.contains("408") || message.contains("503") || message.contains("Too Many")
                // Cloudflare rate-limits bursts; back off briefly and retry a couple of times.
                if (throttled && attemptIndex < 2) {
                    try { Thread.sleep(400L * (attemptIndex + 1)) } catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); return null }
                    attemptIndex++
                    continue
                }
                problems.add(CatalogProblem(path, message.take(180)))
                return null
            }
        }
    }
}

enum class VersionRelation { NO_REMOTE, REMOTE_NEWER, SAME, LOCAL_NEWER }

data class AppVersionComparison(
    val local: InstalledApp,
    val versions: List<CatalogRelease>,
    val latest: CatalogRelease?,
    val compatibleUpdate: CatalogRelease?,
    val latestCompatible: Boolean,
    val relation: VersionRelation,
    val localSigners: Set<String>,
    val identityError: String = "",
)

fun compareAppVersions(local: InstalledApp, records: List<CatalogRelease>, signers: Set<String>, sdk: Int, abis: Set<String>,
    identityError: String = ""): AppVersionComparison {
    fun compatible(record: CatalogRelease) = VersionPolicy.isCompatible(record.release, local.packageName, signers, sdk, abis)
    val versions = records.filter { it.release.packageName == local.packageName }.sortedWith(
        compareByDescending<CatalogRelease> { it.release.versionCode }.thenByDescending { compatible(it) }
            .thenByDescending { it.release.publishedAt }.thenBy { it.release.sha256 }.thenBy { it.directory })
    val latest = versions.firstOrNull()
    val relation = when {
        latest == null -> VersionRelation.NO_REMOTE
        latest.release.versionCode > local.versionCode -> VersionRelation.REMOTE_NEWER
        latest.release.versionCode < local.versionCode -> VersionRelation.LOCAL_NEWER
        else -> VersionRelation.SAME
    }
    return AppVersionComparison(local, versions, latest,
        versions.firstOrNull { it.release.versionCode > local.versionCode && compatible(it) },
        latest?.let(::compatible) == true, relation, signers, identityError)
}

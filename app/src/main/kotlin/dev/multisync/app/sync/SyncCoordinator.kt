package dev.multisync.app.sync

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import dev.multisync.app.SyncApplication
import dev.multisync.app.data.Observation
import dev.multisync.app.data.TaskLog
import dev.multisync.app.data.TransferRecord
import dev.multisync.app.data.BackupStorage
import dev.multisync.app.data.BackupNode
import dev.multisync.app.data.ManagedStorage
import dev.multisync.app.data.hasLocalNetworkAccess
import dev.multisync.app.data.needsLocalNetwork
import dev.multisync.core.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString

data class SyncTransferState(val active: Boolean = false, val ruleName: String = "", val fileName: String = "",
    val fileIndex: Int = 0, val fileCount: Int = 0, val stage: String = "", val network: TransferProgress? = null, val error: String = "",
    val manualDownload: Boolean = false)

data class SavedApk(val uri: String, val name: String)

class SyncCoordinator(private val application: SyncApplication) {
    private val dao get() = application.database.dao()
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow("就绪")
    val status = mutableStatus.asStateFlow()
    private val mutableTransfer = MutableStateFlow(SyncTransferState())
    val transfer = mutableTransfer.asStateFlow()
    private val storage = BackupStorage(application)

    internal suspend fun <T> withConfigurationLock(block: suspend () -> T): T {
        check(mutex.tryLock()) { "同步任务正在运行，请完成后再备份或导入配置" }
        return try { block() } finally { mutex.unlock() }
    }

    private fun currentFile(rule: String, file: String, index: Int = 1, count: Int = 1, manualDownload: Boolean = false) {
        mutableTransfer.value = SyncTransferState(active = true, ruleName = rule, fileName = file, fileIndex = index, fileCount = count, stage = "准备文件", manualDownload = manualDownload)
    }

    suspend fun downloadApk(record: CatalogRelease, destination: Uri, connection: String): SavedApk = mutex.withLock {
        val release = record.release
        val name = apkFileName(release.label, release.versionName, release.versionCode)
        currentFile(release.label, name, manualDownload = true)
        val saved = AtomicBoolean(false)
        val downloadJob = currentCoroutineContext().job
        val calls = ConcurrentHashMap.newKeySet<Call>()
        var tunnel: SshOpenListTunnel? = null
        val temporary = File.createTempFile("download-", ".apk", application.cacheDir)
        try {
            coroutineScope {
                val cancellation = launch {
                    try { awaitCancellation() } finally { calls.forEach { it.cancel() } }
                }
                try {
                    runInterruptible(Dispatchers.IO) {
                        val credentials = application.settings.connectionSnapshot()
                        val settings = credentials.settings
                        check(apkConnectionKey(settings) == connection) { "连接或来源目录已变化，请刷新版本后重新下载" }
                        check(!needsLocalNetwork(settings) || hasLocalNetworkAccess(application)) { "请在设置中允许局域网访问，再下载" }
                        check(release.size > 0 && StatFs(application.cacheDir.path).availableBytes - 32L * 1024 * 1024 >= release.size) { "APK 暂存空间不足" }
                        if (settings.sshEnabled) {
                            runBlocking { log(release.label, "连接下载通道") }
                            tunnel = SshOpenListTunnel.open(checkNotNull(credentials.tunnel), settings.baseUrl)
                        }
                        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
                            .callTimeout(30, TimeUnit.MINUTES).eventListener(object : EventListener() {
                                override fun callStart(call: Call) { calls.add(call); if (Thread.currentThread().isInterrupted) call.cancel() }
                                override fun callEnd(call: Call) { calls.remove(call) }
                                override fun callFailed(call: Call, ioe: java.io.IOException) { calls.remove(call) }
                            }).build()
                        val cloud = WebDavClient(tunnel?.davUrl ?: settings.baseUrl, settings.username, credentials.password,
                            tunnel != null || settings.allowHttp, client = client, secureTunnel = tunnel != null)
                        val validate: (File, Release) -> Unit = { apk, expected ->
                            val info = archiveInfo(apk) ?: error("下载文件不是有效的 APK")
                            val abis = ZipFile(apk).use { zip -> zip.entries().asSequence().map { it.name }
                                .filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet() }
                            validateDownloadedApk(ApkIdentity(info.packageName, version(info), info.versionName.orEmpty(),
                                signers(info).toSet(), info.applicationInfo?.minSdkVersion ?: 24, abis), expected)
                        }
                        var attempts = 0
                        while (true) try {
                            cloud.downloadVerifiedApk(record, temporary, progress = ::networkProgress,
                            stage = { stage -> runBlocking { log(release.label, stage) } }, validateApk = { apk, expected ->
                                validate(apk, expected)
                            })
                            break
                        } catch (network: java.io.IOException) {
                            downloadJob.ensureActive()
                            checkCancellation()
                            if (++attempts >= 3) throw network
                            runBlocking { log(release.label, "连接中断，重试下载", "第 ${attempts + 1} 次尝试") }
                            Thread.sleep(attempts * 1000L)
                        }
                        checkCancellation()
                        runBlocking { log(release.label, "保存安装包") }
                        openDestinationOutput(destination).use { output ->
                            temporary.inputStream().use { input -> copyStream(input, output, release.size) }
                        }
                        runBlocking { log(release.label, "校验保存文件") }
                        openDestinationInput(destination).use { input ->
                            check(digest(input) == FileDigest(release.sha256, release.size)) { "保存文件校验失败，请重新下载" }
                        }
                        saved.set(true)
                        val displayName = destinationDisplayName(destination, name)
                        runBlocking { log(release.label, "下载已完成", "$displayName · 安装包与保存文件均已校验", "success") }
                        SavedApk(destination.toString(), displayName)
                    }
                } finally { cancellation.cancel() }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { log(release.label, "下载已取消", "已停止手动下载") }
            throw cancelled
        } catch (exception: Exception) {
            log(release.label, "下载失败", friendlyError(exception), "error")
            throw exception
        } finally {
            withContext(NonCancellable) {
                calls.forEach { it.cancel() }
                tunnel?.close()
                temporary.delete()
                if (!saved.get()) withContext(Dispatchers.IO) { discardIncompleteApk(destination) }
                mutableTransfer.update { it.copy(active = false) }
            }
        }
    }

    internal fun discardIncompleteApk(uri: Uri) {
        runCatching {
            if (uri.scheme == "file") uri.path?.let { File(it).delete() }
            else if (DocumentsContract.isDocumentUri(application, uri)) DocumentsContract.deleteDocument(application.contentResolver, uri)
            else application.contentResolver.delete(uri, null, null)
        }
    }

    private fun openDestinationOutput(uri: Uri): java.io.OutputStream =
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path) { "保存路径无效" })
            file.parentFile?.mkdirs()
            file.outputStream()
        } else checkNotNull(application.contentResolver.openOutputStream(uri, "wt")) { "保存位置无法写入，请重新选择" }

    private fun openDestinationInput(uri: Uri): java.io.InputStream =
        if (uri.scheme == "file") File(requireNotNull(uri.path) { "保存路径无效" }).inputStream()
        else checkNotNull(application.contentResolver.openInputStream(uri)) { "保存文件无法回读校验" }

    private fun destinationDisplayName(uri: Uri, fallback: String): String =
        if (uri.scheme == "file") File(requireNotNull(uri.path)).name
        else runCatching {
            application.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull().orEmpty().ifBlank { fallback }

    /**
     * Installs a just-downloaded APK straight from the managed Download/MultiSync folder using root.
     * Only applies to file-based destinations (the managed folder); SAF picks stay manual.
     */
    /**
     * After an APK download, if the "auto install after download" setting is on, hand the APK to the
     * system installer (generic ACTION_VIEW — whatever the device default is). No root / pm install.
     */
    internal suspend fun autoInstallDownloaded(uri: Uri, displayName: String): String {
        if (!application.settings.state.value.autoInstallDownloads) return ""
        if (uri.scheme != "file" && uri.scheme != "content") return ""
        val target: Uri = if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path) { "安装文件路径无效" })
            if (!file.isFile) return ""
            FileProvider.getUriForFile(application, application.packageName + ".fileprovider", file)
        } else uri
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(target, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            application.startActivity(intent)
            log(displayName, "已打开系统安装器", "安装包已下载，按提示完成安装", "success")
            "已打开系统安装器"
        } catch (exception: Exception) {
            log(displayName, "打开安装器失败", exception.message?.take(200) ?: "无法打开系统安装器，可在记录中重试", "error")
            "打开安装器失败：${exception.message ?: "未知错误"}"
        }
    }

    private fun networkProgress(progress: TransferProgress) = mutableTransfer.update { current ->
        val action = if (progress.direction == TransferDirection.UPLOAD) "上传" else if (current.stage.contains("校验")) "下载校验" else "下载"
        val stage = when (progress.phase) {
            TransferPhase.WAITING -> if (progress.storagePercent != null) "等待网盘写入完成" else "等待服务器确认"
            TransferPhase.COMPLETE -> if (action == "下载校验") "校验数据已下载" else "${action}完成"
            TransferPhase.FAILED -> "${action}失败"
            TransferPhase.CANCELLED -> "传输已停止"
            else -> action
        }
        current.copy(active = true, network = progress, stage = stage)
    }

    suspend fun log(name: String, stage: String, message: String = "", level: String = "info") {
        mutableStatus.value = if (name.isBlank()) stage else "$name · $stage"
        mutableTransfer.update { it.copy(stage = stage, error = if (level == "error") message.ifBlank { stage } else it.error) }
        dao.addLog(TaskLog(ruleName = name, stage = stage, message = message, level = level))
        dao.trimLogs()
    }

    suspend fun run(): Boolean = mutex.withLock {
        val settings = application.settings.state.value
        if (settings.baseUrl.isBlank()) { log("", "等待配置", "请先保存 OpenList 连接"); return@withLock true }
        mutableTransfer.value = SyncTransferState(active = true, stage = "读取同步规则")
        var tunnel: SshOpenListTunnel? = null
        try {
            val credentials = application.settings.connectionSnapshot()
            val settings = credentials.settings
            check(!needsLocalNetwork(settings) || hasLocalNetworkAccess(application)) { "请在设置中允许局域网访问，再使用 SSH 或局域网同步" }
            if (settings.sshEnabled) {
                log("连接", "连接加密上传通道")
                tunnel = runInterruptible(Dispatchers.IO) { SshOpenListTunnel.open(checkNotNull(credentials.tunnel), settings.baseUrl) }
                log("连接", "上传通道已连接", "服务器主机密钥已核验")
            }
            val cloud = WebDavClient(tunnel?.davUrl ?: settings.baseUrl, settings.username, credentials.password, tunnel != null || settings.allowHttp,
                onTransfer = ::networkProgress, preferOpenListUpload = true,
                onUploadEvent = { stage, message -> runBlocking { log(mutableTransfer.value.ruleName, stage, message) } },
                secureTunnel = tunnel != null)
            val failuresFlag = java.util.concurrent.atomic.AtomicBoolean(false)
            val entityLimit = kotlinx.coroutines.sync.Semaphore(3)
            coroutineScope {
                dao.rules().map { entity ->
                    async {
                        entityLimit.withPermit {
                            var name = "规则"
                            try {
                                when (entity.kind) {
                                    "app" -> {
                                        val rule = SyncJson.decodeFromString<AppRule>(entity.payload)
                                        name = rule.name
                                        if (rule.enabled) {
                                            val label = packageInfo(rule.packageName).applicationInfo?.loadLabel(application.packageManager)?.toString().orEmpty().ifBlank { rule.applicationName.ifBlank { rule.name } }
                                            val named = rule.copy(applicationName = label)
                                            val path = appUploadPath(named, settings.softwareUploadPath)
                                            syncApp(named.copy(remotePath = path), cloud, settings.deviceId,
                                                if (rule.followUploadPath) listOf(settings.softwareUploadPath, settings.fileUploadPath) else listOf(path),
                                                (listOf(rule.remotePath, path) + rule.legacyRemotePaths).filter(String::isNotBlank))
                                        }
                                    }
                                    "backup" -> {
                                        val rule = SyncJson.decodeFromString<BackupRule>(entity.payload)
                                        name = rule.name
                                        if (rule.enabled) { currentFile(rule.name, "扫描备份目录", 0, 0); syncBackup(rule.copy(remotePath = backupUploadPath(rule, settings.fileUploadPath)), cloud, settings.deviceId) }
                                    }
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (exception: Exception) { failuresFlag.set(true); log(name, "需处理", friendlyError(exception), "error") }
                        }
                    }
                }.awaitAll()
            }
            val failures = failuresFlag.get()
            log("", if (failures) "本轮完成，部分任务待重试" else "本轮检查完成", level = if (failures) "error" else "success")
            !failures
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (exception: Exception) { log("连接", "需处理", friendlyError(exception), "error"); false }
        finally { tunnel?.close(); mutableTransfer.update { it.copy(active = false) } }
    }

    private suspend fun syncBackup(rule: BackupRule, cloud: WebDavClient, deviceId: String) {
        val tree = storage.source(rule.sourceTreeUri, rule.deleteAfterVerify)
        val files = runInterruptible(Dispatchers.IO) { collectFiles(tree, rule.recursive) }
        val waiting = java.util.concurrent.atomic.AtomicInteger(0)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val limit = kotlinx.coroutines.sync.Semaphore(3)
        coroutineScope {
            files.mapIndexed { index, pair ->
                async {
                    limit.withPermit {
                        try {
                            if (processBackupFile(rule, cloud, deviceId, pair.first, pair.second, index, files.size, waiting)) completed.incrementAndGet()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (exception: Exception) {
                            val name = runCatching { pair.first.stat().name }.getOrDefault("文件")
                            failures.incrementAndGet(); log(rule.name, "文件需重试", "$name · ${friendlyError(exception)}", "error")
                        }
                    }
                }
            }.awaitAll()
        }
        log(rule.name, "扫描完成", "${completed.get()} 个已归档，${waiting.get()} 个等待稳定")
        check(failures.get() == 0) { "${failures.get()} 个文件未完成，本地已保留，稍后重试" }
    }

    private suspend fun processBackupFile(rule: BackupRule, cloud: WebDavClient, deviceId: String, source: BackupNode, relativeDir: String,
                                          index: Int, total: Int, waiting: java.util.concurrent.atomic.AtomicInteger): Boolean {
        val initial = source.stat()
        val name = initial.name
        val key = "${rule.id}|${source.identity}"
        val now = System.currentTimeMillis()
        val observed = dao.observation(key)
        val size = initial.size
        val modified = initial.modified
        if (size <= 0) return false
        if (observed == null || observed.size != size || observed.modified != modified) {
            dao.putObservation(Observation(key, size, modified, now))
            val waitMs = minOf(rule.stableSeconds.coerceAtLeast(0), 10L) * 1000L
            if (waitMs > 0) {
                try { Thread.sleep(waitMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw CancellationException("任务已取消") }
            }
            checkCancellation()
            val after = source.stat()
            if (after.size != size || after.modified != modified) { waiting.incrementAndGet(); return false }
        } else if (now - observed.stableSince < rule.stableSeconds * 1000) { waiting.incrementAndGet(); return false }
        currentFile(rule.name, name, index + 1, total)
        val snapshot = File.createTempFile("backup-", ".snapshot", application.cacheDir)
        try {
            val result = runInterruptible(Dispatchers.IO) {
                check(StatFs(application.cacheDir.path).availableBytes > size + 32L * 1024 * 1024) { "暂存空间不足，本地文件已保留" }
                source.open().use { input -> snapshot.outputStream().use { output -> copyStream(input, output) } }
                check(source.stat() == initial && snapshot.length() == size) { "备份文件仍在变化，稍后重试" }
                val matches: (FileDigest) -> Boolean = { expected ->
                    runCatching { source.stat() == initial && source.open().use(::digest) == expected }.getOrDefault(false)
                }
                ArchiveEngine(cloud).archive(
                    snapshot = snapshot, originalName = name, relativePath = if (relativeDir.isBlank()) name else relativeDir + name, sourceIdentity = source.identity,
                    remoteDirectory = rule.remotePath, deviceId = deviceId, ruleId = rule.id,
                    deleteAfterVerify = rule.deleteAfterVerify,
                    sourceStillMatches = matches,
                    deleteSource = { matches(digest(snapshot)) && source.delete() },
                    stage = { runBlocking { log(rule.name, it, name) } },
                )
            }
            if (result.deleted) dao.deleteObservation(key)
            if (result.uploaded) dao.addTransfer(TransferRecord(direction = "upload", name = name, size = size, status = "success", detail = rule.remotePath))
            log(rule.name, if (result.sourceChanged) "已归档，源文件变化已保留" else "已完成",
                "$name · ${if (result.uploaded) "新文件已上传" else "内容重复，已跳过上传"} · ${if (result.deleted) "本地已清理" else "本地保留"}", "success")
            return true
        } finally { snapshot.delete() }
    }

    private fun collectFiles(root: BackupNode, recursive: Boolean): List<Pair<BackupNode, String>> {
        val output = ArrayList<Pair<BackupNode, String>>()
        fun visit(directory: BackupNode, depth: Int, prefix: String) {
            check(depth <= 16) { "目录层级过深" }
            for (child in directory.children()) {
                checkCancellation()
                val stat = child.stat()
                val name = stat.name
                if (name == "multisync-apks" || name.startsWith(".multisync")) continue
                if (stat.directory) { if (recursive) visit(child, depth + 1, prefix + name + "/") }
                else if (!Regex("(?i).*(\\.tmp|\\.part|\\.download|\\.swp)$").matches(name)) {
                    output.add(child to prefix)
                    check(output.size <= 5000) { "单条规则文件过多，请拆分目录" }
                }
            }
        }
        visit(root, 0, "")
        return output
    }

    /** Uploads the locally installed APK for a rule to its remote software directory (one-shot publish). */
    suspend fun publishLocalApk(rule: AppRule): String {
        val connection = application.settings.state.value
        require(connection.baseUrl.isNotBlank()) { "请先配置 OpenList 连接" }
        val credentials = application.settings.connectionSnapshot()
        check(apkConnectionKey(credentials.settings) == apkConnectionKey(connection)) { "连接已变化，请重试" }
        val tunnel = if (connection.sshEnabled) SshOpenListTunnel.open(checkNotNull(credentials.tunnel), connection.baseUrl) else null
        try {
            val cloud = WebDavClient(tunnel?.davUrl ?: connection.baseUrl, connection.username, credentials.password,
                tunnel != null || connection.allowHttp, onTransfer = ::networkProgress, preferOpenListUpload = true,
                onUploadEvent = { stage, message -> runBlocking { log(rule.name, stage, message) } }, secureTunnel = tunnel != null)
            syncApp(rule.copy(publish = true, subscribe = false), cloud, connection.deviceId, emptyList(), emptyList())
        } finally { tunnel?.close(); mutableTransfer.update { it.copy(active = false) } }
        return "完成"
    }

    private suspend fun syncApp(rule: AppRule, cloud: WebDavClient, deviceId: String, sources: List<String>, legacyPaths: List<String>) {
        validatePackageName(rule.packageName)
        val root = normalizeRemotePath(rule.remotePath)
        val installed = packageInfo(rule.packageName)
        val applicationName = rule.applicationName.ifBlank { rule.name }
        val exportName = apkFileName(applicationName, installed.versionName.orEmpty(), version(installed))
        currentFile(rule.name, exportName)
        if (rule.publish) {
            check(installed.applicationInfo?.splitSourceDirs.isNullOrEmpty()) { "此软件使用分包安装，首版暂不发布分包；请使用官方通用 APK" }
            val temporary = File.createTempFile("export-", ".apk", application.cacheDir)
            try {
                runInterruptible(Dispatchers.IO) {
                    val original = File(checkNotNull(installed.applicationInfo?.sourceDir))
                    check(StatFs(application.cacheDir.path).availableBytes > original.length() + 32L * 1024 * 1024) { "APK 暂存空间不足" }
                    original.inputStream().use { input -> temporary.outputStream().use { output -> copyStream(input, output) } }
                    check(version(packageInfo(rule.packageName)) == version(installed)) { "提取期间软件发生更新，稍后重试" }
                    val copied = archiveInfo(temporary) ?: error("提取的 APK 无法解析")
                    check(copied.packageName == rule.packageName && version(copied) == version(installed) && signers(copied) == signers(installed)) { "提取的 APK 身份校验失败" }
                    val hash = digest(temporary)
                    val apkPath = namedApkPath(root, applicationName, installed.versionName.orEmpty(), version(installed))
                    val recordPath = "$root/releases/${version(installed)}-${hash.sha256}-$deviceId.json"
                    val legacyExportName = "${rule.packageName}-${version(installed)}-${hash.sha256}.apk"
                    val oldNamedExport = "${applicationFolderName(applicationName)}.apk"
                    val previous = if (cloud.exists(recordPath)) {
                        val bytes = cloud.readBytes(recordPath)
                        runCatching { SyncJson.decodeFromString<Release>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                    } else null
                    if (previous?.let { it.schema == 1 && it.packageName == rule.packageName && it.versionCode == version(installed) &&
                        it.sha256 == hash.sha256 && it.size == hash.size && it.apkPath == apkPath && it.signerDigests == signers(installed) } != true) {
                        exportLocal(rule, temporary, exportName, hash)
                        runBlocking { log(rule.name, "发布安装包") }
                        val reusablePaths = listOfNotNull(previous?.takeIf {
                            it.schema == 1 && it.packageName == rule.packageName && it.sha256 == hash.sha256 &&
                                it.size == hash.size && isReleaseApkPath(root, it)
                        }?.apkPath) + listOf(legacyNamedApkPath(root, hash.sha256, applicationName), "$root/packages/${hash.sha256}.apk")
                        cloud.publishNamedApk(apkPath, reusablePaths, temporary, hash) { stage -> runBlocking { log(rule.name, stage) } }
                        val release = Release(
                            packageName = rule.packageName, label = rule.applicationName.ifBlank { rule.name },
                            versionCode = version(installed), versionName = installed.versionName ?: "",
                            signerDigests = signers(installed), apkPath = apkPath, sha256 = hash.sha256, size = hash.size,
                            minSdk = installed.applicationInfo?.minSdkVersion ?: 24,
                            supportedAbis = ZipFile(temporary).use { zip -> zip.entries().asSequence()
                                .map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                                .map { it.split('/')[1] }.distinct().toList() },
                            publisherId = deviceId, publishedAt = System.currentTimeMillis(),
                        )
                        val encoded = SyncJson.encodeToString(release).toByteArray()
                        cloud.commitVerified(recordPath, encoded)
                        check(cloud.readBytes(recordPath).contentEquals(encoded)) { "发布记录校验失败" }
                        runBlocking { log(rule.name, "已发布", "版本 ${release.versionName} (${release.versionCode})", "success") }
                        runBlocking {
                            dao.addTransfer(TransferRecord(direction = "upload", name = "${release.label}${if (release.versionName.isNotBlank()) " ${release.versionName}" else ""}",
                                packageName = release.packageName, versionName = release.versionName, size = release.size, status = "success", detail = apkPath))
                        }
                    }
                    val exports = listOf(exportName to "", oldNamedExport to hash.sha256, legacyExportName to "")
                        .filter { (name, folder) -> localExportExists(rule, name, folder) }
                    if (rule.deleteExportAfterVerify && exports.isNotEmpty()) {
                        cloud.verify(apkPath, hash)
                        val committed = SyncJson.decodeFromString<Release>(cloud.readBytes(recordPath).toString(Charsets.UTF_8))
                        check(committed.schema == 1 && committed.publisherId == deviceId && committed.size == hash.size &&
                            committed.packageName == rule.packageName && committed.sha256 == hash.sha256 && committed.apkPath == apkPath &&
                            committed.versionCode == version(installed) && committed.signerDigests == signers(installed)) { "发布记录校验失败，导出副本已保留" }
                        check(digest(original) == hash && version(packageInfo(rule.packageName)) == version(installed)) { "软件安装文件已变化，导出副本已保留" }
                        exports.forEach { (name, folder) -> check(cleanupLocalExport(rule, name, hash, folder)) { "导出副本已变化或无法清理，已保留" } }
                        runBlocking { log(rule.name, "APK 副本已清理", "远端文件及发布记录已校验", "success") }
                    }
                }
            } finally { temporary.delete() }
        }
        if (!rule.subscribe) return
        val release = runInterruptible(Dispatchers.IO) {
            val catalog = RemoteVersionCatalog(cloud, parallelism = 4).readSources(sources, legacyPaths)
            val selected = VersionPolicy.choose(catalog.versions.map { it.release }, rule.packageName, version(installed), rule.trustedSigners.toSet(),
                Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toSet())
            if (selected == null) check(catalog.problems.isEmpty()) { "部分版本目录读取失败，请检查连接或权限" }
            catalog.versions.firstOrNull { it.release == selected }
        } ?: return
        val selected = release.release
        check(isReleaseApkPath(release.directory, selected)) { "安装包路径不属于发布目录" }
        if (!rule.autoInstall) { log(rule.name, "发现更新", "版本 ${selected.versionName}，自动安装已关闭"); return }
        val apk = File.createTempFile("install-", ".apk", application.cacheDir)
        try {
            runInterruptible(Dispatchers.IO) {
                check(StatFs(application.cacheDir.path).availableBytes > selected.size + 32L * 1024 * 1024) { "安装暂存空间不足" }
                currentFile(rule.name, selected.apkPath.substringAfterLast('/'))
                runBlocking { log(rule.name, "下载更新", selected.versionName) }
                cloud.open(selected.apkPath).use { input -> apk.outputStream().use { output -> copyStream(input, output, selected.size) } }
                runBlocking { log(rule.name, "校验安装包") }
                check(digest(apk) == FileDigest(selected.sha256, selected.size)) { "下载的 APK 校验失败" }
                val candidate = archiveInfo(apk) ?: error("下载文件不是有效 APK")
                check(candidate.packageName == rule.packageName && version(candidate) == selected.versionCode) { "APK 的包名或版本与发布记录不符" }
                check(signers(candidate).toSet() == rule.trustedSigners.toSet() && rule.trustedSigners.isNotEmpty()) { "APK 签名与受信任软件不符" }
                check((candidate.applicationInfo?.minSdkVersion ?: 24) <= Build.VERSION.SDK_INT) { "此版本不支持当前 Android 系统" }
                check(version(packageInfo(rule.packageName)) < version(candidate)) { "当前版本已变化，本轮跳过安装" }
                runBlocking { log(rule.name, "安装更新") }
                RootShell.install(apk)
                check(version(packageInfo(rule.packageName)) == selected.versionCode) { "安装后版本校验失败" }
            }
            log(rule.name, "更新完成", selected.versionName, "success")
            dao.addTransfer(TransferRecord(direction = "download", name = selected.apkPath.substringAfterLast('/'),
                packageName = rule.packageName, versionName = selected.versionName, size = selected.size, status = "success", detail = selected.apkPath))
        } finally { apk.delete() }
    }

    private fun exportLocal(rule: AppRule, apk: File, name: String, expected: FileDigest) {
        if (rule.exportTreeUri.isBlank()) {
            val target = localExportFile(rule, name)
            if (target.exists()) {
                check(target.isFile && digest(target) == expected) { "本地存在同名但内容不同的安装包，已保留，请检查导出目录" }
                return
            }
            target.parentFile?.mkdirs()
            apk.inputStream().use { input -> target.outputStream().use { output -> copyStream(input, output) } }
        } else {
            val root = DocumentFile.fromTreeUri(application, Uri.parse(rule.exportTreeUri)) ?: error("APK 导出目录授权失效")
            val parent = root.findFile("multisync-apks") ?: root.createDirectory("multisync-apks") ?: error("无法创建 APK 导出目录")
            val directory = parent
            directory.findFile(name)?.let { existing ->
                check(existing.isFile && application.contentResolver.openInputStream(existing.uri)?.use(::digest) == expected) {
                    "本地存在同名但内容不同的安装包，已保留，请检查导出目录"
                }
                return
            }
            val destination = directory.createFile("application/vnd.android.package-archive", name) ?: error("无法创建导出文件")
            try {
                application.contentResolver.openOutputStream(destination.uri, "wt").use { output ->
                    checkNotNull(output) { "无法写入导出目录" }
                    apk.inputStream().use { input -> copyStream(input, output) }
                }
            } catch (exception: Exception) { destination.delete(); throw exception }
        }
    }

    /** Extracted/exported APKs live in Download/MultiSync/apk when storage access is granted, else the app dir. */
    private fun exportBaseDir(): File {
        ManagedStorage.ensure(application)?.let { root -> return File(root, "apk").apply { if (!isDirectory) mkdirs() } }
        return checkNotNull(application.getExternalFilesDir(null)) { "应用导出目录不可用" }
    }

    private fun localExportFile(rule: AppRule, name: String, sha256: String = ""): File {
        val base = exportBaseDir()
        require(sha256.isEmpty() || sha256.matches(Regex("[a-f0-9]{64}"))) { "安装包内容目录无效" }
        val directory = File(base, "packages/${rule.packageName}${if (sha256.isBlank()) "" else "/$sha256"}").canonicalFile
        val file = File(directory, name).canonicalFile
        check(file.parentFile == directory && directory.path.startsWith(base.canonicalPath + File.separator)) { "导出路径越界" }
        return file
    }

    private fun localExportDocument(rule: AppRule, name: String, sha256: String = ""): DocumentFile? {
        val directory = DocumentFile.fromTreeUri(application, Uri.parse(rule.exportTreeUri))?.findFile("multisync-apks") ?: return null
        return (if (sha256.isEmpty()) directory else directory.findFile(sha256))?.findFile(name)
    }

    private fun localExportExists(rule: AppRule, name: String, sha256: String = ""): Boolean =
        if (rule.exportTreeUri.isBlank()) localExportFile(rule, name, sha256).isFile else localExportDocument(rule, name, sha256)?.exists() == true

    private fun cleanupLocalExport(rule: AppRule, name: String, expected: FileDigest, sha256: String = ""): Boolean =
        if (rule.exportTreeUri.isBlank()) {
            val file = localExportFile(rule, name, sha256)
            file.isFile && digest(file) == expected && file.delete()
        } else {
            val document = localExportDocument(rule, name, sha256)
            document != null && document.isFile && application.contentResolver.openInputStream(document.uri)?.use(::digest) == expected && document.delete()
        }

    @Suppress("DEPRECATION") fun packageInfo(name: String): PackageInfo = application.packageManager.getPackageInfo(name, signingFlags())
    @Suppress("DEPRECATION") private fun archiveInfo(apk: File): PackageInfo? = application.packageManager.getPackageArchiveInfo(apk.path, signingFlags())
    @Suppress("DEPRECATION") private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION") fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION") fun signers(info: PackageInfo): List<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return certificates.orEmpty().map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.sorted()
    }

    private fun copyStream(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long = Long.MAX_VALUE) {
        val buffer = ByteArray(128 * 1024)
        var size = 0L
        while (true) {
            checkCancellation()
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            check(size <= maxBytes) { "远端文件超过声明大小" }
            output.write(buffer, 0, count)
        }
    }

    private fun friendlyError(exception: Exception): String = when (exception) {
        is java.net.UnknownHostException -> "无法解析服务器地址"
        is java.net.SocketTimeoutException -> "网络操作超时，稍后重试"
        is javax.net.ssl.SSLException -> "HTTPS 连接失败，请检查证书和地址"
        else -> exception.message?.take(250) ?: "操作失败，请检查目录权限和连接"
    }
}

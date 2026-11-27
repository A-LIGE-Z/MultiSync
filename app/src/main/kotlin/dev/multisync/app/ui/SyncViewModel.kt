package dev.multisync.app.ui

import android.app.Application
import android.os.Build
import android.net.Uri
import android.content.Context
import android.content.Intent
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.multisync.app.sync.ApkDownloadWorker
import dev.multisync.app.sync.apkConnectionKey
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.multisync.app.SyncApplication
import dev.multisync.app.data.RuleEntity
import dev.multisync.app.data.Settings
import dev.multisync.app.data.SettingsStore
import dev.multisync.app.data.InstalledAppsRepository
import dev.multisync.app.data.BackupStorage
import dev.multisync.app.data.BackupDirectoryInfo
import dev.multisync.app.data.LocalDirectory
import dev.multisync.app.data.hasDeviceFileAccess
import dev.multisync.app.data.ManagedStorage
import dev.multisync.app.data.TransferRecord
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import dev.multisync.app.data.ConfigurationBackupStore
import dev.multisync.app.data.ConfigurationImportPreview
import dev.multisync.app.data.PreparedConfiguration
import dev.multisync.app.data.hasLocalNetworkAccess
import dev.multisync.app.data.needsLocalNetwork
import dev.multisync.app.sync.RootShell
import dev.multisync.app.sync.SyncScheduler
import dev.multisync.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

data class RemoteVersionsState(
    val sourcePaths: List<String> = listOf("多端同步/apps"),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String = "",
    val comparisons: List<AppVersionComparison> = emptyList(),
    val problems: List<CatalogProblem> = emptyList(),
    val completedDirectories: Int = 0,
    val totalDirectories: Int = 0,
    val checkedAt: Long = 0,
)

data class RemoteDirectoriesState(
    val path: String = "/",
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String = "",
    val directories: List<RemoteEntry> = emptyList(),
)

data class LocalDirectoriesState(val path: String = "", val loading: Boolean = false, val error: String = "", val directories: List<LocalDirectory> = emptyList())

data class ConfigurationBackupState(val busy: Boolean = false, val importNeedsPassword: Boolean = false,
    val preview: ConfigurationImportPreview? = null, val error: String = "", val result: String = "")

data class ApkDownloadState(val ready: Boolean = false, val active: Boolean = false, val packageName: String = "",
    val sha256: String = "", val fileName: String = "", val status: String = "", val error: String = "", val savedUri: String = "",
    val install: String = "")

data class AppUpdateState(val checking: Boolean = false, val latest: String = "", val url: String = "", val name: String = "",
    val upToDate: Boolean = false, val error: String = "")

private data class ReleaseInfo(val tag: String, val apkUrl: String?, val apkName: String?)

private fun compareVersion(a: String, b: String): Int {
    fun parts(s: String) = s.split('.', '-', '+', '_').mapNotNull { it.toIntOrNull() }
    val pa = parts(a); val pb = parts(b)
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return 0
}

internal fun apkDownloadState(info: WorkInfo): ApkDownloadState {
    fun tag(prefix: String) = info.tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix).orEmpty()
    val active = !info.state.isFinished
    return ApkDownloadState(ready = true, active = active, packageName = tag(ApkDownloadWorker.PACKAGE_TAG),
        sha256 = tag(ApkDownloadWorker.SHA_TAG), fileName = info.outputData.getString(ApkDownloadWorker.FILE_NAME).orEmpty().ifBlank { tag(ApkDownloadWorker.NAME_TAG) },
        status = when (info.state) {
            WorkInfo.State.SUCCEEDED -> "下载完成，校验通过"
            WorkInfo.State.FAILED -> "下载失败"
            WorkInfo.State.CANCELLED -> "下载已取消"
            WorkInfo.State.RUNNING -> "正在下载或等待当前同步任务"
            else -> "等待网络或当前同步任务完成"
        }, error = info.outputData.getString(ApkDownloadWorker.ERROR).orEmpty(), savedUri = info.outputData.getString(ApkDownloadWorker.SAVED_URI).orEmpty(),
        install = info.outputData.getString(ApkDownloadWorker.INSTALL).orEmpty())
}

@Serializable
private data class VersionsCache(val key: String, val savedAt: Long, val versions: List<CatalogRelease>)

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SyncApplication
    private val dao = app.database.dao()
    private val applications = InstalledAppsRepository(app)
    private val backupStorage = BackupStorage(app)
    private val configurationStore = ConfigurationBackupStore(app)
    private val mutableConfigurationBackup = MutableStateFlow(ConfigurationBackupState())
    val configurationBackup = mutableConfigurationBackup.asStateFlow()
    private var pendingConfigurationExport: ByteArray? = null
    private var pendingConfigurationImport: ByteArray? = null
    private var preparedConfiguration: PreparedConfiguration? = null
    private val mutableApkDownload = MutableStateFlow(ApkDownloadState())
    val apkDownload = mutableApkDownload.asStateFlow()
    private val mutableAppUpdate = MutableStateFlow(AppUpdateState())
    val appUpdate = mutableAppUpdate.asStateFlow()
    private var apkObserver: Job? = null
    private var pendingApk: CatalogRelease? = null
    private var pendingApkConnection: String = ""
    val transfer = app.coordinator.transfer
    private val mutableFileAccess = MutableStateFlow(hasDeviceFileAccess(app))
    private val mutableLocalNetworkAccess = MutableStateFlow(hasLocalNetworkAccess(app))
    val localNetworkAccess = mutableLocalNetworkAccess.asStateFlow()
    fun refreshLocalNetworkAccess() {
        val before = mutableLocalNetworkAccess.value
        mutableLocalNetworkAccess.value = hasLocalNetworkAccess(app)
        if (!before && mutableLocalNetworkAccess.value && versionsRequested) loadRemoteVersions(force = true)
    }
    val fileAccess = mutableFileAccess.asStateFlow()
    private val mutableLocalDirectories = MutableStateFlow(LocalDirectoriesState())
    val localDirectories = mutableLocalDirectories.asStateFlow()
    private var localJob: Job? = null
    @Volatile private var localGeneration = 0L

    fun refreshFileAccess() {
        mutableFileAccess.value = hasDeviceFileAccess(app)
        if (mutableFileAccess.value) runCatching { ManagedStorage.ensure(app) }
    }
    fun browseLocalDirectories(path: String = "") {
        localJob?.cancel()
        val generation = ++localGeneration
        mutableLocalDirectories.value = LocalDirectoriesState(path, loading = true)
        localJob = viewModelScope.launch {
            try {
                val directories = runInterruptible(Dispatchers.IO) { backupStorage.browse(path) }
                if (generation == localGeneration) mutableLocalDirectories.value = LocalDirectoriesState(path, directories = directories)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) { if (generation == localGeneration) mutableLocalDirectories.value = LocalDirectoriesState(path,
                error = exception.message?.take(250) ?: "目录读取失败，请检查权限") }
        }
    }
    fun cancelLocalBrowse() { localGeneration++; localJob?.cancel(); mutableLocalDirectories.value = LocalDirectoriesState() }
    fun localDirectoryParent(path: String): String = if (backupStorage.roots().any { it.path == path }) "" else File(path).parent.orEmpty()
    fun checkBackupDirectory(uri: String, cleanup: Boolean, selected: (BackupDirectoryInfo) -> Unit) = action {
        val info = runInterruptible(Dispatchers.IO) { backupStorage.inspect(uri, cleanup) }
        notify("目录可读取：${info.files} 个文件，${info.directories} 个子目录")
        selected(info)
    }
    private val mutableApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps = mutableApps.asStateFlow()
    private val mutableAppsLoading = MutableStateFlow(false)
    val appsLoading = mutableAppsLoading.asStateFlow()
    private val mutableAppsError = MutableStateFlow("")
    val appsError = mutableAppsError.asStateFlow()
    private var appsJob: Job? = null
    val settings = app.settings.state
    val logs = dao.observeLogs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val transfers = dao.observeTransfers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val rules = dao.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val status = app.coordinator.status
    private val mutableMessage = MutableStateFlow("")
    val message = mutableMessage.asStateFlow()
    private val mutableRoot = MutableStateFlow("尚未检查")
    val rootStatus = mutableRoot.asStateFlow()
    // Version records live under the software upload directory; scanning the backup directory as well
    // multiplied requests (and latency / rate limiting) for no added version data.
    private fun versionSources() = listOf(settings.value.softwareUploadPath).distinct()
    private fun versionsCacheFile() = File(app.cacheDir, "remote_versions_cache.json")
    private fun versionsCacheKey(): String =
        listOf(settings.value.baseUrl, settings.value.username, settings.value.softwareUploadPath).joinToString("\u0000")

    /** Builds per-app comparisons from a raw release list; shared by the network path and the disk cache. */
    private fun buildComparisons(localApps: List<InstalledApp>, versions: List<CatalogRelease>): List<AppVersionComparison> {
        val byPackage = versions.groupBy { it.release.packageName }
        return localApps.map { local ->
            var identityError = ""
            var current = local
            val signers = if (byPackage.containsKey(local.packageName)) try {
                val info = app.coordinator.packageInfo(local.packageName)
                current = local.copy(versionName = info.versionName.orEmpty(), versionCode = app.coordinator.version(info))
                app.coordinator.signers(info).toSet()
            } catch (_: Exception) { identityError = "无法读取本机软件签名，请刷新重试"; emptySet() } else emptySet()
            compareAppVersions(current, byPackage[local.packageName].orEmpty(), signers, Build.VERSION.SDK_INT,
                Build.SUPPORTED_ABIS.toSet(), identityError)
        }
    }
    private val mutableVersions = MutableStateFlow(RemoteVersionsState(sourcePaths = versionSources()))
    val remoteVersions = mutableVersions.asStateFlow()
    private var versionsJob: Job? = null
    private var versionsRequested = false
    @Volatile private var versionsGeneration = 0L
    private val mutableDirectories = MutableStateFlow(RemoteDirectoriesState())
    val remoteDirectories = mutableDirectories.asStateFlow()
    private var directoriesJob: Job? = null
    @Volatile private var directoriesGeneration = 0L

    init {
        viewModelScope.launch {
            fun connectionKey(value: Settings) = listOf(value.baseUrl, value.username, value.allowHttp.toString(), value.softwareUploadPath,
                value.fileUploadPath, value.sshEnabled.toString(), value.hasSshTunnel.toString())
            var connection = connectionKey(settings.value)
            settings.map(::connectionKey).distinctUntilChanged().collect {
                if (it != connection) clearRemoteVersions()
                connection = it
            }
        }
        viewModelScope.launch {
            var wasActive = transfer.value.active
            transfer.map { it.active }.distinctUntilChanged().collect { active ->
                if (wasActive && !active && versionsRequested) loadRemoteVersions(force = true)
                wasActive = active
            }
        }
    }

    private fun clearRemoteVersions() {
        versionsGeneration++
        versionsJob?.cancel()
        mutableVersions.value = RemoteVersionsState(sourcePaths = versionSources())
        if (versionsRequested) loadRemoteVersions()
    }

    fun loadRemoteVersions(force: Boolean = false) {
        versionsRequested = true
        val sources = versionSources()
        if (!force && mutableVersions.value.sourcePaths == sources && (versionsJob?.isActive == true ||
                (mutableVersions.value.loaded && mutableVersions.value.error.isBlank()))) return
        versionsJob?.cancel()
        val generation = ++versionsGeneration
        val connection = settings.value
        mutableVersions.value = mutableVersions.value.copy(sourcePaths = sources, loading = true, error = "", completedDirectories = 0, totalDirectories = 0)
        versionsJob = viewModelScope.launch {
            try {
                require(connection.baseUrl.isNotBlank()) { "请先配置 OpenList 连接，再读取远端版本" }
                val roots = sources.map(::normalizeRemoteDirectory)
                val localApps = runInterruptible(Dispatchers.IO) { applications.read() }
                require(localApps.isNotEmpty()) { "未读取到本机应用，请允许获取已安装应用信息后刷新" }
                mutableApps.value = localApps
                // Cache-first: render the last successful scan immediately, then refresh from the network.
                if (generation == versionsGeneration && mutableVersions.value.comparisons.isEmpty()) {
                    runCatching {
                        val file = versionsCacheFile()
                        if (file.isFile) {
                            val cache = SyncJson.decodeFromString<VersionsCache>(file.readText())
                            if (cache.key == versionsCacheKey() && cache.versions.isNotEmpty()) {
                                val cachedComparisons = withContext(Dispatchers.IO) { buildComparisons(localApps, cache.versions) }
                                if (generation == versionsGeneration) mutableVersions.value = mutableVersions.value.copy(
                                    sourcePaths = roots, loading = true, loaded = true, comparisons = cachedComparisons, checkedAt = cache.savedAt)
                            }
                        }
                    }
                }
                val rulePaths = withContext(Dispatchers.IO) { dao.rules().filter { it.kind == "app" }.flatMap {
                    runCatching { val rule = SyncJson.decodeFromString<AppRule>(it.payload)
                        listOf(appUploadPath(rule, connection.softwareUploadPath), rule.remotePath) + rule.legacyRemotePaths
                    }.getOrDefault(emptyList())
                }.filter(String::isNotBlank).distinct() }
                var fetchedVersions: List<CatalogRelease> = emptyList()
                val result = runInterruptible(Dispatchers.IO) {
                    val credentials = app.settings.connectionSnapshot()
                    check(apkConnectionKey(credentials.settings) == apkConnectionKey(connection)) { "连接已变化，请重新读取版本" }
                    check(!needsLocalNetwork(connection) || hasLocalNetworkAccess(app)) { "请在设置中允许局域网访问，再读取版本" }
                    val tunnel = if (connection.sshEnabled) SshOpenListTunnel.open(checkNotNull(credentials.tunnel), connection.baseUrl) else null
                    try {
                        val cloud = WebDavClient(tunnel?.davUrl ?: connection.baseUrl, connection.username, credentials.password,
                            tunnel != null || connection.allowHttp, secureTunnel = tunnel != null)
                        val catalog = RemoteVersionCatalog(cloud, parallelism = 1).readSources(roots, rulePaths) { done, total ->
                            if (generation == versionsGeneration) mutableVersions.value = mutableVersions.value.copy(completedDirectories = done, totalDirectories = total)
                        }
                        fetchedVersions = catalog.versions
                        val comparisons = buildComparisons(localApps, catalog.versions)
                        RemoteVersionsState(sourcePaths = roots, loaded = true, comparisons = comparisons, problems = catalog.problems,
                            completedDirectories = catalog.directoryCount, totalDirectories = catalog.directoryCount, checkedAt = System.currentTimeMillis())
                    } finally { tunnel?.close() }
                }
                if (generation == versionsGeneration) {
                    mutableVersions.value = result
                    runCatching {
                        withContext(Dispatchers.IO) {
                            versionsCacheFile().writeText(SyncJson.encodeToString(VersionsCache(versionsCacheKey(), System.currentTimeMillis(), fetchedVersions)))
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) {
                if (generation == versionsGeneration) mutableVersions.value = mutableVersions.value.copy(loading = false,
                    error = exception.message?.take(250) ?: "版本读取失败，请刷新重试")
            }
        }
    }

    fun browseRemoteDirectories(draft: Settings, password: String, path: String = "/") {
        directoriesJob?.cancel()
        val generation = ++directoriesGeneration
        mutableDirectories.value = RemoteDirectoriesState(path = path, loading = true)
        directoriesJob = viewModelScope.launch {
            try {
                require(draft.baseUrl.isNotBlank()) { "请先填写 WebDAV 地址，再选择目录" }
                val directory = normalizeRemoteDirectory(path)
                val entries = runInterruptible(Dispatchers.IO) {
                    WebDavClient(draft.baseUrl, draft.username, password.ifBlank { app.settings.password() }, draft.allowHttp)
                        .listDirectories(directory).sortedBy { it.name.lowercase() }
                }
                if (generation == directoriesGeneration) mutableDirectories.value = RemoteDirectoriesState(path = directory, loaded = true, directories = entries)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) {
                if (generation == directoriesGeneration) mutableDirectories.value = mutableDirectories.value.copy(loading = false,
                    error = exception.message?.take(250) ?: "目录读取失败，请重试")
            }
        }
    }

    fun cancelDirectoryBrowse() {
        directoriesGeneration++
        directoriesJob?.cancel()
        mutableDirectories.value = RemoteDirectoriesState()
    }

    fun notify(message: String) { mutableMessage.value = message }
    fun dismissMessage() { mutableMessage.value = "" }

    fun observeApkDownloads() {
        if (apkObserver?.isActive == true) return
        apkObserver = viewModelScope.launch {
            try {
                WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ApkDownloadWorker.UNIQUE_WORK).collect { infos ->
                    val id = app.getSharedPreferences(ApkDownloadWorker.PREFERENCES, Context.MODE_PRIVATE).getString(ApkDownloadWorker.WORK_ID, "")
                    val info = infos.firstOrNull { it.id.toString() == id }
                    mutableApkDownload.value = info?.let(::apkDownloadState) ?: mutableApkDownload.value.copy(ready = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableApkDownload.value = ApkDownloadState(ready = true, error = "无法读取下载任务，请重新打开应用") }
        }
    }

    private fun downloadReady(record: CatalogRelease): Boolean {
        if (!mutableApkDownload.value.ready) { notify("正在读取下载任务，请稍候"); return false }
        if (mutableApkDownload.value.active) { notify("已有下载任务，请完成或取消后再下载"); return false }
        if (record !in remoteVersions.value.comparisons.flatMap { it.versions }) { notify("版本信息已变化，请刷新后重新选择"); return false }
        return true
    }

    fun chooseApkDownload(record: CatalogRelease, chooseDestination: (String) -> Unit) {
        if (!downloadReady(record)) return
        pendingApk = record
        pendingApkConnection = apkConnectionKey(settings.value)
        chooseDestination(apkFileName(record.release.label, record.release.versionName, record.release.versionCode))
    }

    /**
     * Downloads an APK. With all-files access it goes straight into Download/MultiSync/apk (then is
     * auto-installed on completion); otherwise it falls back to the system save picker.
     */
    fun requestApkDownload(record: CatalogRelease, fallbackPicker: (String) -> Unit) {
        if (!downloadReady(record)) return
        val dir = ManagedStorage.apkDir(app)
        if (dir == null) { chooseApkDownload(record, fallbackPicker); return }
        val name = apkFileName(record.release.label, record.release.versionName, record.release.versionCode)
        enqueueApkDownload(record, Uri.fromFile(File(dir, name)), apkConnectionKey(settings.value), name)
    }

    fun startApkDownload(uri: Uri?) {
        val record = pendingApk ?: return
        val connection = pendingApkConnection
        pendingApk = null
        pendingApkConnection = ""
        if (uri == null) return
        enqueueApkDownload(record, uri, connection,
            apkFileName(record.release.label, record.release.versionName, record.release.versionCode))
    }

    private fun enqueueApkDownload(record: CatalogRelease, uri: Uri, connection: String, name: String) {
        viewModelScope.launch {
            try {
                val downloadSettings = settings.value
                require(apkConnectionKey(downloadSettings) == connection) { "连接或来源目录已变化，请刷新版本后重新下载" }
                require(!mutableApkDownload.value.active) { "已有下载任务正在运行" }
                if (uri.scheme != "file") {
                    runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                        .getOrElse { error("此保存位置不支持后台写入，请选择设备的下载目录") }
                }
                mutableApkDownload.value = ApkDownloadState(ready = true, active = true, packageName = record.release.packageName,
                    sha256 = record.release.sha256, fileName = name, status = "下载任务已加入队列")
                withContext(Dispatchers.IO) { ApkDownloadWorker.enqueue(app, record, uri, downloadSettings) }
                observeApkDownloads()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) {
                withContext(Dispatchers.IO) { app.coordinator.discardIncompleteApk(uri) }
                mutableApkDownload.value = ApkDownloadState(ready = true, packageName = record.release.packageName, sha256 = record.release.sha256,
                    status = "下载未开始", error = exception.message?.take(250) ?: "无法开始下载，请重新选择保存位置")
            }
        }
    }

    fun cancelApkDownload() { action {
        val prefs = app.getSharedPreferences(ApkDownloadWorker.PREFERENCES, Context.MODE_PRIVATE)
        val id = prefs.getString(ApkDownloadWorker.WORK_ID, null)?.let(java.util.UUID::fromString) ?: return@action
        val uri = prefs.getString(ApkDownloadWorker.DESTINATION, null)?.let(Uri::parse)
        withContext(Dispatchers.IO) {
            val work = WorkManager.getInstance(app)
            work.cancelWorkById(id).result.get()
            if (uri != null && work.getWorkInfoById(id).get()?.state == WorkInfo.State.CANCELLED) app.coordinator.discardIncompleteApk(uri)
        }
    } }

    fun configurationError(message: String) { mutableConfigurationBackup.value = mutableConfigurationBackup.value.copy(error = message, result = "") }

    fun prepareConfigurationExport(includeCredentials: Boolean, password: String, onReady: () -> Unit) {
        if (mutableConfigurationBackup.value.busy) return
        mutableConfigurationBackup.value = mutableConfigurationBackup.value.copy(busy = true, error = "", result = "")
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val passphrase = if (includeCredentials) password.toCharArray() else null
                    try {
                        if (includeCredentials) require(password.length in 8..1024) { "备份密码需为 8～1024 个字符" }
                        ConfigurationBackupCodec.encode(configurationStore.capture(includeCredentials), passphrase)
                    } finally { passphrase?.fill('\u0000') }
                }
                pendingConfigurationExport?.fill(0)
                pendingConfigurationExport = bytes
                mutableConfigurationBackup.value = mutableConfigurationBackup.value.copy(busy = false)
                onReady()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) {
                mutableConfigurationBackup.value = mutableConfigurationBackup.value.copy(busy = false, error = exception.message?.take(250) ?: "配置备份失败")
            }
        }
    }

    fun writeConfigurationExport(uri: Uri?) {
        val bytes = pendingConfigurationExport ?: return
        pendingConfigurationExport = null
        if (uri == null) {
            bytes.fill(0)
            mutableConfigurationBackup.value = mutableConfigurationBackup.value.copy(result = "已取消导出")
            return
        }
        mutableConfigurationBackup.value = mutableConfigurationBackup.value.copy(busy = true, error = "", result = "")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt").use { output ->
                        checkNotNull(output) { "无法写入配置文件，请重新选择保存位置" }
                        output.write(bytes)
                        output.flush()
                    }
                    val saved = app.contentResolver.openInputStream(uri)?.use(ConfigurationBackupCodec::read) ?: error("配置已写入，但无法回读确认，请检查保存位置")
                    try { check(saved.contentEquals(bytes)) { "配置文件写入校验失败，请重新导出" } }
                    finally { saved.fill(0) }
                }
                mutableConfigurationBackup.value = mutableConfigurationBackup.value.copy(busy = false, result = "配置备份已导出")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) {
                mutableConfigurationBackup.value = mutableConfigurationBackup.value.copy(busy = false, error = exception.message?.take(250) ?: "配置导出失败")
            } finally { bytes.fill(0) }
        }
    }

    fun selectConfigurationImport(uri: Uri) {
        if (mutableConfigurationBackup.value.busy) return
        cancelConfigurationImport()
        mutableConfigurationBackup.value = ConfigurationBackupState(busy = true)
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use(ConfigurationBackupCodec::read) ?: error("无法读取配置文件")
                }
                pendingConfigurationImport = bytes
                if (ConfigurationBackupCodec.isEncrypted(bytes)) {
                    mutableConfigurationBackup.value = ConfigurationBackupState(importNeedsPassword = true)
                } else {
                    val prepared = withContext(Dispatchers.IO) { configurationStore.prepare(ConfigurationBackupCodec.decode(bytes)) }
                    preparedConfiguration = prepared
                    mutableConfigurationBackup.value = ConfigurationBackupState(preview = prepared.preview)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) {
                pendingConfigurationImport?.fill(0)
                pendingConfigurationImport = null
                mutableConfigurationBackup.value = ConfigurationBackupState(error = exception.message?.take(250) ?: "配置读取失败")
            }
        }
    }

    fun decryptConfigurationImport(password: String) {
        val bytes = pendingConfigurationImport ?: return
        if (mutableConfigurationBackup.value.busy) return
        mutableConfigurationBackup.value = mutableConfigurationBackup.value.copy(busy = true, error = "")
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val passphrase = password.toCharArray()
                    try { configurationStore.prepare(ConfigurationBackupCodec.decode(bytes, passphrase)) }
                    finally { passphrase.fill('\u0000') }
                }
                preparedConfiguration = prepared
                mutableConfigurationBackup.value = ConfigurationBackupState(preview = prepared.preview)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) {
                mutableConfigurationBackup.value = mutableConfigurationBackup.value.copy(busy = false, error = exception.message?.take(250) ?: "配置解密失败")
            }
        }
    }

    fun applyConfigurationImport() {
        val prepared = preparedConfiguration ?: return
        if (mutableConfigurationBackup.value.busy) return
        mutableConfigurationBackup.value = mutableConfigurationBackup.value.copy(busy = true, error = "")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { configurationStore.apply(prepared) }
                clearRemoteVersions()
                val scheduled = runCatching { SyncScheduler.schedule(app, settings.value) }.isSuccess
                cancelConfigurationImport()
                mutableConfigurationBackup.value = ConfigurationBackupState(result = "已导入 ${prepared.preview.appCount} 条软件规则、${prepared.preview.backupCount} 条备份规则" +
                    if (scheduled) "" else "；请重新打开应用恢复定时检查")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) {
                mutableConfigurationBackup.value = mutableConfigurationBackup.value.copy(busy = false, error = exception.message?.take(250) ?: "配置导入失败")
            }
        }
    }

    fun cancelConfigurationImport() {
        pendingConfigurationImport?.fill(0)
        pendingConfigurationImport = null
        preparedConfiguration = null
        mutableConfigurationBackup.value = ConfigurationBackupState()
    }

    override fun onCleared() {
        pendingConfigurationExport?.fill(0)
        pendingConfigurationImport?.fill(0)
        preparedConfiguration = null
        super.onCleared()
    }

    fun loadInstalledApps(force: Boolean = false) {
        if (appsJob?.isActive == true || (!force && mutableApps.value.isNotEmpty())) return
        appsJob = viewModelScope.launch {
            mutableAppsLoading.value = true
            mutableAppsError.value = ""
            try {
                mutableApps.value = withContext(Dispatchers.IO) { if (force) applications.invalidateIcons(); applications.read() }
                if (mutableApps.value.isEmpty()) mutableAppsError.value = "未读取到应用，请在系统权限中允许获取已安装应用信息后刷新"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableAppsError.value = "无法读取应用列表，请点击刷新重试" }
            finally { mutableAppsLoading.value = false }
        }
    }

    suspend fun applicationIcon(packageName: String, pixels: Int) = withContext(Dispatchers.IO) { applications.icon(packageName, pixels) }

    fun setTheme(theme: String) = action {
        require(theme in setOf("system", "light", "dark"))
        withContext(Dispatchers.IO) { app.settings.save(settings.value.copy(theme = theme), null) }
    }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (exception: Exception) { mutableMessage.value = exception.message?.take(250) ?: "操作失败" }
    }

    fun saveSettings(settings: Settings, password: String, onSaved: () -> Unit = {}) = action {
        require(settings.baseUrl.isNotBlank()) { "请填写 OpenList WebDAV 地址" }
        WebDavClient(settings.baseUrl, settings.username, password, settings.allowHttp)
        withContext(Dispatchers.IO) { app.settings.save(settings, password.takeIf { it.isNotBlank() }) }
        clearRemoteVersions()
        SyncScheduler.schedule(app, app.settings.state.value)
        notify("连接与上传目录已保存")
        onSaved()
    }

    fun savePreferences(settings: Settings, onSaved: () -> Unit = {}) = action {
        require(settings.intervalMinutes >= 15) { "检查周期不能少于 15 分钟" }
        withContext(Dispatchers.IO) { app.settings.save(settings, null) }
        SyncScheduler.schedule(app, app.settings.state.value)
        notify("设备与调度设置已保存")
        onSaved()
    }

    fun testConnection(settings: Settings, password: String) = action {
        notify("正在测试连接")
        withContext(Dispatchers.IO) {
            WebDavClient(settings.baseUrl, settings.username, password.ifBlank { app.settings.password() }, settings.allowHttp).checkConnection()
        }
        notify("WebDAV 目录读取成功；上传权限会在实际任务中验证")
    }

    fun importUploadTunnel(uri: Uri, baseUrl: String, onImported: () -> Unit) = action {
        require(baseUrl.trim().trimEnd('/') == settings.value.baseUrl.trim().trimEnd('/')) { "请先保存 OpenList 地址，再导入对应的上传通道" }
        withContext(Dispatchers.IO) {
            val config = app.contentResolver.openInputStream(uri)?.use(SettingsStore::parseTunnel) ?: error("无法读取上传通道文件")
            app.settings.saveTunnel(config)
        }
        notify("上传通道已导入，私钥已加密保存")
        onImported()
    }

    fun testUploadTunnel(settings: Settings, password: String) = action {
        notify("正在核验加密上传通道")
        runInterruptible(Dispatchers.IO) {
            SshOpenListTunnel.open(app.settings.tunnel(), settings.baseUrl).use { tunnel ->
                WebDavClient(tunnel.davUrl, settings.username, password.ifBlank { app.settings.password() }, true, secureTunnel = true).checkConnection()
            }
        }
        notify("服务器主机密钥已核验，OpenList 目录读取成功")
    }

    fun checkRoot() = action {
        mutableRoot.value = "检查中"
        mutableRoot.value = try { if (withContext(Dispatchers.IO) { RootShell.check() }) "已获得 Root" else "未获得 Root" }
        catch (_: Exception) { "Root 不可用或未授权" }
    }

    /** Exports the task log to Download/MultiSync/MultiSync_logs_<time>.txt for sharing with support. */
    fun exportLogs() = action {
        val root = ManagedStorage.ensure(app) ?: run {
            notify("请先在「设置 → 文件访问权限」授权后重试，日志会导出到 Download/MultiSync")
            return@action
        }
        val logs = withContext(Dispatchers.IO) { dao.allLogs() }
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val versionName = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        val text = buildString {
            appendLine("多端同步 · 任务日志导出")
            appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}")
            appendLine("应用版本: $versionName (${app.packageName})")
            appendLine("设备名称: ${settings.value.deviceName}")
            appendLine("OpenList: ${settings.value.baseUrl}  用户: ${settings.value.username}")
            appendLine("软件上传目录: ${settings.value.softwareUploadPath}")
            appendLine("文件上传目录: ${settings.value.fileUploadPath}")
            appendLine("日志条数: ${logs.size}")
            appendLine("------------------------------------------------------------")
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            logs.forEach { log ->
                appendLine("${fmt.format(Date(log.time))} [${log.level}] ${log.ruleName} · ${log.stage}${if (log.message.isNotBlank()) " · ${log.message}" else ""}")
            }
        }
        val file = File(root, "MultiSync_logs_$stamp.txt")
        withContext(Dispatchers.IO) { file.writeText(text) }
        notify("日志已导出：${ManagedStorage.label(file)}")
    }

    fun saveApp(rule: AppRule, onSaved: () -> Unit) = action {
        validatePackageName(rule.packageName.trim())
        require(rule.publish || rule.subscribe) { "请至少选择发布或订阅" }
        require(rule.packageName != app.packageName) { "首版不支持同步客户端自身更新" }
        val info = withContext(Dispatchers.IO) { app.coordinator.packageInfo(rule.packageName.trim()) }
        val signers = app.coordinator.signers(info)
        require(signers.isNotEmpty()) { "无法读取此软件签名" }
        val label = info.applicationInfo?.loadLabel(app.packageManager)?.toString().orEmpty().ifBlank { rule.applicationName.ifBlank { rule.name } }
        val named = rule.copy(packageName = rule.packageName.trim(), applicationName = label)
        val path = appUploadPath(named, settings.value.softwareUploadPath)
        val previous = dao.rules().firstOrNull { it.id == rule.id }?.let { runCatching { SyncJson.decodeFromString<AppRule>(it.payload) }.getOrNull() }
        val legacy = (rule.legacyRemotePaths + previous?.legacyRemotePaths.orEmpty() + listOfNotNull(previous?.remotePath, rule.remotePath))
            .filter { it.isNotBlank() && it != path }.distinct().takeLast(20)
        val saved = named.copy(name = rule.name.ifBlank { label },
            remotePath = path, legacyRemotePaths = legacy,
            trustedSigners = rule.trustedSigners.ifEmpty { signers })
        require(saved.trustedSigners.toSet() == signers.toSet()) { "本机软件签名已变化，请删除旧规则后重新添加以确认新来源" }
        dao.putRule(RuleEntity(saved.id, "app", SyncJson.encodeToString(saved)))
        if (versionsRequested) loadRemoteVersions(force = true)
        notify("软件规则已保存")
        onSaved()
    }

    fun saveBackup(rule: BackupRule, onSaved: () -> Unit) = action {
        runInterruptible(Dispatchers.IO) { backupStorage.inspect(rule.sourceTreeUri, rule.deleteAfterVerify) }
        require(rule.stableSeconds in 60..86400) { "稳定等待时间需在 60～86400 秒之间" }
        val saved = rule.copy(name = rule.name.ifBlank { "备份目录" }, remotePath = backupUploadPath(rule, settings.value.fileUploadPath))
        dao.putRule(RuleEntity(saved.id, "backup", SyncJson.encodeToString(saved)))
        notify("备份规则已保存")
        onSaved()
    }

    fun deleteRule(id: String) = action {
        dao.deleteRule(id)
        if (versionsRequested) loadRemoteVersions(force = true)
        notify("规则已移除，远端归档保留")
    }

    fun clearTransfers() = action {
        withContext(Dispatchers.IO) { dao.clearTransfers() }
        notify("传输记录已清空")
    }

    /** Tapping a download record: open the system installer if the local APK still exists, else say it is gone. */
    fun installFromRecord(record: TransferRecord) = action {
        if (record.direction != "download") { notify("这是上传记录，无需安装"); return@action }
        val detail = record.detail
        if (detail.isBlank()) { notify("安装包已删除"); return@action }
        val parsed = runCatching { Uri.parse(detail) }.getOrNull()
        val target: Uri = when {
            parsed?.scheme == "file" -> {
                val file = File(requireNotNull(parsed.path) { "路径无效" })
                if (!file.isFile) { notify("安装包已删除"); return@action }
                FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
            }
            parsed?.scheme == "content" -> {
                val ok = runCatching { app.contentResolver.openInputStream(parsed)?.use { true } ?: false }.getOrDefault(false)
                if (!ok) { notify("安装包已删除"); return@action }
                parsed
            }
            else -> { notify("安装包已删除"); return@action }
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(target, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { app.startActivity(intent); notify("正在打开安装器…") }
        catch (exception: Exception) { notify("无法拉起安装器：${exception.message ?: ""}") }
    }

    /** Checks the project's GitHub Releases for a newer version of this app. */
    fun checkAppUpdate() = action {
        if (mutableAppUpdate.value.checking) return@action
        mutableAppUpdate.value = AppUpdateState(checking = true)
        try {
            val info = withContext(Dispatchers.IO) { fetchLatestRelease() }
            if (info == null) { mutableAppUpdate.value = AppUpdateState(error = "GitHub 上还没有发布版本"); return@action }
            val tagVer = info.tag.removePrefix("v").removePrefix("V").trim()
            val current = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
            if (compareVersion(tagVer, current) <= 0) {
                mutableAppUpdate.value = AppUpdateState(upToDate = true, latest = tagVer)
                notify("已是最新版本 ${current.ifBlank { tagVer }}")
                return@action
            }
            if (info.apkUrl == null) { mutableAppUpdate.value = AppUpdateState(latest = tagVer, error = "新版本 $tagVer 未提供 APK 安装包"); return@action }
            mutableAppUpdate.value = AppUpdateState(latest = tagVer, url = info.apkUrl, name = info.apkName.orEmpty())
            notify("发现新版本 $tagVer")
        } catch (exception: Exception) {
            mutableAppUpdate.value = AppUpdateState(error = exception.message?.take(200) ?: "检查更新失败")
        }
    }

    /** Downloads the discovered update APK and opens the system installer. */
    fun downloadAndInstallUpdate() = action {
        val state = mutableAppUpdate.value
        if (state.url.isBlank()) { notify("请先检查更新"); return@action }
        notify("正在下载更新 ${state.latest}…")
        val file = withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder().build()
            val request = Request.Builder().url(state.url).header("User-Agent", "MultiSync").build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
                val out = File(app.cacheDir, "update-${state.latest.ifBlank { "latest" }}.apk")
                val input = response.body?.byteStream() ?: error("下载响应为空")
                input.use { source -> out.outputStream().use { sink -> source.copyTo(sink) } }
                out
            }
        }
        val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)
        notify("已下载更新，正在打开安装器…")
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val client = OkHttpClient.Builder().build()
        val request = Request.Builder().url("https://api.github.com/repos/A-LIGE-Z/MultiSync/releases/latest")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "MultiSync").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            check(response.isSuccessful) { "GitHub 返回 HTTP ${response.code}" }
            val body = response.body?.string() ?: return null
            val obj = org.json.JSONObject(body)
            val tag = obj.optString("tag_name")
            if (tag.isBlank()) return null
            var apkUrl: String? = null
            var apkName: String? = null
            val assets = obj.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val n = asset.optString("name")
                if (n.endsWith(".apk", ignoreCase = true)) { apkUrl = asset.optString("browser_download_url"); apkName = n; break }
            }
            return ReleaseInfo(tag, apkUrl, apkName)
        }
    }

    fun syncNow() {
        if (settings.value.baseUrl.isBlank()) { notify("请先在设置页保存连接"); return }
        SyncScheduler.now(app, settings.value)
        notify("检查任务已加入队列，将按网络条件执行")
    }
}

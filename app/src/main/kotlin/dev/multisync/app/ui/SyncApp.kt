package dev.multisync.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.multisync.app.R
import dev.multisync.app.data.RuleEntity
import dev.multisync.app.data.Settings
import dev.multisync.app.data.TaskLog
import dev.multisync.app.data.TransferRecord
import dev.multisync.core.AppRule
import dev.multisync.core.ArchivedFile
import dev.multisync.core.BackupRule
import dev.multisync.core.InstalledApp
import dev.multisync.core.VersionRelation
import dev.multisync.core.SyncJson
import dev.multisync.core.remoteChildPath
import dev.multisync.core.applicationFolderName
import dev.multisync.core.appUploadPath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SyncApp(model: SyncViewModel) {
    ObserveFileAccess(model)
    ObserveLocalNetworkAccess(model)
    val settings by model.settings.collectAsStateWithLifecycle()
    val entities by model.rules.collectAsStateWithLifecycle()
    val logs by model.logs.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val root by model.rootStatus.collectAsStateWithLifecycle()
    val apps = remember(entities) { entities.filter { it.kind == "app" }.mapNotNull { runCatching { SyncJson.decodeFromString<AppRule>(it.payload) }.getOrNull() } }
    val backups = remember(entities) { entities.filter { it.kind == "backup" }.mapNotNull { runCatching { SyncJson.decodeFromString<BackupRule>(it.payload) }.getOrNull() } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var recordsTab by rememberSaveable { mutableStateOf("download") }
    var detail by rememberSaveable { mutableStateOf("") }
    var appPayload by rememberSaveable { mutableStateOf("") }
    var backupPayload by rememberSaveable { mutableStateOf("") }
    var comparedPackage by rememberSaveable { mutableStateOf("") }
    var versionFromSoftware by rememberSaveable { mutableStateOf(false) }
    var connectionFromVersions by rememberSaveable { mutableStateOf(false) }
    val appDraft = remember(appPayload) { appPayload.takeIf(String::isNotEmpty)?.let { SyncJson.decodeFromString<AppRule>(it) } }
    val backupDraft = remember(backupPayload) { backupPayload.takeIf(String::isNotEmpty)?.let { SyncJson.decodeFromString<BackupRule>(it) } }
    val pageState = rememberSaveableStateHolder()
    val activity = LocalActivity.current
    val dark = settings.theme == "dark" || (settings.theme == "system" && isSystemInDarkTheme())
    val labels = listOf("概览", "软件", "文件备份", "记录", "设置")
    val icons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.Apps, Icons.Outlined.Folder, Icons.Outlined.SwapVert, Icons.Outlined.Settings)
    fun closeDetail() {
        detail = when {
            detail == "version" -> if (versionFromSoftware) "" else "versions"
            detail == "connection" && connectionFromVersions -> "versions"
            else -> ""
        }
        connectionFromVersions = false
    }
    fun newBackup() {
        backupPayload = SyncJson.encodeToString(BackupRule(UUID.randomUUID().toString(), "", "", settings.fileUploadPath, followUploadPath = true))
        detail = "backup"
    }
    fun selectApplication(selected: InstalledApp) {
        appPayload = SyncJson.encodeToString(apps.firstOrNull { it.packageName == selected.packageName } ?: AppRule(
            UUID.randomUUID().toString(), selected.label, selected.packageName, remoteChildPath(settings.softwareUploadPath, applicationFolderName(selected.label)),
            publish = !selected.isSplit, subscribe = selected.isSplit, followUploadPath = true, applicationName = selected.label,
        ))
        detail = "app"
    }
    SideEffect { activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    } } }
    LaunchedEffect(message) { if (message.isNotBlank()) { delay(7000); model.dismissMessage() } }
    BackHandler(enabled = detail.isNotEmpty()) { closeDetail() }
    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val title = when (detail) {
                "picker" -> "选择应用"
                "app" -> "软件规则"
                "backup" -> "备份规则"
                "connection" -> "OpenList 连接"
                "schedule" -> "设备与调度"
                "appearance" -> "外观"
                "file-access" -> "文件访问权限"
                "configuration-backup" -> "配置备份"
                "about" -> "关于"
                "versions" -> "版本对比"
                "files" -> "已备份文件"
                "backupRules" -> "文件备份规则"
                "version" -> "版本详情"
                else -> if (tab == 0) "多端同步" else labels[tab]
            }
            val headerAction: (() -> Unit)? = when {
                detail == "picker" -> ({ model.loadInstalledApps(true) })
                detail == "versions" || detail == "version" -> ({ model.loadRemoteVersions(force = true) })
                detail == "backupRules" -> ({ newBackup() })
                detail.isEmpty() && tab == 1 -> ({ detail = "picker" })
                detail.isEmpty() && tab == 2 -> ({ newBackup() })
                else -> null
            }
            Scaffold(
                topBar = { AppHeader(title, if (detail.isNotEmpty()) ({ closeDetail() }) else null,
                    if (detail in listOf("picker", "versions", "version")) Icons.Outlined.Refresh else Icons.Outlined.Add, headerAction, tab == 0 && detail.isEmpty()) },
                bottomBar = { if (!wide && detail.isEmpty() && !WindowInsets.isImeVisible) NavigationBar {
                    labels.forEachIndexed { index, label -> NavigationBarItem(tab == index, { tab = index }, icons[index], label) }
                } },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    if (wide && detail.isEmpty()) NavigationRail(Modifier.fillMaxHeight(), defaultWindowInsetsPadding = false) {
                        labels.forEachIndexed { index, label -> NavigationRailItem(tab == index, { tab = index }, icons[index], label) }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (message.isNotBlank()) UiCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), PaddingValues(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppText(message, Modifier.weight(1f), size = 12.sp, color = MiuixTheme.colorScheme.primary, maxLines = 3)
                                IconButton(onClick = model::dismissMessage) { Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(18.dp)) }
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            val key = when (detail) { "app" -> "app-${appDraft?.id}"; "backup" -> "backup-${backupDraft?.id}"; "version" -> "version-$comparedPackage"; "" -> "tab-$tab"; else -> detail }
                            pageState.SaveableStateProvider(key) {
                                when (detail) {
                                    "picker" -> InstalledAppPicker(model, ::selectApplication, ::closeDetail)
                                    "app" -> appDraft?.let { AppEditor(it, model, ::closeDetail) }
                                    "backup" -> backupDraft?.let { BackupEditor(it, model, ::closeDetail) }
                                    "connection" -> ConnectionEditor(settings, model, ::closeDetail)
                                    "schedule" -> ScheduleEditor(settings, model, ::closeDetail)
                                    "appearance" -> AppearancePage(settings, model)
                                    "file-access" -> FileAccessPage(model)
                                    "configuration-backup" -> ConfigurationBackupPage(model)
                                    "about" -> AboutPage(model)
                                    "versions" -> RemoteVersionsPage(model, { comparedPackage = it; versionFromSoftware = false; detail = "version" }, { connectionFromVersions = true; detail = "connection" })
                                    "version" -> RemoteVersionDetails(model, comparedPackage, { section -> recordsTab = section; tab = 3; detail = "" })
                                    "files" -> ArchivedFilesPage(model)
                                    "backupRules" -> BackupPage(backups, ::newBackup, { backupPayload = SyncJson.encodeToString(it); detail = "backup" })
                                    else -> when (tab) {
                                        0 -> OverviewPage(settings, apps.size, backups.size, logs, status, model::syncNow,
                                            { detail = "connection" }, { tab = 1; detail = "picker" }, { detail = "backupRules" })
                                        1 -> SoftwarePage(apps, model, { detail = "picker" }, { detail = "versions" },
                                            { comparedPackage = it; versionFromSoftware = true; detail = "version" },
                                            { section -> recordsTab = section; tab = 3; detail = "" }, { model.uploadLocalApk(it) })
                                        2 -> ArchivedFilesPage(model)
                                        3 -> TransfersPage(model, recordsTab) { recordsTab = it }
                                        4 -> SettingsHub(settings, root, model) { detail = it }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(title: String, back: (() -> Unit)?, actionIcon: ImageVector, action: (() -> Unit)?, brand: Boolean) {
    Column(Modifier.background(MiuixTheme.colorScheme.surface).windowInsetsPadding(
        WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (back == null) 16.dp else 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(22.dp)) }
            else if (brand) Image(painterResource(R.drawable.ic_sync), null, Modifier.size(28.dp))
            AppText(title, Modifier.weight(1f), size = 18.sp, weight = FontWeight.SemiBold, maxLines = 1)
            if (action != null) IconButton(onClick = action) { Icon(actionIcon, if (actionIcon == Icons.Outlined.Refresh) "刷新" else "添加规则", Modifier.size(23.dp)) }
        }
    }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun EditorLayout(label: String, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { PageColumn(content) }
        FooterAction(label, save)
    }
}

@Composable
private fun EmptyCard(title: String, description: String, icon: ImageVector, action: String? = null, onClick: () -> Unit = {}) {
    UiCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(28.dp), tint = MiuixTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText(title, weight = FontWeight.SemiBold)
                AppText(description, size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 3)
            }
        }
        if (action != null) { Spacer(Modifier.height(14.dp)); ActionButton(action, onClick, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun OverviewPage(settings: Settings, appCount: Int, backupCount: Int, logs: List<TaskLog>, status: String,
    sync: () -> Unit, connect: () -> Unit, addApp: () -> Unit, openBackupRules: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    PageColumn {
        UiCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Sync, null, Modifier.size(28.dp), tint = MiuixTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    AppText(if (settings.baseUrl.isBlank()) "连接存储，开始同步" else status, weight = FontWeight.SemiBold, maxLines = 2)
                    AppText(if (settings.baseUrl.isBlank()) "通过 OpenList 管理软件与备份" else "每 ${settings.intervalMinutes} 分钟检查", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Spacer(Modifier.height(14.dp))
            ActionButton(if (settings.baseUrl.isBlank()) "连接 OpenList" else "立即检查", if (settings.baseUrl.isBlank()) connect else sync, Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UiCard(Modifier.weight(1f), PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppText(appCount.toString(), size = 22.sp, weight = FontWeight.SemiBold)
                    AppText("软件规则", size = 12.sp)
                }
            }
            UiCard(Modifier.weight(1f), PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppText(backupCount.toString(), size = 22.sp, weight = FontWeight.SemiBold)
                    AppText("文件备份规则", size = 12.sp)
                }
            }
        }
        SectionLabel("快捷操作")
        UiCard(padding = PaddingValues(0.dp)) {
            MenuRow("添加软件", "从本机应用列表选择", Icons.Outlined.Apps, addApp)
            GroupDivider()
            MenuRow("文件备份规则", "归档软件生成的文件", Icons.Outlined.Folder, openBackupRules)
        }
        SectionLabel("最近任务")
        UiCard {
            if (logs.isEmpty()) AppText("暂无任务记录", size = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            logs.take(10).forEachIndexed { index, log ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        AppText(listOf(log.ruleName, log.stage).filter(String::isNotBlank).joinToString(" · "), size = 13.sp,
                            color = if (log.level == "error") Color(0xFFD95454) else MiuixTheme.colorScheme.onSurface, maxLines = 2)
                        if (log.message.isNotBlank()) AppText(log.message, size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
                    }
                    AppText(SimpleDateFormat("HH:mm", locale).format(Date(log.time)), size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        AppText("${settings.deviceName} · ${if (settings.unmeteredOnly) "仅不限流量网络" else "所有联网方式"}", Modifier.padding(start = 4.dp), size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
    }
}

@Composable
private fun SoftwarePage(rules: List<AppRule>, model: SyncViewModel, add: () -> Unit, compare: () -> Unit,
    viewVersion: (String) -> Unit, openRecords: (String) -> Unit, uploadLocal: (AppRule) -> Unit) {
    val state by model.remoteVersions.collectAsStateWithLifecycle()
    val installed by model.installedApps.collectAsStateWithLifecycle()
    val downloads by model.apkDownloads.collectAsStateWithLifecycle()
    val uploading by model.uploadingRules.collectAsStateWithLifecycle()
    val orderedRules = remember(rules, state.comparisons) {
        rules.sortedBy { rule ->
            val rel = state.comparisons.firstOrNull { it.local.packageName == rule.packageName }?.relation
            when (rel) {
                VersionRelation.REMOTE_NEWER -> 0
                VersionRelation.LOCAL_NEWER -> 1
                VersionRelation.SAME -> 2
                VersionRelation.NO_REMOTE -> 3
                null -> 4
            }
        }
    }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"), model::startApkDownload)
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(Unit) { model.observeApkDownloads(); model.loadInstalledApps(force = true); model.loadRemoteVersions() }
    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            UiCard(padding = PaddingValues(0.dp)) { MenuRow("版本对比", "查看本机与 OpenList 中的软件版本", Icons.AutoMirrored.Outlined.CompareArrows, compare) }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText(if (state.loading) "正在读取远端 ${state.completedDirectories}/${state.totalDirectories}"
                    else if (state.checkedAt > 0) "${formatTime(state.checkedAt, locale)} 检查" else "进入页面自动读取版本",
                    Modifier.weight(1f), size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
                ActionButton("刷新", { model.loadRemoteVersions(force = true) }, primary = false, enabled = !state.loading)
            }
        }
        if (state.error.isNotBlank()) item { UiCard { AppText(state.error, size = 12.sp, maxLines = 4) } }
        if (rules.isEmpty()) {
            item { EmptyCard("添加要同步的软件", "选择已安装应用，设置发布或订阅。", Icons.Outlined.Apps, "选择软件", add) }
        } else {
            item { SectionLabel("${rules.size} 个软件规则") }
            items(orderedRules, key = { it.id }) { rule ->
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp), cornerRadius = 18.dp) {
                    ApplicationIdentity(model, rule.packageName, rule.name, showVersion = false)
                    Spacer(Modifier.height(12.dp))
                    AppVersionSummary(state, state.comparisons.firstOrNull { it.local.packageName == rule.packageName },
                        installed.firstOrNull { it.packageName == rule.packageName })
                    Spacer(Modifier.height(10.dp))
                    val cmp = state.comparisons.firstOrNull { it.local.packageName == rule.packageName }
                    val latest = cmp?.latest
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("版本详情", { viewVersion(rule.packageName) }, Modifier.weight(1f), primary = false, compact = true)
                        when {
                            cmp?.relation == VersionRelation.LOCAL_NEWER ->
                                if (rule.packageName in uploading) ActionButton("上传中", { openRecords("upload") }, Modifier.weight(1f), compact = true)
                                else ActionButton("上传本地安装包", { uploadLocal(rule) }, Modifier.weight(1f), compact = true)
                            latest != null ->
                                if (downloads[rule.packageName]?.active == true) ActionButton("下载中", { openRecords("download") }, Modifier.weight(1f), compact = true)
                                else ActionButton("下载远端安装包", { model.requestApkDownload(latest) { name -> destination.launch(name) } }, Modifier.weight(1f), compact = true)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupPage(rules: List<BackupRule>, add: () -> Unit, edit: (BackupRule) -> Unit) {
    PageColumn {
        if (rules.isEmpty()) EmptyCard("添加备份目录", "选择软件的备份输出目录，自动归档与去重。", Icons.Outlined.Folder, "选择备份目录", add)
        else {
            SectionLabel("${rules.size} 个备份规则")
            rules.forEach { rule -> Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp), cornerRadius = 18.dp, onClick = { edit(rule) }) {
                AppText(rule.name, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                AppText(rule.remotePath, size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                AppText("${if (rule.enabled) "启用" else "暂停"} · ${if (rule.deleteAfterVerify) "校验后清理" else "保留本地"}", size = 12.sp, color = MiuixTheme.colorScheme.primary)
            } }
        }
    }
}

@Composable
private fun TransfersPage(model: SyncViewModel, recordsTab: String, onRecordsTab: (String) -> Unit) {
    val transfers by model.transfers.collectAsStateWithLifecycle()
    val transferState by model.transfer.collectAsStateWithLifecycle()
    val apkDownloads by model.apkDownloads.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val downloadRecords = remember(transfers) { transfers.filter { it.direction == "download" } }
    val uploadRecords = remember(transfers) { transfers.filter { it.direction == "upload" } }
    val activeDownloads = remember(apkDownloads) { apkDownloads.values.filter { it.active } }
    val activeUpload = transferState.takeIf { it.active && !it.manualDownload }
    val recordMsg by model.recordsMessage.collectAsStateWithLifecycle()
    val recordTab = recordsTab
    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (recordMsg.isNotBlank()) item {
            UiCard(padding = PaddingValues(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText(recordMsg, Modifier.weight(1f), size = 12.sp, color = MiuixTheme.colorScheme.primary, maxLines = 3)
                    IconButton(onClick = model::dismissRecordsMessage) { Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(18.dp)) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("下载 (${downloadRecords.size})", { onRecordsTab("download") }, Modifier.weight(1f), primary = recordTab == "download")
                ActionButton("上传 (${uploadRecords.size})", { onRecordsTab("upload") }, Modifier.weight(1f), primary = recordTab == "upload")
            }
        }
        if (recordTab == "download") {
            if (activeDownloads.isNotEmpty()) {
                item { SectionLabel("下载中（${activeDownloads.size}）") }
                items(activeDownloads, key = { it.packageName }) { d -> ActiveDownloadCard(d, locale) { model.cancelApkDownload(d.packageName) } }
            }
            item { SectionLabel("下载记录（${downloadRecords.size}）") }
            if (downloadRecords.isEmpty()) item { UiCard { AppText("暂无下载记录", size = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) } }
            else items(downloadRecords, key = { it.id }) { record -> TransferRow(record, locale, { model.installFromRecord(record) }) }
        } else {
            if (activeUpload != null) {
                item { SectionLabel("上传中") }
                item { TransferCard(activeUpload) }
            }
            item { SectionLabel("上传记录（${uploadRecords.size}）") }
            if (uploadRecords.isEmpty()) item { UiCard { AppText("暂无上传记录", size = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) } }
            else items(uploadRecords, key = { it.id }) { record -> TransferRow(record, locale, {}, { model.syncNow() }) }
        }
        item {
            Spacer(Modifier.height(4.dp))
            ActionButton("清空记录", model::clearTransfers, Modifier.fillMaxWidth(), primary = false, enabled = transfers.isNotEmpty())
        }
    }
}

@Composable
private fun ActiveDownloadCard(d: ApkDownloadState, locale: java.util.Locale, cancel: () -> Unit) {
    UiCard(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText(d.fileName.ifBlank { d.packageName }, Modifier.weight(1f), size = 13.sp, weight = FontWeight.Medium, maxLines = 2)
            AppText(if (d.progressTotal > 0) "下载中" else "等待中", size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (d.status.isNotBlank()) AppText(d.status, size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        val fraction = if (d.progressTotal > 0) (d.progressDone.toFloat() / d.progressTotal).coerceIn(0f, 1f) else null
        Box(Modifier.fillMaxWidth().height(5.dp).background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
            if (fraction != null) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MiuixTheme.colorScheme.primary))
        }
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText("${fmtBytes(d.progressDone, locale)} / ${if (d.progressTotal > 0) fmtBytes(d.progressTotal, locale) else "大小未知"}",
                Modifier.weight(1f), size = 11.sp, maxLines = 2)
            AppText(fraction?.let { "${(it * 100).toInt()}%" } ?: "传输中", size = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        ActionButton("取消下载", cancel, Modifier.fillMaxWidth(), primary = false)
    }
}

private fun fmtBytes(bytes: Long, locale: java.util.Locale): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var amount = bytes.toDouble(); var index = 0
    while (amount >= 1024 && index < units.lastIndex) { amount /= 1024; index++ }
    return if (index == 0) "$bytes B" else String.format(locale, "%.1f", amount) + " " + units[index]
}

@Composable
private fun TransferRow(t: TransferRecord, locale: java.util.Locale, onClick: () -> Unit, onRetry: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp), cornerRadius = 16.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (t.direction == "upload") Icons.Outlined.CloudUpload else Icons.Outlined.CloudDownload, null, Modifier.size(24.dp),
                tint = if (t.status == "failed") Color(0xFFD95454) else MiuixTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                AppText(t.name.ifBlank { t.packageName }, weight = FontWeight.Medium, maxLines = 2)
                val meta = listOfNotNull(
                    t.versionName.takeIf { it.isNotBlank() },
                    if (t.size > 0) fmtBytes(t.size, locale) else null,
                    SimpleDateFormat("MM-dd HH:mm", locale).format(Date(t.time)),
                    if (t.status == "failed") "失败" else "成功",
                ).joinToString(" · ")
                AppText(meta, size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
            }
            if (t.status == "failed" && onRetry != null) {
                ActionButton("重试", onRetry, compact = true)
            }
        }
    }
}

@Composable
private fun ArchivedFilesPage(model: SyncViewModel) {
    val state by model.archivedFiles.collectAsStateWithLifecycle()
    val fileDownloads by model.fileDownloads.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    var folder by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { model.loadArchivedFiles() }
    fun relOf(f: ArchivedFile): String = f.record.relativePath.ifBlank { f.record.originalName }
    fun dirOf(rel: String): String = rel.substringBeforeLast('/', "")
    fun nameOf(rel: String): String = rel.substringAfterLast('/')
    val prefix = if (folder.isBlank()) "" else folder + "/"
    val filesHere = state.files.filter { dirOf(relOf(it)) == folder }
    val subFolders = state.files.mapNotNull { f ->
        val d = dirOf(relOf(f))
        when {
            d.isBlank() -> null
            folder.isBlank() -> d.substringBefore('/').ifBlank { null }
            d.startsWith(prefix) -> d.removePrefix(prefix).substringBefore('/').ifBlank { null }
            else -> null
        }
    }.distinct().sorted()
    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText(
                    if (state.loading) "正在读取远端备份文件…"
                    else "共 ${state.files.size} 个文件 · ${folder.ifBlank { "根目录" }}",
                    Modifier.weight(1f), size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
                ActionButton("刷新", { model.loadArchivedFiles(force = true) }, primary = false, enabled = !state.loading)
            }
        }
        if (folder.isNotBlank()) item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp), cornerRadius = 18.dp,
                onClick = { folder = folder.substringBeforeLast('/', "") }) {
                AppText("⬆ 返回上级", weight = FontWeight.Medium, color = MiuixTheme.colorScheme.primary)
            }
        }
        if (state.error.isNotBlank()) item { UiCard { AppText(state.error, size = 12.sp, maxLines = 4) } }
        items(subFolders, key = { "folder:" + folder + "/" + it }) { name ->
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp), cornerRadius = 18.dp,
                onClick = { folder = if (folder.isBlank()) name else folder + "/" + name }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AppText(name, weight = FontWeight.Medium, maxLines = 2)
                        AppText("文件夹", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        items(filesHere, key = { it.directory + "|" + it.record.relativePath + "|" + it.record.sha256 + "|" + it.record.archivedAt }) { f ->
            val rel = relOf(f)
            val key = f.record.sha256 + "|" + f.record.relativePath + "|" + f.record.originalName
            val dl = fileDownloads[key]
            UiCard(padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        AppText(nameOf(rel).ifBlank { "未命名文件" }, weight = FontWeight.Medium, maxLines = 2)
                        val deviceLabel = if (f.record.deviceId == settings.deviceId) settings.deviceName.ifBlank { "本机" } else "其他设备"
                        val line1 = listOfNotNull(
                            if (f.record.size > 0) fmtBytes(f.record.size, locale) else null,
                            deviceLabel,
                        ).joinToString(" · ")
                        AppText(line1, size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
                        AppText(SimpleDateFormat("yyyy-MM-dd HH:mm", locale).format(Date(f.record.archivedAt)), size = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
                        if (dl?.status == "下载失败" && dl.error.isNotBlank()) AppText(dl.error, size = 11.sp, color = Color(0xFFD95454), maxLines = 2)
                    }
                    val label = when {
                        dl?.active == true -> if (dl.total > 0) "${(dl.done * 100 / dl.total)}%" else "下载中"
                        dl?.status == "已下载" -> "已下载"
                        else -> "下载"
                    }
                    ActionButton(label, { model.downloadArchivedFile(f) }, compact = true, primary = !(dl?.active == true))
                }
            }
        }
        if (!state.loading && state.error.isBlank() && filesHere.isEmpty() && subFolders.isEmpty()) {
            item { UiCard { AppText(if (folder.isBlank()) "暂无已备份文件" else "此文件夹为空", size = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) } }
        }
    }
}

@Composable
private fun AppEditor(initial: AppRule, model: SyncViewModel, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var remote by rememberSaveable { mutableStateOf(initial.remotePath) }
    var followPath by rememberSaveable { mutableStateOf(initial.followUploadPath) }
    var publish by rememberSaveable { mutableStateOf(initial.publish) }
    var subscribe by rememberSaveable { mutableStateOf(initial.subscribe) }
    var install by rememberSaveable { mutableStateOf(initial.autoInstall) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var export by rememberSaveable { mutableStateOf(initial.exportTreeUri) }
    var cleanup by rememberSaveable { mutableStateOf(initial.deleteExportAfterVerify) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val apps by model.installedApps.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val saved by model.rules.collectAsStateWithLifecycle()
    val split = apps.firstOrNull { it.packageName == initial.packageName }?.isSplit == true
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            export = uri.toString()
        } catch (_: Exception) { model.notify("目录授权失败，请选择可读写目录") }
    }
    val label = apps.firstOrNull { it.packageName == initial.packageName }?.label ?: initial.applicationName.ifBlank { initial.name }
    val destination = appUploadPath(initial.copy(remotePath = remote, followUploadPath = followPath, applicationName = label), settings.softwareUploadPath)
    EditorLayout("保存规则", { model.saveApp(initial.copy(name = name, remotePath = destination, followUploadPath = followPath, publish = publish && !split,
        subscribe = subscribe, autoInstall = install, enabled = enabled, exportTreeUri = export, deleteExportAfterVerify = cleanup), close) }) {
        UiCard(padding = PaddingValues(14.dp)) { ApplicationIdentity(model, initial.packageName, initial.name) }
        UiCard { if (followPath) {
            AppText("远端目录", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            AppText(destination, maxLines = 3)
            AppText("跟随 OpenList 软件上传目录", size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else EditField("远端目录", destination, { remote = it.replace('\n', ' ') }, multiline = true) }
        SectionLabel("同步方式")
        UiCard(padding = PaddingValues(0.dp)) {
            ToggleRow("发布本机版本", publish && !split, { publish = it }, enabled = !split)
            GroupDivider()
            ToggleRow("订阅远端更新", subscribe, { subscribe = it })
            GroupDivider()
            ToggleRow("自动安装更新", install, { install = it }, "需要 Root 授权", subscribe)
        }
        if (split) AppText("分包应用可订阅兼容的通用安装包。", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        UiCard(padding = PaddingValues(0.dp)) {
            MenuRow(if (advanced) "收起更多选项" else "更多选项", "名称、导出目录与本地清理", onClick = { advanced = !advanced })
            if (advanced) {
                GroupDivider()
                ToggleRow("跟随配置上传目录", followPath, { if (!it) remote = destination; followPath = it })
                Column(Modifier.padding(16.dp)) { EditField("规则名称", name, { name = it }) }
                ToggleRow("启用规则", enabled, { enabled = it })
                MenuRow("APK 导出目录", if (export.isBlank()) "默认（Download/MultiSync）" else folderLabel(export), Icons.Outlined.Folder) { picker.launch(null) }
                ToggleRow("校验后清理导出副本", cleanup, { cleanup = it }, "已安装软件不受影响")
            }
        }
        if (saved.any { it.id == initial.id }) ActionButton("移除此规则", { model.deleteRule(initial.id); close() }, Modifier.fillMaxWidth(), primary = false)
    }
}

@Composable
private fun BackupEditor(initial: BackupRule, model: SyncViewModel, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var source by rememberSaveable { mutableStateOf(initial.sourceTreeUri) }
    var remote by rememberSaveable { mutableStateOf(initial.remotePath) }
    var followPath by rememberSaveable { mutableStateOf(initial.followUploadPath) }
    var stable by rememberSaveable { mutableStateOf(initial.stableSeconds.toString()) }
    var cleanup by rememberSaveable { mutableStateOf(initial.deleteAfterVerify) }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var recursive by rememberSaveable { mutableStateOf(initial.recursive) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var chooseDevice by rememberSaveable { mutableStateOf(false) }
    val saved by model.rules.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun selected(uri: String) { model.checkBackupDirectory(uri, cleanup) { info -> source = uri; if (name.isBlank()) name = info.name } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri -> try {
            val granted = result.data!!.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(granted and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "未获得目录读取权限，请重新授权" }
            val persistentFlags = if (granted and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, persistentFlags)
            selected(uri.toString())
        } catch (exception: Exception) { model.notify(exception.message ?: "长期目录授权失败，请选择设备目录或系统文件管理器") } }
    }
    fun systemPicker() = picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
    val destination = if (followPath) settings.fileUploadPath else remote
    EditorLayout("保存规则", { model.saveBackup(initial.copy(name = name, sourceTreeUri = source, remotePath = destination, followUploadPath = followPath,
        stableSeconds = stable.toLongOrNull() ?: 0, deleteAfterVerify = cleanup, enabled = enabled, recursive = recursive), close) }) {
        UiCard(padding = PaddingValues(0.dp)) {
            MenuRow("本地备份目录", if (source.isBlank()) "选择设备目录或系统授权目录" else folderLabel(source), Icons.Outlined.Folder) { chooseDevice = true }
            if (source.isNotBlank()) { GroupDivider(); MenuRow("检查目录读取权限", "验证目录与文件是否可读", Icons.Outlined.Security) { selected(source) } }
        }
        UiCard { if (followPath) {
            AppText("远端目录", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            AppText(destination, maxLines = 3)
            AppText("跟随 OpenList 文件上传目录", size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else EditField("远端目录", remote, { remote = it.replace('\n', ' ') }, multiline = true) }
        UiCard(padding = PaddingValues(0.dp)) { ToggleRow("校验后清理本地", cleanup, { cleanup = it }, "新文件和重复文件均先校验远端") }
        UiCard(padding = PaddingValues(0.dp)) {
            MenuRow(if (advanced) "收起扫描选项" else "扫描选项", "名称、子目录与稳定等待", onClick = { advanced = !advanced })
            if (advanced) {
                GroupDivider()
                ToggleRow("跟随配置上传目录", followPath, { if (!it) remote = destination; followPath = it })
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    EditField("规则名称", name, { name = it })
                    EditField("稳定等待（秒）", stable, { stable = it }, "至少 60 秒", KeyboardType.Number)
                }
                ToggleRow("启用规则", enabled, { enabled = it })
                ToggleRow("包含子目录", recursive, { recursive = it })
            }
        }
        if (saved.any { it.id == initial.id }) ActionButton("移除此规则", { model.deleteRule(initial.id); close() }, Modifier.fillMaxWidth(), primary = false)
    }
    if (chooseDevice) LocalDirectoryPicker(model, source, { uri -> chooseDevice = false; selected(uri) }, ::systemPicker, { chooseDevice = false })
}

@Composable
private fun SettingsHub(settings: Settings, root: String, model: SyncViewModel, open: (String) -> Unit) {
    val fileAccess by model.fileAccess.collectAsStateWithLifecycle()
    val localNetwork by model.localNetworkAccess.collectAsStateWithLifecycle()
    val localPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refreshLocalNetworkAccess() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> model.notify(if (granted) "通知已启用" else "可在应用内查看任务状态") }
    PageColumn {
        SectionLabel("同步服务")
        UiCard(padding = PaddingValues(0.dp)) {
            MenuRow("OpenList 连接", if (settings.baseUrl.isBlank()) "尚未配置" else "已配置", Icons.Outlined.Cloud) { open("connection") }
            GroupDivider()
            MenuRow("设备与调度", "${settings.deviceName} · 每 ${settings.intervalMinutes} 分钟", Icons.Outlined.Schedule) { open("schedule") }
            GroupDivider()
            MenuRow("配置备份", "导出备份、导入恢复", Icons.Outlined.SettingsBackupRestore) { open("configuration-backup") }
        }
        SectionLabel("权限")
        UiCard(padding = PaddingValues(0.dp)) {
            MenuRow("文件访问权限", if (fileAccess) "设备存储已授权" else "可使用系统授权目录", Icons.Outlined.Folder) { open("file-access") }
            if (Build.VERSION.SDK_INT >= 37) {
                GroupDivider()
                MenuRow("局域网访问", if (localNetwork) "已授权，可使用局域网与 SSH 通道" else "局域网或 SSH 通道需要此权限", Icons.Outlined.Wifi) {
                    localPermission.launch(dev.multisync.app.data.LOCAL_NETWORK_PERMISSION)
                }
            }
            GroupDivider()
            MenuRow("Root 授权", root, Icons.Outlined.Security) { model.checkRoot() }
            if (Build.VERSION.SDK_INT >= 33) { GroupDivider(); MenuRow("任务通知", "查看后台同步状态", Icons.Outlined.Notifications) { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) } }
        }
        SectionLabel("诊断")
        UiCard(padding = PaddingValues(0.dp)) {
            MenuRow("导出日志", "任务日志导出到 Download/MultiSync", Icons.Outlined.Description) { model.exportLogs() }
        }
        SectionLabel("应用")
        UiCard(padding = PaddingValues(0.dp)) {
            MenuRow("外观", when (settings.theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }, Icons.Outlined.Palette) { open("appearance") }
            GroupDivider()
            MenuRow("关于", "多端同步 0.1.11", Icons.Outlined.Info) { open("about") }
        }
    }
}

@Composable
private fun ConnectionEditor(initial: Settings, model: SyncViewModel, close: () -> Unit) {
    var url by rememberSaveable(initial.baseUrl) { mutableStateOf(initial.baseUrl) }
    var username by rememberSaveable(initial.username) { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf("") }
    var http by rememberSaveable(initial.allowHttp) { mutableStateOf(initial.allowHttp) }
    var softwarePath by rememberSaveable(initial.softwareUploadPath) { mutableStateOf(initial.softwareUploadPath) }
    var filePath by rememberSaveable(initial.fileUploadPath) { mutableStateOf(initial.fileUploadPath) }
    var choosing by rememberSaveable { mutableStateOf("") }
    var ssh by rememberSaveable(initial.sshEnabled) { mutableStateOf(initial.sshEnabled) }
    var tunnelHelp by rememberSaveable { mutableStateOf(false) }
    val tunnelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importUploadTunnel(uri, url) { ssh = true }
    }
    fun draft() = initial.copy(baseUrl = url, username = username, allowHttp = http, softwareUploadPath = softwarePath, fileUploadPath = filePath, sshEnabled = ssh)
    EditorLayout("保存连接", { model.saveSettings(draft(), password) { password = ""; close() } }) {
        UiCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                EditField("WebDAV 地址", url, { url = it }, "例如 https://example.com/dav/", KeyboardType.Uri)
                EditField("用户名", username, { username = it })
                EditField("密码", password, { password = it }, if (initial.hasPassword) "已保存密码，留空保持" else "", KeyboardType.Password, password = true)
            }
        }
        UiCard(padding = PaddingValues(0.dp)) { ToggleRow("允许 HTTP", http, { http = it }, "默认使用 HTTPS") }
        SectionLabel("上传通道")
        UiCard {
            ToggleRow("使用加密上传通道", ssh, { ssh = it }, if (initial.hasSshTunnel) "已导入，可用于上传与校验" else "导入受限 SSH 上传通道（仅端口转发）")
            Spacer(Modifier.height(10.dp))
            ActionButton("导入上传通道", { tunnelPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, Modifier.fillMaxWidth(), primary = false)
            if (initial.hasSshTunnel) {
                Spacer(Modifier.height(8.dp))
                ActionButton("测试上传通道", { model.testUploadTunnel(draft(), password) }, Modifier.fillMaxWidth(), primary = false)
            }
            GroupDivider()
            MenuRow(if (tunnelHelp) "收起加密通道说明" else "加密通道：用途与获取方式", "为什么用、怎么获取 SSH 通道", Icons.Outlined.HelpOutline) { tunnelHelp = !tunnelHelp }
            if (tunnelHelp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText("用途", weight = FontWeight.Medium, size = 13.sp)
                    AppText("开启后，上传、下载和版本对比都会通过 SSH 只转发到本机 OpenList 的 127.0.0.1:5244，绕过 Cloudflare 或反向代理的请求限流（避免 HTTP 429），并降低公网延迟。该私钥仅用于端口转发，不能登录或执行命令；App 使用 Android Keystore 加密保存，并固定校验服务器主机密钥。通道绑定 OpenList 地址，换站点需重新导入。", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(4.dp))
                    AppText("获取 SSH 通道（通用，自行创建）", weight = FontWeight.Medium, size = 13.sp)
                    AppText("它就是一个“受限 SSH 端口转发通道”，可在任何运行 OpenList 的 Linux 服务器上自行创建，不依赖其他服务。所需字段：host、port、username、host_key、private_key、openlist_urls。", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    AppText("前提：SSH 登录的就是运行 OpenList 的那台机器；OpenList 监听 127.0.0.1:5244 并提供 /dav；服务器允许 TCP 转发（sshd 默认开启）。", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    AppText("1) 建只做转发的账号：sudo useradd -m -s /usr/sbin/nologin multisync", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    AppText("2) 生成专用密钥：ssh-keygen -t ed25519 -f multisync_tunnel -N \"\"", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    AppText("3) 把 multisync_tunnel.pub 写入 /home/multisync/.ssh/authorized_keys，并在行首加限制：no-pty,no-X11-forwarding,no-agent-forwarding,no-user-rc,permitopen=\"127.0.0.1:5244\"", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    AppText("4) 取 host_key：cat /etc/ssh/ssh_host_ed25519_key.pub，取 ssh-ed25519 之后那段 AAAA…", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    AppText("5) 组装 JSON 并存成 .json：{ \"host\":\"服务器地址\", \"port\":22, \"username\":\"multisync\", \"host_key\":\"AAAA…\", \"private_key\":\"第2步生成的私钥内容\", \"openlist_urls\":[\"https://你的 OpenList 域名\"] }", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    AppText("6) 在此页「导入上传通道」选择该 .json → 打开「使用加密上传通道」→「测试上传通道」核验。openlist_urls 需与 WebDAV 地址的源站一致。", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        SectionLabel("上传与版本对比来源")
        UiCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                UploadDirectoryField("软件上传目录", softwarePath, { softwarePath = it }, { choosing = "software" })
                UploadDirectoryField("文件上传目录", filePath, { filePath = it }, { choosing = "files" })
                AppText("软件按应用名称创建子目录。版本对比同时读取这两个位置的发布记录。", size = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 3)
            }
        }
        ActionButton("测试连接", { model.testConnection(draft(), password) }, Modifier.fillMaxWidth(), primary = false)
    }
    if (choosing.isNotEmpty()) RemoteDirectoryPicker(model, draft(), password,
        if (choosing == "software") softwarePath else filePath,
        if (choosing == "software") "选择软件目录" else "选择文件目录",
        { path -> if (choosing == "software") softwarePath = path else filePath = path; choosing = "" }, { choosing = "" })
}

@Composable
private fun UploadDirectoryField(label: String, value: String, change: (String) -> Unit, pick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { EditField(label, value, { change(it.replace('\n', ' ')) }) }
        IconButton(onClick = pick) { Icon(Icons.Outlined.Folder, "选择$label", Modifier.size(24.dp)) }
    }
}

@Composable
private fun ScheduleEditor(initial: Settings, model: SyncViewModel, close: () -> Unit) {
    var name by rememberSaveable(initial.deviceName) { mutableStateOf(initial.deviceName) }
    var interval by rememberSaveable(initial.intervalMinutes) { mutableStateOf(initial.intervalMinutes.toString()) }
    var unmetered by rememberSaveable(initial.unmeteredOnly) { mutableStateOf(initial.unmeteredOnly) }
    EditorLayout("保存设置", { model.savePreferences(initial.copy(deviceName = name, intervalMinutes = interval.toLongOrNull() ?: 0, unmeteredOnly = unmetered), close) }) {
        UiCard { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EditField("本机名称", name, { name = it })
            EditField("检查周期（分钟）", interval, { interval = it }, "至少 15 分钟，系统休眠时可能延迟", KeyboardType.Number)
        } }
        UiCard(padding = PaddingValues(0.dp)) { ToggleRow("仅不限流量网络", unmetered, { unmetered = it }, "按系统对网络是否计费的判断执行") }
    }
}

@Composable
private fun AppearancePage(settings: Settings, model: SyncViewModel) {
    PageColumn {
        SectionLabel("主题")
        UiCard(padding = PaddingValues(0.dp)) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEachIndexed { index, (value, label) ->
                if (index > 0) GroupDivider()
                Row(Modifier.fillMaxWidth().selectable(settings.theme == value, role = Role.RadioButton,
                    onClick = { model.setTheme(value) }).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AppText(label, Modifier.weight(1f), weight = FontWeight.Medium)
                    if (settings.theme == value) Icon(Icons.Outlined.Check, "已选择", Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                }
            }
        }
        AppText("字体大小跟随系统设置。", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun AboutPage(model: SyncViewModel) {
    var licenses by rememberSaveable { mutableStateOf(false) }
    val resources = LocalResources.current
    val context = LocalContext.current
    val update by model.appUpdate.collectAsStateWithLifecycle()
    PageColumn {
        UiCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(painterResource(R.drawable.ic_sync), null, Modifier.size(44.dp))
                Column { AppText("多端同步", weight = FontWeight.SemiBold); AppText("0.1.11 · Kotlin / Miuix", size = 12.sp) }
            }
            Spacer(Modifier.height(14.dp))
            AppText("支持单 APK 同步与共享目录备份。分包安装和自动恢复尚未开放。", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        UiCard(padding = PaddingValues(0.dp)) {
            MenuRow("检查更新", when {
                update.checking -> "正在检查…"
                update.error.isNotBlank() -> update.error
                update.upToDate -> "已是最新版本 ${update.latest}"
                update.latest.isNotBlank() -> "发现新版本 ${update.latest}"
                else -> "通过 GitHub 检查新版本"
            }, Icons.Outlined.SystemUpdate) { model.checkAppUpdate() }
            if (update.url.isNotBlank()) {
                GroupDivider()
                Column(Modifier.padding(16.dp)) {
                    ActionButton("下载并安装 ${update.latest}", model::downloadAndInstallUpdate, Modifier.fillMaxWidth())
                }
            }
        }
        UiCard(padding = PaddingValues(0.dp)) {
            MenuRow("项目主页", "https://github.com/A-LIGE-Z/MultiSync", Icons.Outlined.Link) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/A-LIGE-Z/MultiSync"))) }
            }
        }
        UiCard(padding = PaddingValues(0.dp)) { MenuRow("开源许可", "Miuix、Kotlin、AndroidX、OkHttp", onClick = { licenses = !licenses }) }
        if (licenses) UiCard { AppText(remember(resources) { resources.openRawResource(R.raw.open_source_licenses).bufferedReader().use { it.readText() } }, size = 11.sp) }
    }
}

private fun folderLabel(uri: String): String = Uri.parse(uri).lastPathSegment.orEmpty().substringAfter(':').ifBlank { "已选择目录" }

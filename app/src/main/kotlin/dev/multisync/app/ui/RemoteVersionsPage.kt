package dev.multisync.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.multisync.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RemoteVersionsPage(model: SyncViewModel, openVersion: (String) -> Unit, connect: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val state by model.remoteVersions.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var onlyRemote by rememberSaveable { mutableStateOf(true) }
    val keyboard = LocalSoftwareKeyboardController.current
    val matches = remember(state.comparisons, query, onlyRemote) {
        state.comparisons.filter { (!onlyRemote || it.latest != null) &&
            (it.local.label.contains(query.trim(), ignoreCase = true) || it.local.packageName.contains(query.trim(), ignoreCase = true)) }
    }
    LaunchedEffect(Unit) { model.loadRemoteVersions() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(query, { query = it }, label = "搜索名称或包名", useLabelAsPlaceholder = true, singleLine = true,
                    insideMargin = DpSize(12.dp, 10.dp), cornerRadius = 14.dp,
                    textStyle = MiuixTheme.textStyles.main.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth())
                UiCard(padding = PaddingValues(0.dp)) {
                    MenuRow("配置来源目录", "在 OpenList 配置中修改", onClick = connect)
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppText("软件：${settings.softwareUploadPath}", size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
                        AppText("版本记录来自软件上传目录", size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppText(if (state.loading) "读取中 ${state.completedDirectories}/${state.totalDirectories}" else "${matches.size} 个应用",
                        Modifier.weight(1f), size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    AppText("仅远端已有", size = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Switch(onlyRemote, { onlyRemote = it })
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.loading) item { UiCard { AppText("正在读取 OpenList 发布记录…", size = 13.sp) } }
                if (state.error.isNotBlank()) item {
                    UiCard {
                        AppText(state.error, size = 13.sp, maxLines = 4)
                        Spacer(Modifier.height(12.dp))
                        ActionButton(if (settings.baseUrl.isBlank()) "配置 OpenList" else "重新读取",
                            if (settings.baseUrl.isBlank()) connect else ({ model.loadRemoteVersions(force = true) }), Modifier.fillMaxWidth())
                    }
                }
                if (state.problems.isNotEmpty()) item {
                    UiCard {
                        AppText("有 ${state.problems.size} 项记录或目录未能读取", weight = FontWeight.Medium)
                        AppText("下方显示已成功读取的版本，未找到的软件仍需重新检查。", size = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        state.problems.take(3).forEach { problem ->
                            Spacer(Modifier.height(6.dp))
                            AppText("${problem.path}：${problem.message}", size = 11.sp, maxLines = 3)
                        }
                    }
                }
                if (state.loaded && matches.isEmpty()) item {
                    UiCard { AppText(when {
                        query.isNotBlank() -> "没有匹配的应用"
                        state.problems.isNotEmpty() -> "已读取的记录中暂未找到本机软件，请检查目录范围或刷新。"
                        onlyRemote -> "远端暂无匹配的本机软件，请检查软件目录。"
                        else -> "未读取到本机应用，请允许获取已安装应用信息后刷新。"
                    }, size = 13.sp, maxLines = 4) }
                }
                items(matches, key = { it.local.packageName }) { comparison ->
                    Card(Modifier.fillMaxWidth(), cornerRadius = 16.dp, insideMargin = PaddingValues(12.dp),
                        onClick = { keyboard?.hide(); openVersion(comparison.local.packageName) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ApplicationIcon(model, comparison.local.packageName, comparison.local.label, 36.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                AppText(comparison.local.label, weight = FontWeight.Medium, maxLines = 1)
                                AppText(comparison.local.packageName, size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
                            }
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                AppText("本机", size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                AppText(versionLabel(comparison.local.versionName, comparison.local.versionCode), size = 12.sp, maxLines = 2)
                            }
                            Column(Modifier.weight(1f)) {
                                AppText("远端最新", size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                AppText(comparison.latest?.release?.let { versionLabel(it.versionName, it.versionCode) } ?: "未找到", size = 12.sp, maxLines = 2)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        AppText(comparisonStatus(comparison, state.problems.isNotEmpty()), size = 12.sp,
                            color = if (comparison.compatibleUpdate != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
                    }
                }
                if (state.loaded) item { AppText("${formatTime(state.checkedAt, locale)} 检查 · ${state.totalDirectories} 个软件目录", size = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            }
        }
    }
}

@Composable
internal fun RemoteVersionDetails(model: SyncViewModel, packageName: String, openRecords: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val state by model.remoteVersions.collectAsStateWithLifecycle()
    val downloads by model.apkDownloads.collectAsStateWithLifecycle()
    val dl = downloads[packageName]
    val downloading = dl?.active == true
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"), model::startApkDownload)
    fun requestDownload(record: CatalogRelease) { if (downloading) openRecords() else model.requestApkDownload(record) { name -> destination.launch(name) } }
    val comparison = state.comparisons.firstOrNull { it.local.packageName == packageName }
    val entities by model.rules.collectAsStateWithLifecycle()
    val uploading by model.uploadingRules.collectAsStateWithLifecycle()
    val rule = remember(entities, packageName) {
        entities.filter { it.kind == "app" }.mapNotNull { runCatching { SyncJson.decodeFromString<AppRule>(it.payload) }.getOrNull() }
            .firstOrNull { it.packageName == packageName }
    }
    val uploadingThis = rule != null && rule.packageName in uploading
    LaunchedEffect(Unit) { model.observeApkDownloads(); model.loadRemoteVersions() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (dl != null && dl.status.isNotBlank()) item {
                UiCard {
                    AppText(dl.status, weight = FontWeight.Medium, color = MiuixTheme.colorScheme.primary, maxLines = 2)
                    if (dl.fileName.isNotBlank()) AppText(dl.fileName, size = 12.sp, maxLines = 3)
                    if (dl.error.isNotBlank()) AppText(dl.error, size = 12.sp, maxLines = 4)
                    if (dl.install.isNotBlank()) AppText(dl.install, size = 12.sp, color = MiuixTheme.colorScheme.primary, maxLines = 3)
                    if (dl.savedUri.isNotBlank()) AppText(
                        if (dl.savedUri.startsWith("file://")) "已保存到 Download/MultiSync/apk，可在文件管理器中打开安装。"
                        else "已保存到所选位置，可在文件管理器中打开安装。", size = 12.sp, maxLines = 3)
                    if (dl.active) {
                        Spacer(Modifier.height(10.dp))
                        ActionButton("取消下载", { model.cancelApkDownload(packageName) }, Modifier.fillMaxWidth(), primary = false)
                    }
                }
            }
            if (comparison == null) item { UiCard { AppText(if (state.loading) "正在读取版本…" else state.error.ifBlank { "未找到此软件，请返回列表刷新" }) } }
            if (comparison != null) {
                item { UiCard {
                    ApplicationIdentity(model, comparison.local.packageName, comparison.local.label)
                    Spacer(Modifier.height(12.dp))
                    VersionValue("本机版本", versionLabel(comparison.local.versionName, comparison.local.versionCode))
                    VersionValue("远端最新", comparison.latest?.release?.let { versionLabel(it.versionName, it.versionCode) } ?: "未找到")
                    comparison.compatibleUpdate?.takeIf { it != comparison.latest }?.let { update ->
                        VersionValue("兼容更新", update.release.let { versionLabel(it.versionName, it.versionCode) })
                    }
                    Spacer(Modifier.height(8.dp))
                    AppText(comparisonStatus(comparison, state.problems.isNotEmpty()), color = MiuixTheme.colorScheme.primary, maxLines = 3)
                    if (comparison.identityError.isNotBlank()) AppText(comparison.identityError, size = 12.sp, maxLines = 2)
                    comparison.latest?.let { latest ->
                        Spacer(Modifier.height(12.dp))
                        if (comparison.relation == VersionRelation.LOCAL_NEWER && rule != null) {
                            ActionButton(
                                if (uploadingThis) "上传中，前往记录查看" else "上传本地安装包",
                                { if (uploadingThis) openRecords() else rule.let { model.uploadLocalApk(it) } },
                                Modifier.fillMaxWidth(), primary = !uploadingThis)
                        } else {
                            ActionButton(if (downloading) "下载中，前往下载记录查看" else "下载最新安装包", { requestDownload(latest) }, Modifier.fillMaxWidth(), primary = !downloading)
                        }
                    }
                    comparison.compatibleUpdate?.takeIf { it != comparison.latest }?.let { compatible ->
                        Spacer(Modifier.height(8.dp))
                        ActionButton(if (downloading) "下载中，前往下载记录查看" else "下载兼容更新", { requestDownload(compatible) }, Modifier.fillMaxWidth(), primary = false)
                    }
                } }
                if (comparison.latest == null) item { UiCard { AppText(if (state.problems.isEmpty()) "此软件暂无远端发布记录。" else "部分远端记录读取失败，请返回列表重新读取。", size = 13.sp) } }
                if (comparison.versions.isNotEmpty()) {
                    item { SectionLabel("远端历史 · ${comparison.versions.size} 条发布记录") }
                    items(comparison.versions.withIndex().toList(), key = { it.index }) { indexed ->
                        val record = indexed.value
                        val release = record.release
                        UiCard {
                            AppText(versionLabel(release.versionName, release.versionCode), weight = FontWeight.SemiBold, maxLines = 2)
                            Spacer(Modifier.height(6.dp))
                            val compatibility = compatibilityMessage(release, comparison)
                            AppText(compatibility, size = 12.sp, color = if (compatibility == "签名一致，设备兼容") MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 3)
                            Spacer(Modifier.height(10.dp))
                            VersionValue("发布时间", formatTime(release.publishedAt, locale))
                            VersionValue("发布设备", release.publisherId)
                            VersionValue("安装包大小", "${String.format(locale, "%.1f", release.size / 1048576.0)} MB")
                            VersionValue("软件目录", record.directory)
                            VersionValue("安装包文件", release.apkPath.substringAfterLast('/'))
                            VersionValue("SHA-256", release.sha256)
                            Spacer(Modifier.height(12.dp))
                            ActionButton(if (downloading) "下载中，前往下载记录查看" else "下载此版本", { requestDownload(record) }, Modifier.fillMaxWidth(), primary = false)
                        }
                    }
                    item { AppText("版本按软件内部版本号排序。信息来自发布记录，安装前会再次校验实际 APK。", size = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                }
            }
        }
    }
}

@Composable
private fun VersionValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppText(label, Modifier.width(84.dp), size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        AppText(value, Modifier.weight(1f), size = 12.sp, maxLines = 4)
    }
}

internal fun versionLabel(name: String, code: Long) = if (name.isBlank()) code.toString() else "$name ($code)"
internal fun formatTime(time: Long, locale: Locale) = SimpleDateFormat("yyyy-MM-dd HH:mm", locale).format(Date(time))

internal fun comparisonStatus(comparison: AppVersionComparison, incomplete: Boolean): String = when {
    comparison.relation == VersionRelation.NO_REMOTE -> if (incomplete) "已读取记录中未找到" else "远端暂无版本"
    comparison.identityError.isNotBlank() -> "版本已读取，兼容性未确认"
    comparison.compatibleUpdate != null && !comparison.latestCompatible -> "有兼容更新，远端最新版本不兼容"
    comparison.compatibleUpdate != null -> "远端版本较新，有可用更新"
    comparison.relation == VersionRelation.REMOTE_NEWER -> "远端版本较新，签名或设备不兼容"
    comparison.relation == VersionRelation.SAME && !comparison.latestCompatible -> "版本号相同，签名或设备不兼容"
    comparison.relation == VersionRelation.SAME -> "版本相同"
    else -> "本机版本较新"
}

internal fun remoteVersionStatus(state: RemoteVersionsState, comparison: AppVersionComparison?): String = when {
    state.loading -> "正在更新版本对比…"
    state.error.isNotBlank() -> if (state.checkedAt > 0) "读取失败，显示上次结果" else "版本读取失败，请重试"
    !state.loaded -> "等待读取远端版本"
    comparison == null -> "未读取到本机软件，请刷新检查"
    else -> comparisonStatus(comparison, state.problems.isNotEmpty())
}

@Composable
internal fun AppVersionSummary(state: RemoteVersionsState, comparison: AppVersionComparison?, local: InstalledApp?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("本机版本", size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            val installed = comparison?.local ?: local
            AppText(installed?.let { versionLabel(it.versionName, it.versionCode) } ?: "未读取到", size = 13.sp, maxLines = 3)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("远端最新", size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            AppText(comparison?.latest?.release?.let { versionLabel(it.versionName, it.versionCode) } ?: when {
                state.loading -> "读取中…"
                state.error.isNotBlank() -> "读取失败"
                !state.loaded -> "尚未读取"
                state.problems.isNotEmpty() -> "尚未确认"
                else -> "暂无版本"
            }, size = 13.sp, maxLines = 3)
        }
    }
}

private fun compatibilityMessage(release: Release, comparison: AppVersionComparison): String {
    val issues = mutableListOf<String>()
    if (comparison.localSigners.isEmpty()) issues.add("本机签名未确认")
    else if (comparison.localSigners != release.signerDigests.toSet()) issues.add("签名不一致")
    if (release.minSdk > Build.VERSION.SDK_INT) issues.add("Android 系统版本不支持")
    if (release.supportedAbis.isNotEmpty() && release.supportedAbis.none { it in Build.SUPPORTED_ABIS }) issues.add("设备架构不匹配")
    return issues.joinToString("，").ifBlank { "签名一致，设备兼容" }
}

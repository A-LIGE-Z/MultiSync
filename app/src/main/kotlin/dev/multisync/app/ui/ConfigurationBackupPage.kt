package dev.multisync.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConfigurationBackupPage(model: SyncViewModel) {
    val state by model.configurationBackup.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val scroll = rememberScrollState()
    LaunchedEffect(state.error, state.result) {
        if (state.error.isNotBlank() || state.result.isNotBlank()) scroll.animateScrollTo(0)
    }
    var includeCredentials by rememberSaveable { mutableStateOf(true) }
    var exportPassword by remember { mutableStateOf("") }
    var exportConfirmation by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> model.writeConfigurationExport(uri) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importPassword = ""; model.selectConfigurationImport(uri) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText("备份软件规则、备份规则、OpenList 连接及同步设置。", size = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 3)
            if (state.busy) UiCard { AppText("正在处理配置，请稍候…", size = 13.sp) }
            if (state.error.isNotBlank()) UiCard { AppText(state.error, size = 13.sp, maxLines = 5) }
            if (state.result.isNotBlank()) UiCard { AppText(state.result, size = 13.sp, color = MiuixTheme.colorScheme.primary, maxLines = 4) }
            SectionLabel("导出备份")
            UiCard {
                ToggleRow("包含连接凭据", includeCredentials, { includeCredentials = it }, "包含 OpenList 密码和 SSH 通道，使用备份密码加密", enabled = !state.busy)
                if (includeCredentials) {
                    Spacer(Modifier.height(12.dp))
                    EditField("备份密码", exportPassword, { exportPassword = it }, "至少 8 个字符，导入时需要此密码", KeyboardType.Password, password = true)
                    Spacer(Modifier.height(12.dp))
                    EditField("确认备份密码", exportConfirmation, { exportConfirmation = it }, keyboardType = KeyboardType.Password, password = true)
                } else {
                    AppText("导出普通 JSON；密码和私钥不会写入文件。", size = 12.sp, maxLines = 3)
                }
                Spacer(Modifier.height(16.dp))
                ActionButton("导出配置", {
                    if (includeCredentials && exportPassword != exportConfirmation) model.configurationError("两次输入的备份密码不一致")
                    else model.prepareConfigurationExport(includeCredentials, exportPassword) {
                        exportPassword = ""; exportConfirmation = ""
                        exporter.launch("MultiSync_Config_${SimpleDateFormat("yyyyMMdd_HHmmss", locale).format(Date())}.json")
                    }
                }, Modifier.fillMaxWidth(), enabled = !state.busy)
            }
            SectionLabel("导入恢复")
            UiCard {
                AppText("选择备份文件，先预览内容，再替换当前配置。设备标识始终保留；目录授权会在本机重新检查。", size = 12.sp, maxLines = 4)
                Spacer(Modifier.height(12.dp))
                ActionButton("选择配置文件", { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                    Modifier.fillMaxWidth(), primary = false, enabled = !state.busy)
                if (state.importNeedsPassword) {
                    Spacer(Modifier.height(16.dp))
                    EditField("导入备份密码", importPassword, { importPassword = it }, "请输入导出时设置的备份密码", KeyboardType.Password, password = true)
                    Spacer(Modifier.height(12.dp))
                    ActionButton("解密并预览", { model.decryptConfigurationImport(importPassword); importPassword = "" },
                        Modifier.fillMaxWidth(), enabled = !state.busy)
                }
            }
            state.preview?.let { preview ->
                SectionLabel("导入预览")
                UiCard {
                    AppText("${preview.appCount} 条软件规则 · ${preview.backupCount} 条备份规则", weight = FontWeight.Medium, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    AppText("备份时间：${formatTime(preview.createdAt, locale)}", size = 12.sp)
                    AppText(if (preview.containsCredentials) "连接凭据：从加密备份恢复" else "连接凭据：文件未包含", size = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    AppText("OpenList：${preview.baseUrl.ifBlank { "尚未配置" }}", size = 12.sp, maxLines = 3)
                    AppText("软件目录：${preview.softwarePath}", size = 12.sp, maxLines = 3)
                    AppText("文件目录：${preview.filePath}", size = 12.sp, maxLines = 3)
                    if (preview.disabledApps + preview.disabledBackups > 0) {
                        Spacer(Modifier.height(10.dp))
                        AppText("恢复后暂停：${preview.disabledApps} 条软件规则、${preview.disabledBackups} 条备份规则", size = 12.sp, maxLines = 3)
                    }
                    preview.warnings.take(8).forEach { warning ->
                        Spacer(Modifier.height(8.dp))
                        AppText(warning, size = 12.sp, maxLines = 4)
                    }
                    if (preview.warnings.size > 8) AppText("另有 ${preview.warnings.size - 8} 项需要检查。", size = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    ActionButton("导入并替换配置", model::applyConfigurationImport, Modifier.fillMaxWidth(), enabled = !state.busy)
                    Spacer(Modifier.height(8.dp))
                    ActionButton("取消导入", model::cancelConfigurationImport, Modifier.fillMaxWidth(), primary = false, enabled = !state.busy)
                }
            }
        }
    }
}

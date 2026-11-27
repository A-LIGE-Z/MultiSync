package dev.multisync.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ObserveFileAccess(model: SyncViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshFileAccess() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { model.refreshFileAccess() }
}

@Composable
internal fun rememberFileAccessRequest(model: SyncViewModel): () -> Unit {
    val context = LocalContext.current
    val manager = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refreshFileAccess() }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refreshFileAccess() }
    return {
        if (Build.VERSION.SDK_INT >= 30) {
            try { manager.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) }
            catch (_: Exception) { manager.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
}

@Composable
internal fun FileAccessPage(model: SyncViewModel) {
    val access by model.fileAccess.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val request = rememberFileAccessRequest(model)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiCard {
            AppText(if (access) "设备文件访问已授权" else "设备文件访问尚未授权")
            Spacer(Modifier.height(8.dp))
            AppText("授权后可直接选择 Download 等共享存储目录。也可通过系统选择器，只授权一个备份目录。", size = 12.sp)
            Spacer(Modifier.height(12.dp))
            ActionButton(if (access) "查看文件访问授权" else "授权文件访问", request, Modifier.fillMaxWidth())
        }
        UiCard(padding = PaddingValues(0.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("下载与导出工作目录", weight = FontWeight.Medium)
                AppText("Download/MultiSync", size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                AppText(if (access) "远端下载的安装包和文件会保存到这里；本机提取的 APK 也导出到这里用于上传。授权后自动创建。"
                    else "授权文件访问后会自动创建该目录，用于保存远端下载和本机提取的 APK。",
                    size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 4)
            }
        }
        UiCard(padding = PaddingValues(0.dp)) {
            ToggleRow("下载后自动安装", settings.autoInstallDownloads,
                { model.savePreferences(settings.copy(autoInstallDownloads = it)) }, "需要 Root 授权；关闭后仅保存安装包")
        }
        ActionButton("重新检查权限", model::refreshFileAccess, Modifier.fillMaxWidth(), primary = false)
    }
}

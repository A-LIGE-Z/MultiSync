package dev.multisync.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LocalDirectoryPicker(model: SyncViewModel, source: String, select: (String) -> Unit, systemSelect: () -> Unit, dismiss: () -> Unit) {
    val state by model.localDirectories.collectAsStateWithLifecycle()
    val access by model.fileAccess.collectAsStateWithLifecycle()
    val request = rememberFileAccessRequest(model)
    val initial = remember(source) { Uri.parse(source).takeIf { it.scheme == "file" }?.path.orEmpty() }
    fun up() {
        if (state.path.isBlank()) dismiss()
        else model.browseLocalDirectories(model.localDirectoryParent(state.path))
    }
    LaunchedEffect(access) { model.browseLocalDirectories(state.path.ifBlank { initial }) }
    DisposableEffect(Unit) { onDispose { model.cancelLocalBrowse() } }
    Dialog(onDismissRequest = ::up, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = MiuixTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::up) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一级", Modifier.size(22.dp)) }
                    AppText("选择设备目录", Modifier.weight(1f), size = 18.sp, weight = FontWeight.SemiBold)
                    IconButton(onClick = { model.refreshFileAccess(); model.browseLocalDirectories(state.path) }) { Icon(Icons.Outlined.Refresh, "刷新设备目录", Modifier.size(22.dp)) }
                    IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "取消选择", Modifier.size(22.dp)) }
                }
                AppText(state.path.ifBlank { "设备存储" }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), size = 12.sp, maxLines = 3)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!access) item { UiCard {
                        AppText("需要文件访问权限", weight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        ActionButton("授权文件访问", request, Modifier.fillMaxWidth())
                    } }
                    if (state.loading) item { UiCard { AppText("正在读取目录…", size = 13.sp) } }
                    if (access && state.error.isNotBlank()) item { UiCard { AppText(state.error, size = 13.sp, maxLines = 4) } }
                    if (access && !state.loading && state.error.isBlank() && state.directories.isEmpty()) item { UiCard { AppText("没有子目录，可选择当前目录。", size = 13.sp) } }
                    items(state.directories, key = { it.path }) { directory -> UiCard(padding = PaddingValues(0.dp)) {
                        MenuRow(directory.name, icon = Icons.Outlined.Folder, onClick = { model.browseLocalDirectories(directory.path) })
                    } }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("通过系统选择器授权目录", { dismiss(); systemSelect() }, Modifier.fillMaxWidth(), primary = false)
                    ActionButton("选择当前目录", { select(Uri.fromFile(File(state.path)).toString()) }, Modifier.fillMaxWidth(),
                        enabled = access && !state.loading && state.error.isBlank() && state.path.isNotBlank())
                }
            }
        }
    }
}

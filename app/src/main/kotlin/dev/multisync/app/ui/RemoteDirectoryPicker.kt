package dev.multisync.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.multisync.app.data.Settings
import dev.multisync.core.remoteChildPath
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RemoteDirectoryPicker(model: SyncViewModel, draft: Settings, password: String, initialPath: String,
    title: String, select: (String) -> Unit, dismiss: () -> Unit) {
    val state by model.remoteDirectories.collectAsStateWithLifecycle()
    fun parent() = state.path.trim('/').substringBeforeLast('/', "").ifBlank { "/" }
    fun up() { if (state.path == "/") dismiss() else model.browseRemoteDirectories(draft, password, parent()) }
    LaunchedEffect(Unit) { model.browseRemoteDirectories(draft, password, initialPath.ifBlank { "/" }) }
    DisposableEffect(Unit) { onDispose { model.cancelDirectoryBrowse() } }
    Dialog(onDismissRequest = ::up, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val dark = draft.theme == "dark" || (draft.theme == "system" && isSystemInDarkTheme())
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        } }
        Surface(color = MiuixTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::up) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, if (state.path == "/") "返回配置" else "上一级", Modifier.size(22.dp)) }
                    AppText(title, Modifier.weight(1f), size = 18.sp, weight = FontWeight.SemiBold, maxLines = 1)
                    IconButton(onClick = { model.browseRemoteDirectories(draft, password, state.path) }) { Icon(Icons.Outlined.Refresh, "刷新目录", Modifier.size(22.dp)) }
                    IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "取消选择", Modifier.size(22.dp)) }
                }
                UiCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), PaddingValues(12.dp)) {
                    AppText("当前目录", size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    AppText(if (state.path == "/") "/" else "/${state.path.trim('/')}", size = 13.sp, maxLines = 3)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.loading) item { UiCard { AppText("正在读取远端目录…", size = 13.sp) } }
                    if (state.error.isNotBlank()) item { UiCard { AppText(state.error, size = 13.sp, maxLines = 4) } }
                    if (state.loaded && state.directories.isEmpty()) item { UiCard { AppText("没有子目录，可以选择当前目录。", size = 13.sp) } }
                    items(state.directories, key = { it.name }) { directory ->
                        UiCard(padding = PaddingValues(0.dp)) { MenuRow(directory.name, icon = Icons.Outlined.Folder,
                            onClick = { model.browseRemoteDirectories(draft, password, remoteChildPath(state.path, directory.name)) }) }
                    }
                }
                Surface(color = MiuixTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                        ActionButton("选择当前目录", { select(state.path) }, Modifier.widthIn(max = 600.dp).fillMaxWidth(), enabled = state.loaded && !state.loading)
                    }
                }
            }
        }
    }
}

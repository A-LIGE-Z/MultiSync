package dev.multisync.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.multisync.core.InstalledApp
import dev.multisync.core.filterInstalledApps
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun InstalledAppPicker(model: SyncViewModel, onSelect: (InstalledApp) -> Unit, onCancel: () -> Unit) {
    val apps by model.installedApps.collectAsStateWithLifecycle()
    val loading by model.appsLoading.collectAsStateWithLifecycle()
    val error by model.appsError.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(apps, query, showSystem) { filterInstalledApps(apps, query, showSystem) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { model.loadInstalledApps() }
    BackHandler { onCancel() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(value = query, onValueChange = { query = it }, label = "搜索名称或包名", useLabelAsPlaceholder = true,
                singleLine = true, insideMargin = DpSize(12.dp, 10.dp), cornerRadius = 14.dp,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                textStyle = MiuixTheme.textStyles.main.copy(fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically) {
                AppText(if (loading) "正在读取…" else "${filtered.size} 个应用", Modifier.weight(1f), size = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                AppText("系统应用", size = 12.sp)
                Spacer(Modifier.width(8.dp))
                Switch(showSystem, { showSystem = it })
            }
        }
        if (error.isNotBlank()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(error, fontSize = 14.sp) }
        } else if (filtered.isEmpty() && !loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("没有找到匹配的应用", fontSize = 14.sp) }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    Card(modifier = Modifier.fillMaxWidth().alpha(if (app.isSystem) 0.6f else 1f), cornerRadius = 16.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp), onClick = if (app.isSystem) null else ({ onSelect(app) })) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ApplicationIcon(model, app.packageName, app.label, 38.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                AppText(app.label, size = 14.sp, weight = FontWeight.Medium, maxLines = 1)
                                AppText(buildString {
                                    append(app.versionName.ifBlank { app.versionCode.toString() })
                                    if (app.isSplit) append(" · 分包")
                                    append(" · ")
                                    append(if (app.isSystem) "系统应用，仅查看" else app.packageName)
                                }, size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
                            }
                            if (!app.isSystem) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ApplicationIdentity(model: SyncViewModel, packageName: String, label: String, showVersion: Boolean = true) {
    val app: InstalledApp? = if (showVersion) {
        val apps by model.installedApps.collectAsStateWithLifecycle()
        apps.firstOrNull { it.packageName == packageName }
    } else null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ApplicationIcon(model, packageName, label, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            AppText(label, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 2)
            Text(packageName, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (showVersion && app != null) Text("版本 ${app.versionName.ifBlank { app.versionCode.toString() }}", fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
internal fun ApplicationIcon(model: SyncViewModel, packageName: String, label: String, size: Dp = 44.dp) {
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, packageName, pixels) {
        value = model.applicationIcon(packageName, pixels)?.asImageBitmap()
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap = bitmap!!, contentDescription = null, modifier = Modifier.fillMaxSize())
        else Text(label.take(1), fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

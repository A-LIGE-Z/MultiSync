package dev.multisync.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multisync.app.sync.SyncTransferState
import dev.multisync.core.TransferPhase
import java.util.Locale
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TransferCard(state: SyncTransferState, cancel: (() -> Unit)? = null) {
    val locale = LocalConfiguration.current.locales[0]
    val progress = state.network
    val color = if (state.error.isNotBlank() || progress?.phase == TransferPhase.FAILED) Color(0xFFD95454) else MiuixTheme.colorScheme.primary
    UiCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText(listOf(state.ruleName, state.stage).filter(String::isNotBlank).joinToString(" · "), Modifier.weight(1f),
                size = 13.sp, weight = FontWeight.Medium, maxLines = 2)
            if (state.fileCount > 0) AppText("${state.fileIndex}/${state.fileCount}", size = 11.sp)
        }
        if (state.fileName.isNotBlank()) AppText(state.fileName, size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        val fraction = progress?.fraction
        BoxWithConstraints(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
            if (fraction != null) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
            else {
                val animation = rememberInfiniteTransition(label = "transfer")
                val position by animation.animateFloat(-0.25f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "transfer-position")
                Box(Modifier.offset(x = maxWidth * position).width(maxWidth * 0.25f).fillMaxHeight().background(color))
            }
        }
        if (progress != null) {
            val storagePercent = progress.storagePercent
            if (progress.phase == TransferPhase.WAITING && storagePercent != null) {
                Spacer(Modifier.height(7.dp))
                AppText("分片已发送 · 网盘处理 ${storagePercent.roundToInt()}%", size = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText("${formatBytes(progress.bytes.toDouble(), locale)} / ${progress.totalBytes?.let { formatBytes(it.toDouble(), locale) } ?: "大小未知"}",
                    Modifier.weight(1f), size = 11.sp, maxLines = 2)
                AppText(fraction?.let { "${(it * 100).roundToInt()}%" } ?: "传输中", size = 11.sp)
                AppText("${formatBytes(progress.bytesPerSecond, locale)}/s", size = 11.sp)
            }
        }
        if (cancel != null) {
            Spacer(Modifier.height(8.dp))
            ActionButton("取消下载", cancel, Modifier.fillMaxWidth(), primary = false)
        }
    }
}

private fun formatBytes(bytes: Double, locale: Locale): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var amount = bytes
    var index = 0
    while (amount >= 1024 && index < units.lastIndex) { amount /= 1024; index++ }
    return if (index == 0) "${amount.toLong()} B" else "${String.format(locale, "%.1f", amount)} ${units[index]}"
}

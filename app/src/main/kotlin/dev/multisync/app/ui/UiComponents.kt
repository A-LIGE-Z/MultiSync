package dev.multisync.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AppText(text: String, modifier: Modifier = Modifier, size: TextUnit = 14.sp,
    weight: FontWeight? = null, color: Color = Color.Unspecified, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier = modifier, fontSize = size, lineHeight = size * 1.35f, fontWeight = weight,
        color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun SectionLabel(text: String) {
    AppText(text, Modifier.padding(start = 4.dp, top = 4.dp), size = 12.sp,
        weight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
internal fun UiCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = padding, content = content)
}

@Composable
internal fun GroupDivider() {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.surfaceContainerHigh))
}

@Composable
internal fun MenuRow(title: String, summary: String = "", icon: ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MiuixTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            AppText(title, weight = FontWeight.Medium)
            if (summary.isNotBlank()) AppText(summary, size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
internal fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, summary: String = "", enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            AppText(title, color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (summary.isNotBlank()) AppText(summary, size = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), minHeight = 48.dp, cornerRadius = 16.dp,
        enabled = enabled, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
        AppText(label, weight = FontWeight.Medium, maxLines = 2)
    }
}

@Composable
internal fun FooterAction(label: String, onClick: () -> Unit) {
    val focus = LocalFocusManager.current
    Surface(color = MiuixTheme.colorScheme.surface) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            ActionButton(label, { focus.clearFocus(); onClick() }, Modifier.widthIn(max = 600.dp).fillMaxWidth())
        }
    }
}

@Composable
internal fun EditField(label: String, value: String, onChange: (String) -> Unit, hint: String = "",
    keyboardType: KeyboardType = KeyboardType.Text, password: Boolean = false, multiline: Boolean = false) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText(label, size = 12.sp, weight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        TextField(value, onValueChange = onChange, label = "", singleLine = !multiline, maxLines = if (multiline) 3 else 1,
            insideMargin = DpSize(12.dp, 10.dp), cornerRadius = 14.dp,
            textStyle = MiuixTheme.textStyles.main.copy(fontSize = 14.sp, lineHeight = 20.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (multiline) ImeAction.Default else ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
        if (hint.isNotBlank()) AppText(hint, size = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2)
    }
}

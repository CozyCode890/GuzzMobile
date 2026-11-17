package io.github.cozycode890.guzz.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.settings.BoolSetting
import io.github.cozycode890.guzz.core.common.settings.ChoiceSetting
import io.github.cozycode890.guzz.core.common.settings.DoubleSetting
import io.github.cozycode890.guzz.core.common.settings.IntSetting
import io.github.cozycode890.guzz.core.common.settings.SettingKey
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.core.common.settings.StringSetting
import kotlinx.coroutines.launch

/** Thẻ một nhóm mục cài đặt, như `GroupCard` của desktop. */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            hint?.let { Hint(it) }
            content()
        }
    }
}

/** Dòng chữ nhỏ giải thích dưới một mục. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/**
 * Phần "Nâng cao" gập lại (D117): mục ít dùng nằm cuối trang, bấm mới mở. Nhớ trạng thái mở khi xoay màn hình.
 */
@Composable
fun AdvancedSection(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.common_advanced),
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp).testTag("advanced"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        if (open) content()
    }
}

/** Công tắc gắn thẳng với một [BoolSetting]: bấm là ghi. */
@Composable
fun SwitchSetting(settings: Settings, key: BoolSetting, label: String, hint: String? = null, enabled: Boolean = true) {
    val scope = rememberCoroutineScope()
    val value by settings.flow(key).collectAsState(initial = key.default)
    SwitchRow(label, value, hint, enabled) { v -> scope.launch { settings.set(key, v) } }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, hint: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label)
            hint?.let { Hint(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Chọn một trong các giá trị của [key]. [options]: giá trị → chữ hiện ra; giá trị không có trong [options] thì hiện
 * nguyên giá trị.
 */
@Composable
fun ChoiceSettingRow(settings: Settings, key: ChoiceSetting, label: String, options: List<Pair<String, String>>, hint: String? = null) {
    val scope = rememberCoroutineScope()
    val value by settings.flow(key).collectAsState(initial = key.default)
    ChoiceRow(label, value, options, hint) { v -> scope.launch { settings.set(key, v) } }
}

@Composable
fun ChoiceRow(label: String, value: String, options: List<Pair<String, String>>, hint: String? = null, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 4.dp),
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(label)
                Text(
                    options.firstOrNull { it.first == value }?.second ?: value,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                )
                hint?.let { Hint(it) }
            }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    onChoose(v)
                })
            }
        }
    }
}

/**
 * Kiểm một chuỗi số cho [IntSetting] / [DoubleSetting]: trả số đã đọc, hoặc `null` khi không phải số hay nằm ngoài
 * [min, max] của mục. Nhận cả dấu phẩy thập phân (bàn phím tiếng Việt).
 */
fun parseNumber(key: SettingKey<*>, text: String): Number? {
    val t = text.trim().replace(',', '.')
    return when (key) {
        is IntSetting -> t.toIntOrNull()?.takeIf { it in key.min..key.max }
        is DoubleSetting -> t.toDoubleOrNull()?.takeIf { !it.isNaN() && it >= key.min && it <= key.max }
        else -> null
    }
}

/** Khoảng cho phép của mục, để ghi trong lỗi; `null` khi không chặn. */
private fun rangeText(key: SettingKey<*>): String? = when (key) {
    is IntSetting -> when {
        key.min != Int.MIN_VALUE && key.max != Int.MAX_VALUE -> "${key.min} – ${key.max}"
        key.min != Int.MIN_VALUE -> "≥ ${key.min}"
        key.max != Int.MAX_VALUE -> "≤ ${key.max}"
        else -> null
    }
    is DoubleSetting -> when {
        key.min.isFinite() && key.max.isFinite() -> "${key.toConfigText(key.min)} – ${key.toConfigText(key.max)}"
        key.min.isFinite() -> "≥ ${key.toConfigText(key.min)}"
        key.max.isFinite() -> "≤ ${key.toConfigText(key.max)}"
        else -> null
    }
    else -> null
}

/**
 * Ô số cho [IntSetting] / [DoubleSetting] (B4): chỉ ghi khi là số hợp lệ trong khoảng của mục; sai thì báo dưới ô và
 * giữ giá trị cũ trong cài đặt.
 */
@Composable
fun NumberSetting(
    settings: Settings,
    key: SettingKey<*>,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    require(key is IntSetting || key is DoubleSetting) { "${key.id} không phải mục số" }
    val scope = rememberCoroutineScope()
    @Suppress("UNCHECKED_CAST")
    val stored by settings.flow(key as SettingKey<Any>).collectAsState(initial = null)
    val storedText = stored?.let { key.toConfigText(it) } ?: ""
    var text by remember(storedText) { mutableStateOf(storedText) }
    val invalid = text.isNotEmpty() && parseNumber(key, text) == null
    val range = rangeText(key)
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            when (val n = parseNumber(key, t)) {
                null -> Unit
                is Int -> scope.launch { settings.set(key as IntSetting, n) }
                else -> scope.launch { settings.set(key as DoubleSetting, n.toDouble()) }
            }
        },
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        supportingText = when {
            invalid -> {
                { Text(if (range != null) stringResource(R.string.common_number_range, range) else stringResource(R.string.common_number_invalid)) }
            }
            hint != null -> {
                { Text(hint) }
            }
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = if (key is IntSetting) KeyboardType.Number else KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth().testTag("number:${key.id}"),
    )
}

/**
 * Ô chữ cho [StringSetting]. [validate] trả câu báo lỗi (không lưu) hoặc `null`; mặc định chuỗi trống không được lưu
 * (mục trống mà có `clean` thì cài đặt tự lấy mặc định), trừ khi [allowBlank].
 */
@Composable
fun TextSetting(
    settings: Settings,
    key: StringSetting,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    singleLine: Boolean = true,
    allowBlank: Boolean = false,
    enabled: Boolean = true,
    validate: (String) -> String? = { null },
) {
    val scope = rememberCoroutineScope()
    val stored by settings.flow(key).collectAsState(initial = null)
    var text by remember(stored) { mutableStateOf(stored ?: "") }
    val error = validate(text)
    val message = error ?: hint
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            if ((allowBlank || t.isNotBlank()) && validate(t) == null) scope.launch { settings.set(key, t) }
        },
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        enabled = enabled,
        isError = error != null,
        supportingText = if (message != null) { { Text(message) } } else null,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Nút "Khôi phục mặc định" cho các mục của một trang; hỏi lại trước khi xoá giá trị đã đặt. */
@Composable
fun ResetDefaultsButton(settings: Settings, keys: List<SettingKey<*>>, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }, modifier = modifier) { Text(stringResource(R.string.common_restore_default)) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.common_restore_default)) },
            text = { Text(stringResource(R.string.common_restore_default_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { keys.forEach { settings.reset(it) } }
                }) { Text(stringResource(R.string.common_restore_default)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** Hộp hỏi lại trước một việc khó hoàn tác (Đặt lại bộ đếm, Xoá lịch sử…). */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

package io.github.cozycode890.guzz.feature.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.security.SecureRandom

/** Trạng thái trang Tự động hoá. Trường quyền / pin / thông báo đọc lại mỗi lần trang hiện ([AutomationSettingsModel.refresh]). */
data class AutomationUiState(
    val enabled: Boolean = false,
    val secret: String = "",
    val includePaths: Boolean = true,
    val saveBesideSource: Boolean = false,
    val allFilesFlavor: Boolean = false,
    val allFilesAccess: Boolean = false,
    val sourceDirs: List<Uri> = emptyList(),
    val batteryExempt: Boolean = false,
    val notificationsOn: Boolean = true,
    val packageName: String = "",
)

/** Việc trang gọi ra ngoài; Activity chủ (hoặc route của Stage 8) cấp các launcher cần `ActivityResult`. */
class AutomationActions(
    val setEnabled: (Boolean) -> Unit,
    val setSecret: (String) -> Unit,
    val setIncludePaths: (Boolean) -> Unit,
    val setSaveBesideSource: (Boolean) -> Unit,
    val requestAllFiles: () -> Unit,
    val addSourceDir: () -> Unit,
    val removeSourceDir: (Uri) -> Unit,
    val requestBattery: () -> Unit,
    val requestNotifications: () -> Unit,
    val sendTestEvent: () -> Unit,
)

/**
 * Dữ liệu của trang (D99): cài đặt `[AUTOMATION]` + trạng thái quyền. Không phụ thuộc `AppContainer` để Stage 8 dựng lại
 * trang bằng widget chung mà vẫn dùng lớp này.
 */
class AutomationSettingsModel(private val automation: Automation, private val settings: Settings, private val packageName: String) {
    private val system = MutableStateFlow(SystemState())

    private data class SystemState(val allFiles: Boolean = false, val battery: Boolean = false, val notifications: Boolean = true)

    val state: Flow<AutomationUiState> = combine(
        settings.flow(SettingKeys.AUTOMATION_ENABLED),
        settings.flow(SettingKeys.AUTOMATION_SECRET),
        settings.flow(SettingKeys.AUTOMATION_INCLUDE_PATHS),
        settings.flow(SettingKeys.SAVE_BESIDE_SOURCE),
        automation.fileAccess.sourceDirsFlow(),
    ) { enabled, secret, include, beside, dirs -> listOf(enabled, secret, include, beside, dirs) }
        .combine(system) { v, sys ->
            @Suppress("UNCHECKED_CAST")
            AutomationUiState(
                enabled = v[0] as Boolean, secret = v[1] as String, includePaths = v[2] as Boolean,
                saveBesideSource = v[3] as Boolean, allFilesFlavor = automation.allFilesFlavor,
                allFilesAccess = sys.allFiles, sourceDirs = v[4] as List<Uri>, batteryExempt = sys.battery,
                notificationsOn = sys.notifications, packageName = packageName,
            )
        }

    /** Đọc lại quyền (gọi ở `onResume`: người dùng vừa quay về từ trang cài đặt hệ thống). */
    fun refresh() {
        system.value = SystemState(
            allFiles = automation.fileAccess.hasAllFilesAccess(),
            battery = automation.battery.isIgnoringBatteryOptimizations(),
            notifications = automation.battery.notificationsEnabled(),
        )
    }

    suspend fun setEnabled(v: Boolean) {
        secretOnEnable(v, settings.get(SettingKeys.AUTOMATION_SECRET))?.let { settings.set(SettingKeys.AUTOMATION_SECRET, it) }
        settings.set(SettingKeys.AUTOMATION_ENABLED, v)
    }
    suspend fun setSecret(v: String) = settings.set(SettingKeys.AUTOMATION_SECRET, v)
    suspend fun setIncludePaths(v: Boolean) = settings.set(SettingKeys.AUTOMATION_INCLUDE_PATHS, v)
    suspend fun setSaveBesideSource(v: Boolean) = settings.set(SettingKeys.SAVE_BESIDE_SOURCE, v)
    suspend fun addSourceDir(tree: Uri) = automation.fileAccess.addSourceDir(tree)
    suspend fun removeSourceDir(tree: Uri) = automation.fileAccess.removeSourceDir(tree)
    suspend fun sendTestEvent() = automation.sendTestEvent()

    companion object {
        /**
         * Stage 9 B1 (D138): bật "Cho phép app khác điều khiển Guzz" mà mã bí mật đang trống thì tự tạo mã, để không phải
         * mọi app trên máy đều gửi lệnh được. Trả về mã mới, `null` = giữ nguyên. Người dùng vẫn xoá trắng được sau đó.
         */
        fun secretOnEnable(enabled: Boolean, current: String, generate: () -> String = ::randomSecret): String? =
            if (enabled && current.isBlank()) generate() else null

        /** Mã bí mật ngẫu nhiên: 16 ký tự chữ thường + số, dễ dán vào MacroDroid. */
        fun randomSecret(): String {
            val chars = "abcdefghijkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..16).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }
    }
}

/**
 * Nội dung trang Tự động hoá (Stage 6, D99), đặt trong trang cài đặt của `:app` (Stage 8 H1): trang chủ lo cuộn, lề và
 * thanh trên. [advanced]: phần gập "Nâng cao" của app (D117), chứa mục ít dùng (tên lệnh / sự kiện để chép, gửi thử).
 */
@Composable
fun ColumnScope.AutomationSettingsScreen(
    state: AutomationUiState,
    actions: AutomationActions,
    advanced: @Composable (content: @Composable ColumnScope.() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    Text(stringResource(R.string.automation_intro), style = MaterialTheme.typography.bodyMedium)

    SectionCard {
        SwitchRow(stringResource(R.string.automation_enabled), stringResource(R.string.automation_enabled_hint), state.enabled, actions.setEnabled)
        SecretField(state.secret, actions.setSecret)
        SwitchRow(
            stringResource(R.string.automation_include_paths), stringResource(R.string.automation_include_paths_hint),
            state.includePaths, actions.setIncludePaths,
        )
        if (state.allFilesFlavor) {
            SwitchRow(
                stringResource(R.string.automation_beside_source), stringResource(R.string.automation_beside_source_hint),
                state.saveBesideSource, actions.setSaveBesideSource,
            )
        }
    }

    Text(stringResource(R.string.automation_section_access), style = MaterialTheme.typography.titleMedium)
    SectionCard {
        if (state.allFilesFlavor) {
            StatusRow(
                stringResource(R.string.automation_all_files),
                stringResource(if (state.allFilesAccess) R.string.automation_all_files_on else R.string.automation_all_files_off),
                ok = state.allFilesAccess, actionLabel = stringResource(R.string.automation_grant), onAction = actions.requestAllFiles,
            )
        }
        Text(stringResource(R.string.automation_source_dirs), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.automation_source_dirs_hint), style = MaterialTheme.typography.bodySmall)
        if (state.sourceDirs.isEmpty()) {
            Text(stringResource(R.string.automation_source_dirs_empty), style = MaterialTheme.typography.bodySmall)
        }
        for (dir in state.sourceDirs) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(FileAccess.describeTree(dir), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { actions.removeSourceDir(dir) }) { Text(stringResource(R.string.automation_source_remove)) }
            }
        }
        OutlinedButton(onClick = actions.addSourceDir) { Text(stringResource(R.string.automation_source_add)) }
        StatusRow(
            stringResource(R.string.automation_battery),
            stringResource(if (state.batteryExempt) R.string.automation_battery_on else R.string.automation_battery_off),
            ok = state.batteryExempt, actionLabel = stringResource(R.string.automation_fix), onAction = actions.requestBattery,
        )
        StatusRow(
            stringResource(R.string.automation_notifications),
            stringResource(if (state.notificationsOn) R.string.automation_notifications_on else R.string.automation_notifications_off),
            ok = state.notificationsOn, actionLabel = stringResource(R.string.automation_fix), onAction = actions.requestNotifications,
        )
    }

    advanced {
        Text(stringResource(R.string.automation_section_api), style = MaterialTheme.typography.titleMedium)
        SectionCard {
            Text(stringResource(R.string.automation_api_hint, state.packageName), style = MaterialTheme.typography.bodySmall)
            CopyLine(stringResource(R.string.automation_label_package), state.packageName)
            CopyLine(stringResource(R.string.automation_label_receiver), GuzzIntents.RECEIVER_CLASS)
            CopyLine(stringResource(R.string.automation_label_activity), GuzzIntents.ACTIVITY_CLASS)
            Text(stringResource(R.string.automation_label_commands), style = MaterialTheme.typography.titleSmall)
            GuzzIntents.actions.forEach { CopyLine(null, it) }
            Text(stringResource(R.string.automation_label_events), style = MaterialTheme.typography.titleSmall)
            GuzzIntents.events.forEach { CopyLine(null, it) }
            OutlinedButton(onClick = {
                if (!state.enabled) {
                    Toast.makeText(context, R.string.automation_test_event_disabled, Toast.LENGTH_SHORT).show()
                } else {
                    actions.sendTestEvent()
                    Toast.makeText(context, R.string.automation_test_event_sent, Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.automation_test_event)) }
            Text(stringResource(R.string.automation_test_event_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusRow(title: String, text: String, ok: Boolean, actionLabel: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text((if (ok) "✓ " else "✗ ") + title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
        if (!ok) OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun SecretField(secret: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    var text by remember(secret) { mutableStateOf(secret) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it)
        },
        label = { Text(stringResource(R.string.automation_secret)) },
        supportingText = { Text(stringResource(R.string.automation_secret_hint)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Row {
        TextButton(onClick = { visible = !visible }) {
            Text(stringResource(if (visible) R.string.automation_secret_hide else R.string.automation_secret_show))
        }
        TextButton(onClick = {
            val s = AutomationSettingsModel.randomSecret()
            text = s
            visible = true
            onChange(s)
        }) { Text(stringResource(R.string.automation_secret_generate)) }
    }
}

@Composable
private fun CopyLine(label: String?, value: String) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clickable { copy(context, value) }.padding(vertical = 2.dp),
    ) {
        label?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun copy(context: Context, value: String) {
    // Nhãn của clip là tên kỹ thuật, không hiện lên giao diện.
    val clip = ClipData.newPlainText("Guzz", value) // strings-ok
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    Toast.makeText(context, context.getString(R.string.automation_copied, value), Toast.LENGTH_SHORT).show()
}

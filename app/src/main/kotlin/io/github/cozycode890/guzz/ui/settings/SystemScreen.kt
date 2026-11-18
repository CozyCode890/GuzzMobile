package io.github.cozycode890.guzz.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.AppContainer
import io.github.cozycode890.guzz.AppLanguage
import io.github.cozycode890.guzz.BuildConfig
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.settings.ConfigIni
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.ui.components.AdvancedSection
import io.github.cozycode890.guzz.ui.components.ChoiceRow
import io.github.cozycode890.guzz.ui.components.ChoiceSettingRow
import io.github.cozycode890.guzz.ui.components.Hint
import io.github.cozycode890.guzz.ui.components.NumberSetting
import io.github.cozycode890.guzz.ui.components.ResetDefaultsButton
import io.github.cozycode890.guzz.ui.components.SectionCard
import io.github.cozycode890.guzz.ui.components.SettingsPage
import io.github.cozycode890.guzz.ui.components.SwitchSetting
import io.github.cozycode890.guzz.ui.convert.DOCUMENTS_HINT
import io.github.cozycode890.guzz.ui.setup.SetupList
import io.github.cozycode890.guzz.ui.setup.rememberSetupState
import io.github.cozycode890.guzz.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Mục không đi theo tệp xuất / nhập (D112): thư mục lưu là URI SAF chỉ có nghĩa trên máy đã cấp quyền (desktop là đường
 * dẫn Windows). API key không nằm trong Settings nên không bao giờ được xuất.
 */
val NOT_TRANSFERRED = setOf(SettingKeys.OUTPUT_DIR.id)

/** Xuất mọi cài đặt ra INI (D112). */
suspend fun exportConfig(container: AppContainer): String {
    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val header = listOf(
        "Guzz Mobile ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), $now",
        "Cùng định dạng config.txt của Guzz desktop. Không có API key và thư mục lưu.",
    )
    return ConfigIni.export(container.settings, SettingKeys.all, NOT_TRANSFERRED, header)
}

/** Nhập INI vào cài đặt; ngôn ngữ nhập vào được áp ngay (D115). */
suspend fun importConfig(container: AppContainer, text: String): ConfigIni.ImportReport {
    val plan = ConfigIni.plan(ConfigIni.parse(text), SettingKeys.all, NOT_TRANSFERRED)
    ConfigIni.apply(container.settings, plan)
    GuzzLog.info(
        "Nhập cài đặt: ${plan.report.applied.size} mục, bỏ qua ${plan.report.skipped.size}, lỗi ${plan.report.errors.size}" +
            plan.report.errors.joinToString("") { "\n  lỗi: $it" },
    )
    return plan.report
}

private fun readText(context: Context, uri: Uri): String =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

private fun writeText(context: Context, uri: Uri, text: String) {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        ?: error("openOutputStream null")
}

private val systemKeys = listOf(
    SettingKeys.LOG_LEVEL, SettingKeys.LOG_SIZE_MB, SettingKeys.OLD_LOG_COUNT, SettingKeys.DELETE_TEMP_WHEN_DONE,
)

/** Cài đặt → Hệ thống (C5, F1, F3): giao diện, Thiết lập ban đầu, xuất / nhập cài đặt; Nâng cao: nhật ký, thư mục tạm. */
@Composable
fun SystemScreen(container: AppContainer, resumes: StateFlow<Int>, onBack: () -> Unit, open: (String) -> Unit) {
    val settings = container.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val language by settings.flow(SettingKeys.LANGUAGE).collectAsState(initial = AppLanguage.current())
    var refresh by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<ConfigIni.ImportReport?>(null) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    val exportedMessage = stringResource(R.string.system_export_done)
    val failedMessage = stringResource(R.string.system_transfer_failed)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch {
                transferMessage = try {
                    val text = exportConfig(container)
                    withContext(Dispatchers.IO) { writeText(context, uri, text) }
                    GuzzLog.info("Xuất cài đặt: $uri")
                    exportedMessage
                } catch (e: Exception) {
                    GuzzLog.warning("Không xuất được cài đặt: ${e.message}")
                    failedMessage + " " + (e.message ?: "")
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) { readText(context, uri) }
                    report = importConfig(container, text)
                    transferMessage = null
                    // Ngôn ngữ nhập vào được áp khi đóng hộp tóm tắt: áp ngay thì AppCompat dựng lại Activity và hộp mất.
                } catch (e: Exception) {
                    GuzzLog.warning("Không nhập được cài đặt: ${e.message}")
                    transferMessage = failedMessage + " " + (e.message ?: "")
                }
            }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            scope.launch { settings.set(SettingKeys.OUTPUT_DIR, uri.toString()) }
        }
    }

    SettingsPage(stringResource(R.string.settings_system_title), onBack) {
        SectionCard(stringResource(R.string.system_appearance)) {
            ChoiceRow(
                stringResource(R.string.system_language), language,
                listOf(
                    AppLanguage.AUTO to stringResource(R.string.system_language_auto),
                    "vi" to stringResource(R.string.lang_vi),
                    "en" to stringResource(R.string.lang_en),
                ),
            ) { v -> scope.launch { AppLanguage.set(settings, v) } }
            ChoiceSettingRow(
                settings, SettingKeys.THEME, stringResource(R.string.system_theme),
                listOf(
                    ThemeMode.AUTO to stringResource(R.string.system_theme_auto),
                    ThemeMode.LIGHT to stringResource(R.string.system_theme_light),
                    ThemeMode.DARK to stringResource(R.string.system_theme_dark),
                ),
            )
        }

        SectionCard(stringResource(R.string.setup_title), hint = stringResource(R.string.setup_hint)) {
            rememberSetupState(container, resumes, refresh)?.let { state ->
                SetupList(state, onlyPending = false, open = open, onPickFolder = { pickFolder.launch(DOCUMENTS_HINT) }) { refresh++ }
            }
        }

        SectionCard(stringResource(R.string.system_transfer), hint = stringResource(R.string.system_transfer_hint)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("guzz-config.txt") }) { Text(stringResource(R.string.system_export)) }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) }) {
                    Text(stringResource(R.string.system_import))
                }
            }
            transferMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        AdvancedSection {
            SectionCard(stringResource(R.string.system_logs_section)) {
                ChoiceSettingRow(
                    settings, SettingKeys.LOG_LEVEL, stringResource(R.string.system_log_level),
                    listOf("INFO" to stringResource(R.string.system_log_info), "DEBUG" to stringResource(R.string.system_log_debug)),
                )
                NumberSetting(settings, SettingKeys.LOG_SIZE_MB, stringResource(R.string.system_log_size))
                NumberSetting(settings, SettingKeys.OLD_LOG_COUNT, stringResource(R.string.system_log_count))
            }
            SectionCard(stringResource(R.string.system_temp_section)) {
                SwitchSetting(
                    settings, SettingKeys.DELETE_TEMP_WHEN_DONE, stringResource(R.string.system_delete_temp),
                    hint = stringResource(R.string.system_delete_temp_hint),
                )
            }
            ResetDefaultsButton(settings, systemKeys)
        }
    }

    report?.let { r ->
        val close = {
            report = null
            scope.launch { AppLanguage.apply(settings.get(SettingKeys.LANGUAGE)) }
            Unit
        }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.system_import_done)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.system_import_summary, r.applied.size, r.skipped.size, r.errors.size))
                    if (r.errors.isNotEmpty()) {
                        Text(stringResource(R.string.system_import_errors), style = MaterialTheme.typography.titleSmall)
                        r.errors.forEach { Hint(it) }
                    }
                    if (r.skipped.isNotEmpty()) {
                        Text(stringResource(R.string.system_import_skipped), style = MaterialTheme.typography.titleSmall)
                        Hint(r.skipped.joinToString(", "))
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

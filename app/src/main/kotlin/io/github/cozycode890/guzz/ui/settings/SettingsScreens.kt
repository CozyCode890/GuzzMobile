package io.github.cozycode890.guzz.ui.settings

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.cozycode890.guzz.AppContainer
import io.github.cozycode890.guzz.BuildConfig
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.core.pipeline.OutputNaming
import io.github.cozycode890.guzz.feature.automation.R as AutomationR
import io.github.cozycode890.guzz.ui.components.AdvancedSection
import io.github.cozycode890.guzz.ui.components.ChoiceSettingRow
import io.github.cozycode890.guzz.ui.components.Hint
import io.github.cozycode890.guzz.ui.components.ResetDefaultsButton
import io.github.cozycode890.guzz.ui.components.SectionCard
import io.github.cozycode890.guzz.ui.components.SettingsPage
import io.github.cozycode890.guzz.ui.components.SwitchSetting
import io.github.cozycode890.guzz.ui.components.TextSetting
import io.github.cozycode890.guzz.ui.nav.Routes
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Một mục của danh sách Cài đặt. */
private data class Entry(val route: String, val title: Int, val summary: Int)

private val entries = listOf(
    Entry(Routes.GOOGLE_AI, R.string.google_ai_title, R.string.settings_google_ai_summary),
    Entry(Routes.AUDIO, R.string.audio_title, R.string.settings_audio_summary),
    Entry(Routes.SPEAKERS, R.string.speakers_title, R.string.settings_speakers_summary),
    Entry(Routes.OUTPUT, R.string.settings_output_title, R.string.settings_output_summary),
    Entry(Routes.QUEUE, R.string.settings_queue_title, R.string.settings_queue_summary),
    Entry(Routes.AUTOMATION, AutomationR.string.automation_title, R.string.settings_automation_summary),
    Entry(Routes.SERVER, R.string.server_title, R.string.settings_server_summary),
    Entry(Routes.SYSTEM, R.string.settings_system_title, R.string.settings_system_summary),
    Entry(Routes.LOGS, R.string.logs_title, R.string.settings_logs_summary),
    Entry(Routes.ABOUT, R.string.about_title, R.string.settings_about_summary),
)

/** Tab Cài đặt (D110): danh sách các trang con, mỗi trang một dòng tên + mô tả ngắn. */
@Composable
fun SettingsHomeScreen(@Suppress("UNUSED_PARAMETER") container: AppContainer, open: (String) -> Unit) {
    SettingsPage(stringResource(R.string.nav_settings), onBack = null) {
        Card(Modifier.fillMaxWidth()) {
            entries.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { open(e.route) }.padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("entry:${e.route}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(e.title), style = MaterialTheme.typography.titleMedium)
                        Hint(stringResource(e.summary))
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

/** Tên ví dụ cho dòng xem trước mẫu tên tệp, như `set_name_preview` của desktop. */
fun filenamePreview(template: String, extension: String): String? =
    if (OutputNaming.templateError(template) != null) {
        null
    } else {
        val at = LocalDateTime.of(2026, 11, 17, 9, 30)
        OutputNaming.outputFilename(template, "bai giang.m4a", at, at.plusHours(1), "gemini-3.5-flash-lite", extension)
    }

private val outputKeys = listOf(
    SettingKeys.FILENAME_TEMPLATE, SettingKeys.EXTENSION, SettingKeys.ON_CONFLICT, SettingKeys.ENCODING,
    SettingKeys.NEWLINE, SettingKeys.ADD_HEADER, SettingKeys.MARK_CHUNKS, SettingKeys.SAVE_SPEAKER_MAP,
)

/** Cài đặt → Bản chép lời (C4): các mục `[OUTPUT]` và dòng xem trước tên tệp. */
@Composable
fun OutputScreen(settings: Settings, onBack: () -> Unit) {
    val template by settings.flow(SettingKeys.FILENAME_TEMPLATE).collectAsState(initial = SettingKeys.FILENAME_TEMPLATE.default)
    val extension by settings.flow(SettingKeys.EXTENSION).collectAsState(initial = SettingKeys.EXTENSION.default)
    val invalid = stringResource(R.string.output_name_invalid)
    SettingsPage(stringResource(R.string.settings_output_title), onBack) {
        SectionCard(stringResource(R.string.output_file_section)) {
            TextSetting(
                settings, SettingKeys.FILENAME_TEMPLATE, stringResource(R.string.output_name_template),
                validate = { t -> if (t.isBlank() || OutputNaming.templateError(t) == null) null else invalid },
            )
            filenamePreview(template, extension)?.let { Hint(stringResource(R.string.output_name_preview, it)) }
            ChoiceSettingRow(
                settings, SettingKeys.ON_CONFLICT, stringResource(R.string.output_on_conflict),
                listOf(
                    "number" to stringResource(R.string.output_conflict_number),
                    "overwrite" to stringResource(R.string.output_conflict_overwrite),
                    "skip" to stringResource(R.string.output_conflict_skip),
                ),
            )
            TextSetting(settings, SettingKeys.EXTENSION, stringResource(R.string.output_extension))
            SwitchSetting(
                settings, SettingKeys.ADD_HEADER, stringResource(R.string.output_header),
                hint = stringResource(R.string.output_header_hint),
            )
        }
        AdvancedSection {
            SectionCard(null) {
                ChoiceSettingRow(
                    settings, SettingKeys.ENCODING, stringResource(R.string.output_encoding),
                    listOf("utf-8" to "UTF-8", "utf-8-sig" to stringResource(R.string.output_encoding_bom)),
                )
                ChoiceSettingRow(
                    settings, SettingKeys.NEWLINE, stringResource(R.string.output_newline),
                    listOf("crlf" to stringResource(R.string.output_newline_crlf), "lf" to stringResource(R.string.output_newline_lf)),
                )
                SwitchSetting(
                    settings, SettingKeys.MARK_CHUNKS, stringResource(R.string.output_chunk_markers),
                    hint = stringResource(R.string.output_chunk_markers_hint),
                )
                SwitchSetting(
                    settings, SettingKeys.SAVE_SPEAKER_MAP, stringResource(R.string.speakers_save_map),
                    hint = stringResource(R.string.output_save_map_hint),
                )
            }
            ResetDefaultsButton(settings, outputKeys)
        }
    }
}

/** Cài đặt → Hàng đợi (C4). */
@Composable
fun QueueSettingsScreen(settings: Settings, onBack: () -> Unit) {
    SettingsPage(stringResource(R.string.settings_queue_title), onBack) {
        SectionCard(null) {
            ChoiceSettingRow(
                settings, SettingKeys.ON_FILE_ERROR, stringResource(R.string.queue_on_error),
                listOf(
                    "continue" to stringResource(R.string.queue_on_error_continue),
                    "stop" to stringResource(R.string.queue_on_error_stop),
                ),
            )
            SwitchSetting(
                settings, SettingKeys.NOTIFY_WHEN_DONE, stringResource(R.string.queue_notify),
                hint = stringResource(R.string.queue_notify_hint),
            )
        }
        ResetDefaultsButton(settings, listOf(SettingKeys.ON_FILE_ERROR, SettingKeys.NOTIFY_WHEN_DONE))
    }
}

/** Một phần mềm / model đóng gói trong APK và tệp giấy phép của nó trong `assets/licenses/` (Stage 9 E3). */
private data class LicenseEntry(val name: String, val license: String, val file: String)

// Tên riêng và tên giấy phép, không dịch. Toàn văn: assets/licenses/ (Apache-2.0 dùng chung một tệp).
private val licenseEntries = listOf(
    LicenseEntry("Guzz Mobile", "MIT", "guzz-mobile.txt"),
    LicenseEntry("sherpa-onnx", "Apache-2.0", "apache-2.0.txt"),
    LicenseEntry("ONNX Runtime (© Microsoft)", "MIT", "onnxruntime.txt"),
    LicenseEntry("pyannote segmentation-3.0 (© 2022 CNRS)", "MIT", "pyannote-segmentation-3.0.txt"),
    LicenseEntry("3D-Speaker CAM++", "Apache-2.0", "apache-2.0.txt"),
    LicenseEntry("AndroidX, Jetpack Compose", "Apache-2.0", "apache-2.0.txt"),
    LicenseEntry("OkHttp, Okio", "Apache-2.0", "apache-2.0.txt"),
    LicenseEntry("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache-2.0", "apache-2.0.txt"),
)

private const val REPO_URL = "https://github.com/CozyCode890/GuzzMobile"

/**
 * Cài đặt → Thông tin (C5): phiên bản, bản phân phối, nơi để nhật ký. Stage 9 (E3, E4): link mã nguồn và trang
 * Releases (D126: app không tự kiểm bản mới), ghi chú quyền riêng tư, giấy phép bấm vào xem toàn văn.
 */
@Composable
fun AboutScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<LicenseEntry?>(null) }
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    SettingsPage(stringResource(R.string.about_title), onBack) {
        SectionCard(stringResource(R.string.app_name)) {
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            Text(
                stringResource(
                    R.string.about_flavor,
                    stringResource(if (BuildConfig.FLAVOR == "play") R.string.about_flavor_play else R.string.about_flavor_sideload),
                ),
            )
            Text(stringResource(R.string.about_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
            Hint(stringResource(R.string.about_log_path, container.logFile.parent.orEmpty()))
            Hint(stringResource(R.string.about_quota_note))
            Row {
                TextButton(onClick = { open(REPO_URL) }) { Text(stringResource(R.string.about_source)) }
                TextButton(onClick = { open("$REPO_URL/releases") }) { Text(stringResource(R.string.about_releases)) }
            }
        }
        SectionCard(stringResource(R.string.about_privacy)) {
            Text(stringResource(R.string.about_privacy_text))
        }
        SectionCard(stringResource(R.string.about_licenses)) {
            Hint(stringResource(R.string.about_licenses_hint))
            licenseEntries.forEach { entry ->
                Column(
                    Modifier.fillMaxWidth().clickable { shown = entry }.padding(vertical = 6.dp),
                ) {
                    Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                    Text(entry.license, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    shown?.let { entry -> LicenseDialog(entry) { shown = null } }
}

/** Toàn văn giấy phép đọc từ assets. */
@Composable
private fun LicenseDialog(entry: LicenseEntry, onClose: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("", entry.file) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/${entry.file}").bufferedReader().use { it.readText() } }
                .getOrDefault("")
                .let(::reflowParagraphs)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.about_license_close)) } },
        title = { Text(entry.name) },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
    )
}

/** Tệp giấy phép cắt dòng cứng ở ~80 ký tự: nối các dòng trong một đoạn để màn hình hẹp tự xuống dòng cho đẹp. */
private fun reflowParagraphs(text: String): String =
    text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        .joinToString("\n\n") { it.trim().replace(Regex("\\s+"), " ") }

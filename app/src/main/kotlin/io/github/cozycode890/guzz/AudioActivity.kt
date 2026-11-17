package io.github.cozycode890.guzz

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.core.common.settings.AudioPresetValues
import io.github.cozycode890.guzz.core.common.settings.AudioPresets
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.ui.theme.GuzzTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Trang Âm thanh rút gọn (D89): chọn preset (4 preset của desktop + Tuỳ chỉnh), bật / tắt từng bước làm sạch, lưu âm
 * thanh sạch. Ô số từng tham số của Tuỳ chỉnh: Stage 8. Mỗi thay đổi ghi ngay vào cài đặt, nên tệp sau trong hàng đợi
 * dùng giá trị mới (mã cache đổi theo, các đoạn được xử lý lại).
 */
class AudioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GuzzApp).container
        setContent { GuzzTheme { AudioScreen(container.settings) } }
    }
}

/** Bật cân bằng âm lượng / ghìm tiếng động từ trạng thái tắt thì lấy số của desktop (preset Giảng viên đi lại / Loa nhiều hướng). */
private const val DEFAULT_LEVEL_WINDOW_SEC = 3.0
private const val DEFAULT_TRANSIENT_CLAMP_DB = 15.0

/**
 * Preset đang dùng. Như `detect_preset` của desktop, cộng thêm: tắt khử ồn hay cắt lặng (hai mục preset không đặt) thì
 * coi là Tuỳ chỉnh, để "tắt một bước thì thành Tuỳ chỉnh" đúng với mọi công tắc trên trang (D89).
 */
fun currentPreset(values: AudioPresetValues, denoise: Boolean, trimSilence: Boolean): String =
    if (!denoise || !trimSilence) AudioPresets.CUSTOM else AudioPresets.detect(values)

/** Tên hiển thị của preset, cùng chữ với desktop (`audio_preset_*` của gui/i18n.py). */
fun presetLabel(context: Context, preset: String): String = context.getString(
    when (preset) {
        AudioPresets.NEAR_LECTURER -> R.string.audio_preset_near_lecturer
        AudioPresets.NEAR_SPEAKER -> R.string.audio_preset_near_speaker
        AudioPresets.MOVING_LECTURER -> R.string.audio_preset_moving
        AudioPresets.SPEAKERS_AROUND -> R.string.audio_preset_speakers_around
        else -> R.string.audio_preset_custom
    },
)

/** Preset hiện tại theo cài đặt, cập nhật khi cài đặt đổi (cho dòng "Âm thanh: …" ở màn hình Hàng đợi). */
fun presetFlow(settings: Settings): Flow<String> {
    val values: Flow<AudioPresetValues> = combine(
        listOf<Flow<Any>>(
            settings.flow(SettingKeys.TARGET_DBFS), settings.flow(SettingKeys.SILENCE_THRESH_OFFSET),
            settings.flow(SettingKeys.MIN_SILENCE_LEN), settings.flow(SettingKeys.KEEP_SILENCE),
            settings.flow(SettingKeys.NOISE_SAMPLE_SEC), settings.flow(SettingKeys.BANDPASS_LOW),
            settings.flow(SettingKeys.BANDPASS_HIGH), settings.flow(SettingKeys.PROP_DECREASE),
            settings.flow(SettingKeys.LEVEL_WINDOW_SEC), settings.flow(SettingKeys.TRANSIENT_CLAMP_DB),
        ),
    ) { v ->
        AudioPresetValues(
            v[0] as Double, v[1] as Int, v[2] as Int, v[3] as Int, v[4] as Double, v[5] as Int, v[6] as Int,
            v[7] as Double, v[8] as Double, v[9] as Double,
        )
    }
    return combine(values, settings.flow(SettingKeys.DENOISE), settings.flow(SettingKeys.TRIM_SILENCE)) { v, d, t ->
        currentPreset(v, d, t)
    }
}

@Composable
private fun AudioScreen(settings: Settings) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val preset by presetFlow(settings).collectAsState(initial = null)
    val denoise by settings.flow(SettingKeys.DENOISE).collectAsState(initial = true)
    val level by settings.flow(SettingKeys.LEVEL_WINDOW_SEC).collectAsState(initial = 0.0)
    val clamp by settings.flow(SettingKeys.TRANSIENT_CLAMP_DB).collectAsState(initial = 0.0)
    val trim by settings.flow(SettingKeys.TRIM_SILENCE).collectAsState(initial = true)
    val saveClean by settings.flow(SettingKeys.SAVE_CLEAN_AUDIO).collectAsState(initial = false)

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.audio_title), style = MaterialTheme.typography.headlineSmall)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.audio_preset), style = MaterialTheme.typography.titleMedium)
                    for (name in AudioPresets.all.keys + AudioPresets.CUSTOM) {
                        val isCustom = name == AudioPresets.CUSTOM
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = preset == name,
                                // Tuỳ chỉnh không có giá trị riêng để áp: chỉ hiện khi đã đổi một mục.
                                enabled = !isCustom,
                                onClick = {
                                    scope.launch {
                                        AudioPresets.all.getValue(name).applyTo(settings)
                                        // Preset của desktop luôn đi kèm khử ồn và cắt lặng.
                                        settings.set(SettingKeys.DENOISE, true)
                                        settings.set(SettingKeys.TRIM_SILENCE, true)
                                    }
                                },
                            ).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = preset == name, onClick = null, enabled = !isCustom || preset == name)
                            Text(presetLabel(context, name), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    Text(
                        stringResource(R.string.audio_preset_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.audio_steps), style = MaterialTheme.typography.titleMedium)
                    SwitchRow(stringResource(R.string.audio_denoise), denoise) { v ->
                        scope.launch { settings.set(SettingKeys.DENOISE, v) }
                    }
                    SwitchRow(stringResource(R.string.audio_level), level > 0) { v ->
                        scope.launch { settings.set(SettingKeys.LEVEL_WINDOW_SEC, if (v) DEFAULT_LEVEL_WINDOW_SEC else 0.0) }
                    }
                    SwitchRow(stringResource(R.string.audio_transient), clamp > 0) { v ->
                        scope.launch { settings.set(SettingKeys.TRANSIENT_CLAMP_DB, if (v) DEFAULT_TRANSIENT_CLAMP_DB else 0.0) }
                    }
                    SwitchRow(stringResource(R.string.audio_trim), trim) { v ->
                        scope.launch { settings.set(SettingKeys.TRIM_SILENCE, v) }
                    }
                    Text(
                        stringResource(R.string.audio_trim_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwitchRow(stringResource(R.string.audio_save_clean), saveClean) { v ->
                        scope.launch { settings.set(SettingKeys.SAVE_CLEAN_AUDIO, v) }
                    }
                    Text(
                        stringResource(R.string.audio_save_clean_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

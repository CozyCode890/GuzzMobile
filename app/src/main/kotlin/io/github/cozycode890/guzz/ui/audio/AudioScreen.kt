package io.github.cozycode890.guzz.ui.audio

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.settings.AudioPresetValues
import io.github.cozycode890.guzz.core.common.settings.AudioPresets
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.ui.components.AdvancedSection
import io.github.cozycode890.guzz.ui.components.ChoiceRow
import io.github.cozycode890.guzz.ui.components.Hint
import io.github.cozycode890.guzz.ui.components.NumberSetting
import io.github.cozycode890.guzz.ui.components.ResetDefaultsButton
import io.github.cozycode890.guzz.ui.components.SectionCard
import io.github.cozycode890.guzz.ui.components.SettingsPage
import io.github.cozycode890.guzz.ui.components.SwitchRow
import io.github.cozycode890.guzz.ui.components.SwitchSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Bật cân bằng âm lượng / ghìm tiếng động từ trạng thái tắt thì lấy số của desktop (preset Giảng viên đi lại / Loa nhiều hướng). */
private const val DEFAULT_LEVEL_WINDOW_SEC = 3.0
private const val DEFAULT_TRANSIENT_CLAMP_DB = 15.0

/** Số luồng âm thanh đưa ra lựa chọn; video có nhiều luồng hơn thì chỉnh trong tệp cài đặt (xuất / nhập). */
private const val TRACK_CHOICES = 4

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

/** Preset hiện tại theo cài đặt, cập nhật khi cài đặt đổi (cho dòng "Âm thanh: …" ở tab Chuyển đổi). */
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

/** Các mục của trang, cho nút Khôi phục mặc định. */
private val pageKeys = listOf(
    SettingKeys.TARGET_DBFS, SettingKeys.TRIM_SILENCE, SettingKeys.MIN_SILENCE_LEN, SettingKeys.SILENCE_THRESH_OFFSET,
    SettingKeys.KEEP_SILENCE, SettingKeys.DENOISE, SettingKeys.NOISE_SAMPLE_SEC, SettingKeys.BANDPASS_LOW,
    SettingKeys.BANDPASS_HIGH, SettingKeys.PROP_DECREASE, SettingKeys.LEVEL_WINDOW_SEC, SettingKeys.TRANSIENT_CLAMP_DB,
    SettingKeys.SAVE_CLEAN_AUDIO, SettingKeys.AUDIO_TRACK, SettingKeys.SKIP_SILENT, SettingKeys.SAVE_EXTRACTED,
    SettingKeys.CHUNK_MINUTES, SettingKeys.SEARCH_SECONDS, SettingKeys.MIN_LAST_CHUNK_SECONDS,
)

/**
 * Trang Âm thanh, video & cắt đoạn (Stage 7 D89, đủ mục ở Stage 8 C2): preset, bật / tắt từng bước, lưu âm thanh sạch,
 * video; Nâng cao: ô số `[AUDIO]` (sửa một ô thì preset thành Tuỳ chỉnh, như desktop) và `[CHUNKING]`. Mỗi thay đổi
 * ghi ngay vào cài đặt, nên tệp sau trong hàng đợi dùng giá trị mới (mã cache đổi theo, các đoạn được xử lý lại).
 */
@Composable
fun AudioScreen(settings: Settings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val preset by remember { presetFlow(settings) }.collectAsState(initial = null)
    val denoise by settings.flow(SettingKeys.DENOISE).collectAsState(initial = true)
    val level by settings.flow(SettingKeys.LEVEL_WINDOW_SEC).collectAsState(initial = 0.0)
    val clamp by settings.flow(SettingKeys.TRANSIENT_CLAMP_DB).collectAsState(initial = 0.0)
    val trim by settings.flow(SettingKeys.TRIM_SILENCE).collectAsState(initial = true)
    val track by settings.flow(SettingKeys.AUDIO_TRACK).collectAsState(initial = SettingKeys.AUDIO_TRACK.default)

    SettingsPage(stringResource(R.string.audio_title), onBack) {
        SectionCard(stringResource(R.string.audio_preset), hint = stringResource(R.string.audio_preset_hint)) {
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
        }

        SectionCard(stringResource(R.string.audio_steps)) {
            SwitchRow(stringResource(R.string.audio_denoise), denoise) { v -> scope.launch { settings.set(SettingKeys.DENOISE, v) } }
            SwitchRow(stringResource(R.string.audio_level), level > 0) { v ->
                scope.launch { settings.set(SettingKeys.LEVEL_WINDOW_SEC, if (v) DEFAULT_LEVEL_WINDOW_SEC else 0.0) }
            }
            SwitchRow(stringResource(R.string.audio_transient), clamp > 0) { v ->
                scope.launch { settings.set(SettingKeys.TRANSIENT_CLAMP_DB, if (v) DEFAULT_TRANSIENT_CLAMP_DB else 0.0) }
            }
            SwitchRow(stringResource(R.string.audio_trim), trim, hint = stringResource(R.string.audio_trim_hint)) { v ->
                scope.launch { settings.set(SettingKeys.TRIM_SILENCE, v) }
            }
            SwitchSetting(
                settings, SettingKeys.SAVE_CLEAN_AUDIO, stringResource(R.string.audio_save_clean),
                hint = stringResource(R.string.audio_save_clean_hint),
            )
        }

        SectionCard(stringResource(R.string.video_section), hint = stringResource(R.string.video_section_hint)) {
            val auto = SettingKeys.AUDIO_TRACK.default
            val trackOptions = listOf(auto to stringResource(R.string.video_track_auto)) +
                (0 until TRACK_CHOICES).map { it.toString() to stringResource(R.string.video_track_n, it + 1) }
            ChoiceRow(stringResource(R.string.video_track), track, trackOptions, hint = stringResource(R.string.video_track_hint)) { v ->
                scope.launch { settings.set(SettingKeys.AUDIO_TRACK, v) }
            }
            SwitchSetting(
                settings, SettingKeys.SKIP_SILENT, stringResource(R.string.video_skip_silent),
                hint = stringResource(R.string.video_skip_silent_hint),
            )
            SwitchSetting(
                settings, SettingKeys.SAVE_EXTRACTED, stringResource(R.string.video_save_extracted),
                hint = stringResource(R.string.video_save_extracted_hint),
            )
        }

        AdvancedSection {
            SectionCard(stringResource(R.string.audio_advanced), hint = stringResource(R.string.audio_advanced_hint)) {
                NumberSetting(settings, SettingKeys.TARGET_DBFS, stringResource(R.string.audio_target_dbfs))
                NumberSetting(settings, SettingKeys.SILENCE_THRESH_OFFSET, stringResource(R.string.audio_silence_offset))
                NumberSetting(settings, SettingKeys.MIN_SILENCE_LEN, stringResource(R.string.audio_min_silence))
                NumberSetting(settings, SettingKeys.KEEP_SILENCE, stringResource(R.string.audio_keep_silence))
                NumberSetting(settings, SettingKeys.NOISE_SAMPLE_SEC, stringResource(R.string.audio_noise_sample))
                NumberSetting(settings, SettingKeys.BANDPASS_LOW, stringResource(R.string.audio_bandpass_low))
                NumberSetting(settings, SettingKeys.BANDPASS_HIGH, stringResource(R.string.audio_bandpass_high))
                NumberSetting(settings, SettingKeys.PROP_DECREASE, stringResource(R.string.audio_prop_decrease))
                NumberSetting(settings, SettingKeys.LEVEL_WINDOW_SEC, stringResource(R.string.audio_level_window))
                NumberSetting(settings, SettingKeys.TRANSIENT_CLAMP_DB, stringResource(R.string.audio_transient_db))
            }
            SectionCard(stringResource(R.string.audio_chunk_section), hint = stringResource(R.string.audio_chunk_hint)) {
                NumberSetting(settings, SettingKeys.CHUNK_MINUTES, stringResource(R.string.audio_chunk_minutes))
                NumberSetting(settings, SettingKeys.SEARCH_SECONDS, stringResource(R.string.audio_chunk_search))
                NumberSetting(settings, SettingKeys.MIN_LAST_CHUNK_SECONDS, stringResource(R.string.audio_chunk_last_min))
            }
            Hint(stringResource(R.string.audio_custom_note), Modifier.padding(horizontal = 4.dp))
            ResetDefaultsButton(settings, pageKeys)
        }
    }
}


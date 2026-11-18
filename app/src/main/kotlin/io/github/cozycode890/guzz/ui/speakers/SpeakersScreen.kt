package io.github.cozycode890.guzz.ui.speakers

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.core.gemini.DiarizationMethod
import io.github.cozycode890.guzz.core.gemini.closestAlternativeMethod
import io.github.cozycode890.guzz.core.gemini.isTranscribeModel
import io.github.cozycode890.guzz.core.gemini.validDiarizationMethods
import io.github.cozycode890.guzz.core.speaker.Paragraph
import io.github.cozycode890.guzz.core.speaker.Speakers
import io.github.cozycode890.guzz.core.gemini.PromptKind
import io.github.cozycode890.guzz.core.pipeline.SpeakerTranscript
import io.github.cozycode890.guzz.ui.components.AdvancedSection
import io.github.cozycode890.guzz.ui.components.PromptEditor
import io.github.cozycode890.guzz.ui.components.ChoiceSettingRow
import io.github.cozycode890.guzz.ui.components.Hint
import io.github.cozycode890.guzz.ui.components.NumberSetting
import io.github.cozycode890.guzz.ui.components.ResetDefaultsButton
import io.github.cozycode890.guzz.ui.components.SectionCard
import io.github.cozycode890.guzz.ui.components.SettingsPage
import io.github.cozycode890.guzz.ui.components.SwitchRow
import io.github.cozycode890.guzz.ui.components.SwitchSetting
import io.github.cozycode890.guzz.ui.components.TextSetting
import kotlinx.coroutines.launch

/** Tên hiển thị của cách nhận diện (D71); giá trị cài đặt giữ như desktop. */
fun methodLabel(context: Context, method: String): String = context.getString(
    when (method) {
        DiarizationMethod.PYANNOTE -> R.string.speakers_method_pyannote
        DiarizationMethod.GEMINI -> R.string.speakers_method_gemini
        else -> R.string.speakers_method_hybrid
    },
)

/**
 * Sau khi đổi model: cách đang chọn không hợp model mới thì chuyển sang cách gần nhất (E5, như desktop) và trả câu báo
 * cho giao diện; `null` nếu không phải đổi.
 */
suspend fun switchMethodForModel(context: Context, settings: Settings, model: String): String? {
    if (!settings.get(SettingKeys.SPEAKERS_ENABLED)) return null
    val method = settings.get(SettingKeys.SPEAKERS_METHOD)
    val next = closestAlternativeMethod(model, method)
    if (next == method) return null
    settings.set(SettingKeys.SPEAKERS_METHOD, next)
    return context.getString(R.string.speakers_switched, methodLabel(context, next), model, methodLabel(context, method))
}

/**
 * Xem trước mẫu đoạn văn bằng chính hàm ghép của bản chép lời (`Speakers.formatParagraph`, port của speaker.py), như
 * trang Người nói của desktop; `null` khi mẫu sai (biến lạ, ngoặc lẻ).
 */
fun previewParagraph(template: String, speaker: String, sample: String, timestamps: Boolean): String? = try {
    Speakers.formatParagraph(Paragraph(sample, speaker, 754.0), template, timestamps, markReview = false)
} catch (_: IllegalArgumentException) {
    null
}

private val pageKeys = listOf(
    SettingKeys.SPEAKERS_ENABLED, SettingKeys.SPEAKERS_METHOD, SettingKeys.NUM_SPEAKERS, SettingKeys.MIN_SPEAKERS,
    SettingKeys.MAX_SPEAKERS, SettingKeys.RUN_ON, SettingKeys.SMOOTH, SettingKeys.SANDWICH_MAX_SEC,
    SettingKeys.SANDWICH_GAP_SEC, SettingKeys.DENSITY_WINDOW_SEC, SettingKeys.DENSITY_MIN_SEC, SettingKeys.MERGE_GAP_SEC,
    SettingKeys.LABEL_MAIN_SPEAKER, SettingKeys.MAIN_LABEL, SettingKeys.OTHER_LABEL, SettingKeys.GENERIC_LABEL,
    SettingKeys.MAP_DELIVERY, SettingKeys.SPEAKER_TIMESTAMPS, SettingKeys.PARAGRAPH_TEMPLATE,
    SettingKeys.MAX_PARAGRAPH_SEC, SettingKeys.PARAGRAPH_GAP_SEC, SettingKeys.MARK_REVIEW, SettingKeys.SAVE_SPEAKER_MAP,
)

/**
 * Trang Người nói (Stage 5 D76, đủ mục ở Stage 8 C3): bật / tắt, cách (khoá cách không hợp model, D71), số người (D80
 * khuyên nhập), tên, mốc thời gian, cách gửi bản đồ; Nâng cao: làm mượt, phân tích trên âm thanh nào, mẫu đoạn văn.
 */
@Composable
fun SpeakersScreen(settings: Settings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val enabled by settings.flow(SettingKeys.SPEAKERS_ENABLED).collectAsState(initial = false)
    val method by settings.flow(SettingKeys.SPEAKERS_METHOD).collectAsState(initial = DiarizationMethod.PYANNOTE)
    val model by settings.flow(SettingKeys.MODEL).collectAsState(initial = "")
    val labelMain by settings.flow(SettingKeys.LABEL_MAIN_SPEAKER).collectAsState(initial = true)
    val mainLabel by settings.flow(SettingKeys.MAIN_LABEL).collectAsState(initial = SettingKeys.MAIN_LABEL.default)
    val genericLabel by settings.flow(SettingKeys.GENERIC_LABEL).collectAsState(initial = SettingKeys.GENERIC_LABEL.default)
    val timestamps by settings.flow(SettingKeys.SPEAKER_TIMESTAMPS).collectAsState(initial = true)
    val template by settings.flow(SettingKeys.PARAGRAPH_TEMPLATE).collectAsState(initial = SettingKeys.PARAGRAPH_TEMPLATE.default)
    val valid = validDiarizationMethods(model)
    val sample = stringResource(R.string.speakers_template_sample)
    val invalidTemplate = stringResource(R.string.speakers_template_invalid)

    SettingsPage(stringResource(R.string.speakers_title), onBack) {
        SectionCard(null) {
            SwitchRow(stringResource(R.string.speakers_enabled), enabled) { v ->
                scope.launch {
                    settings.set(SettingKeys.SPEAKERS_ENABLED, v)
                    // Bật lên mà cách đang lưu không hợp model: chuyển luôn, như khi đổi model.
                    if (v) settings.set(SettingKeys.SPEAKERS_METHOD, closestAlternativeMethod(model, method))
                }
            }
        }

        SectionCard(stringResource(R.string.speakers_method)) {
            for (m in listOf(DiarizationMethod.PYANNOTE, DiarizationMethod.GEMINI, DiarizationMethod.HYBRID)) {
                val ok = m in valid
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = method == m, enabled = ok && enabled,
                        onClick = { scope.launch { settings.set(SettingKeys.SPEAKERS_METHOD, m) } },
                    ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = method == m, onClick = null, enabled = ok && enabled)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(methodLabel(context, m))
                        if (!ok) {
                            Hint(
                                stringResource(
                                    if (isTranscribeModel(model)) R.string.speakers_locked_transcribe else R.string.speakers_locked_general,
                                    model,
                                ),
                            )
                        }
                    }
                }
            }
            if (isTranscribeModel(model)) Hint(stringResource(R.string.speakers_transcribe_limits))
        }

        SectionCard(stringResource(R.string.speakers_count), hint = stringResource(R.string.speakers_count_hint)) {
            NumberSetting(settings, SettingKeys.NUM_SPEAKERS, stringResource(R.string.speakers_num))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberSetting(settings, SettingKeys.MIN_SPEAKERS, stringResource(R.string.speakers_min), Modifier.weight(1f))
                NumberSetting(settings, SettingKeys.MAX_SPEAKERS, stringResource(R.string.speakers_max), Modifier.weight(1f))
            }
        }

        SectionCard(stringResource(R.string.speakers_names_section)) {
            SwitchSetting(
                settings, SettingKeys.LABEL_MAIN_SPEAKER, stringResource(R.string.speakers_label_main),
                hint = stringResource(R.string.speakers_label_main_hint),
            )
            TextSetting(settings, SettingKeys.MAIN_LABEL, stringResource(R.string.speakers_main_label), enabled = labelMain)
            TextSetting(settings, SettingKeys.OTHER_LABEL, stringResource(R.string.speakers_other_label), enabled = labelMain)
            TextSetting(settings, SettingKeys.GENERIC_LABEL, stringResource(R.string.speakers_generic_label))
        }

        SectionCard(stringResource(R.string.speakers_output_section)) {
            SwitchSetting(
                settings, SettingKeys.SPEAKER_TIMESTAMPS, stringResource(R.string.speakers_timestamps),
                hint = stringResource(R.string.speakers_timestamps_hint),
            )
            ChoiceSettingRow(
                settings, SettingKeys.MAP_DELIVERY, stringResource(R.string.speakers_map_delivery),
                listOf(
                    "file" to stringResource(R.string.speakers_map_delivery_file),
                    "text" to stringResource(R.string.speakers_map_delivery_text),
                ),
            )
            SwitchSetting(settings, SettingKeys.SAVE_SPEAKER_MAP, stringResource(R.string.speakers_save_map))
        }

        AdvancedSection {
            PromptEditor(
                PromptKind.DIARIZATION, stringResource(R.string.prompt_diarization_title),
                stringResource(R.string.prompt_diarization_hint),
                lockedReason = if (isTranscribeModel(model)) stringResource(R.string.prompt_locked_transcribe, model) else null,
                requiredText = SpeakerTranscript.SPEAKERS_PLACEHOLDERS.first(),
            )
            SectionCard(stringResource(R.string.speakers_smooth_section), hint = stringResource(R.string.speakers_smooth_hint)) {
                SwitchSetting(settings, SettingKeys.SMOOTH, stringResource(R.string.speakers_smooth))
                NumberSetting(settings, SettingKeys.SANDWICH_MAX_SEC, stringResource(R.string.speakers_sandwich_max))
                NumberSetting(settings, SettingKeys.SANDWICH_GAP_SEC, stringResource(R.string.speakers_sandwich_gap))
                NumberSetting(settings, SettingKeys.DENSITY_WINDOW_SEC, stringResource(R.string.speakers_density_window))
                NumberSetting(settings, SettingKeys.DENSITY_MIN_SEC, stringResource(R.string.speakers_density_min))
                NumberSetting(settings, SettingKeys.MERGE_GAP_SEC, stringResource(R.string.speakers_merge_gap))
            }
            SectionCard(stringResource(R.string.speakers_paragraph_section)) {
                ChoiceSettingRow(
                    settings, SettingKeys.RUN_ON, stringResource(R.string.speakers_run_on),
                    listOf(
                        "clean" to stringResource(R.string.speakers_run_on_clean),
                        "original" to stringResource(R.string.speakers_run_on_original),
                    ),
                )
                TextSetting(
                    settings, SettingKeys.PARAGRAPH_TEMPLATE, stringResource(R.string.speakers_paragraph_template),
                    validate = { t -> if (t.isBlank() || previewParagraph(t, "A", "x", true) != null) null else invalidTemplate },
                )
                val speakerName = if (labelMain) mainLabel else "$genericLabel 1"
                previewParagraph(template, speakerName, sample, timestamps)?.let {
                    Text(
                        stringResource(R.string.speakers_template_preview, it),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NumberSetting(settings, SettingKeys.MAX_PARAGRAPH_SEC, stringResource(R.string.speakers_para_max))
                NumberSetting(settings, SettingKeys.PARAGRAPH_GAP_SEC, stringResource(R.string.speakers_para_gap))
                SwitchSetting(
                    settings, SettingKeys.MARK_REVIEW, stringResource(R.string.speakers_mark_review),
                    hint = stringResource(R.string.speakers_mark_review_hint),
                )
            }
            ResetDefaultsButton(settings, pageKeys)
        }
    }
}

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.core.common.settings.IntSetting
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.core.common.settings.StringSetting
import io.github.cozycode890.guzz.core.gemini.DiarizationMethod
import io.github.cozycode890.guzz.core.gemini.closestAlternativeMethod
import io.github.cozycode890.guzz.core.gemini.isTranscribeModel
import io.github.cozycode890.guzz.core.gemini.validDiarizationMethods
import io.github.cozycode890.guzz.ui.theme.GuzzTheme
import kotlinx.coroutines.launch

/**
 * Trang Người nói rút gọn (D76): bật / tắt, chọn cách (khoá cách không hợp model, D71), số người hoặc min–max (D80 khuyên
 * nhập số người), tên giảng viên / người hỏi, làm mượt, lưu bản đồ. Ngưỡng, mẫu đoạn văn, prompt: Stage 8. Mỗi thay đổi
 * ghi ngay vào cài đặt, nên tệp sau trong hàng đợi dùng giá trị mới.
 */
class SpeakersActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GuzzApp).container
        setContent { GuzzTheme { SpeakersScreen(container.settings) } }
    }
}

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

@Composable
private fun SpeakersScreen(settings: Settings) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val enabled by settings.flow(SettingKeys.SPEAKERS_ENABLED).collectAsState(initial = false)
    val method by settings.flow(SettingKeys.SPEAKERS_METHOD).collectAsState(initial = DiarizationMethod.PYANNOTE)
    val model by settings.flow(SettingKeys.MODEL).collectAsState(initial = "")
    val smooth by settings.flow(SettingKeys.SMOOTH).collectAsState(initial = true)
    val saveMap by settings.flow(SettingKeys.SAVE_SPEAKER_MAP).collectAsState(initial = false)
    val valid = validDiarizationMethods(model)

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.speakers_title), style = MaterialTheme.typography.headlineSmall)
            SwitchRow(stringResource(R.string.speakers_enabled), enabled) { v ->
                scope.launch {
                    settings.set(SettingKeys.SPEAKERS_ENABLED, v)
                    // Bật lên mà cách đang lưu không hợp model: chuyển luôn, như khi đổi model.
                    if (v) settings.set(SettingKeys.SPEAKERS_METHOD, closestAlternativeMethod(model, method))
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.speakers_method), style = MaterialTheme.typography.titleMedium)
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
                                    Text(
                                        stringResource(
                                            if (isTranscribeModel(model)) R.string.speakers_locked_transcribe
                                            else R.string.speakers_locked_general,
                                            model,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.speakers_count), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.speakers_count_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IntField(settings, SettingKeys.NUM_SPEAKERS, stringResource(R.string.speakers_num), Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntField(settings, SettingKeys.MIN_SPEAKERS, stringResource(R.string.speakers_min), Modifier.weight(1f))
                        IntField(settings, SettingKeys.MAX_SPEAKERS, stringResource(R.string.speakers_max), Modifier.weight(1f))
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextSettingField(settings, SettingKeys.MAIN_LABEL, stringResource(R.string.speakers_main_label))
                    TextSettingField(settings, SettingKeys.OTHER_LABEL, stringResource(R.string.speakers_other_label))
                    SwitchRow(stringResource(R.string.speakers_smooth), smooth) { v ->
                        scope.launch { settings.set(SettingKeys.SMOOTH, v) }
                    }
                    SwitchRow(stringResource(R.string.speakers_save_map), saveMap) { v ->
                        scope.launch { settings.set(SettingKeys.SAVE_SPEAKER_MAP, v) }
                    }
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

/** Ô số nguyên: ghi khi là số hợp lệ (cài đặt tự chặn dưới như `load_config`). */
@Composable
private fun IntField(settings: Settings, key: IntSetting, label: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val value by settings.flow(key).collectAsState(initial = null)
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() }.take(2)
            text.toIntOrNull()?.let { scope.launch { settings.set(key, it) } }
        },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun TextSettingField(settings: Settings, key: StringSetting, label: String) {
    val scope = rememberCoroutineScope()
    val value by settings.flow(key).collectAsState(initial = null)
    var text by remember(value) { mutableStateOf(value ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            if (t.isNotBlank()) scope.launch { settings.set(key, t) }
        },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

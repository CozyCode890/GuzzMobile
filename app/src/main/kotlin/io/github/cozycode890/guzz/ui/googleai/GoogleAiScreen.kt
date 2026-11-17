package io.github.cozycode890.guzz.ui.googleai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.AppContainer
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.gemini.ErrorKind
import io.github.cozycode890.guzz.core.gemini.GoogleAIException
import io.github.cozycode890.guzz.core.gemini.isTranscribeModel
import io.github.cozycode890.guzz.core.gemini.isValidModelName
import io.github.cozycode890.guzz.core.gemini.maskApiKey
import io.github.cozycode890.guzz.ui.components.AdvancedSection
import io.github.cozycode890.guzz.ui.components.ChoiceSettingRow
import io.github.cozycode890.guzz.ui.components.Hint
import io.github.cozycode890.guzz.ui.components.NumberSetting
import io.github.cozycode890.guzz.ui.components.ResetDefaultsButton
import io.github.cozycode890.guzz.ui.components.SectionCard
import io.github.cozycode890.guzz.ui.components.SettingsPage
import io.github.cozycode890.guzz.ui.components.SwitchSetting
import io.github.cozycode890.guzz.ui.components.TextSetting
import io.github.cozycode890.guzz.ui.speakers.switchMethodForModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kết quả lần Kiểm tra kết nối gần nhất. */
private sealed interface CheckState {
    data object Idle : CheckState
    data object Running : CheckState
    data class Ok(val models: List<String>) : CheckState
    data class Failed(val kind: ErrorKind?, val message: String) : CheckState
}

/** Gợi ý `languages` cho model `*-transcribe` (D119): thiếu mục này thì chữ rất thưa trên bài lẫn Việt / Anh (Stage 3). */
const val SUGGESTED_LANGUAGES = "vi-VN, en-US"

/** Hiện gợi ý điền [SUGGESTED_LANGUAGES] khi nào (D119). */
fun shouldSuggestLanguages(model: String, languages: String): Boolean = isTranscribeModel(model) && languages.isBlank()

/** Các mục của trang này, cho nút Khôi phục mặc định (API key và model chính không nằm trong đây). */
private val pageKeys = listOf(
    SettingKeys.TRANSCRIBE_MODE, SettingKeys.LANGUAGES, SettingKeys.VOCABULARY, SettingKeys.STORE_ON_SERVER,
    SettingKeys.TOKEN_RATE_LIMIT, SettingKeys.TIMEOUT_SECONDS, SettingKeys.CHUNK_RETRIES, SettingKeys.DELETE_REMOTE_FILE,
    SettingKeys.FALLBACK_ENABLED, SettingKeys.FALLBACK_MODELS, SettingKeys.RETRIES_BEFORE_SWITCH,
    SettingKeys.OVERLOAD_LOCK_MINUTES, SettingKeys.RETURN_TO_MAIN_MODEL, SettingKeys.SWITCH_WAIT_THRESHOLD_SEC,
    SettingKeys.MAX_ALL_LOCKED_WAIT_MIN, SettingKeys.PROACTIVE_DAILY_LOCK,
)

/**
 * Trang Google AI (Stage 3 D56, đủ mục ở Stage 8 C1): API key (đầu trang, cách xa ô model — Phát sinh Stage 3), Kiểm
 * tra kết nối, model chính, chế độ, ngôn ngữ (+ gợi ý D119), thuật ngữ; Nâng cao: gửi yêu cầu và Tự đổi model.
 */
@Composable
fun GoogleAiScreen(container: AppContainer, onBack: () -> Unit) {
    val settings = container.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedMasked by remember { mutableStateOf<String?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var check by remember { mutableStateOf<CheckState>(CheckState.Idle) }
    val model by settings.flow(SettingKeys.MODEL).collectAsState(initial = "")
    val languages by settings.flow(SettingKeys.LANGUAGES).collectAsState(initial = "")
    var modelInput by remember { mutableStateOf("") }
    var modelNote by remember { mutableStateOf<String?>(null) }
    val invalidModelMessage = stringResource(R.string.google_ai_model_invalid)

    LaunchedEffect(Unit) {
        savedMasked = withContext(Dispatchers.IO) { container.apiKeyStore.read()?.let(::maskApiKey) }
    }
    LaunchedEffect(model) { modelInput = model }

    fun setModel(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        if (!isValidModelName(trimmed)) {
            // Không lưu, không ghi log chuỗi này: có thể là API key dán nhầm ô.
            modelInput = ""
            modelNote = invalidModelMessage
            return
        }
        modelNote = null
        scope.launch {
            settings.set(SettingKeys.MODEL, trimmed)
            GuzzLog.info("Trang Google AI: đổi model chính sang $trimmed")
            modelNote = switchMethodForModel(context, settings, trimmed)
        }
    }

    SettingsPage(stringResource(R.string.google_ai_title), onBack) {
        // ---- API key
        SectionCard(stringResource(R.string.google_ai_key_section), hint = stringResource(R.string.google_ai_key_hint)) {
            Text(
                savedMasked?.let { stringResource(R.string.google_ai_key_saved, it) }
                    ?: stringResource(R.string.google_ai_key_none),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(stringResource(R.string.google_ai_key_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = keyInput.isNotBlank(),
                    onClick = {
                        val key = keyInput
                        keyInput = ""
                        scope.launch {
                            withContext(Dispatchers.IO) { container.apiKeyStore.save(key) }
                            savedMasked = maskApiKey(key.trim())
                            check = CheckState.Idle
                            GuzzLog.info("Trang Google AI: đã lưu API key ${maskApiKey(key.trim())}")
                        }
                    },
                ) { Text(stringResource(R.string.google_ai_save)) }
                OutlinedButton(
                    enabled = savedMasked != null,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { container.apiKeyStore.clear() }
                            savedMasked = null
                            check = CheckState.Idle
                            GuzzLog.info("Trang Google AI: đã xoá API key")
                        }
                    },
                ) { Text(stringResource(R.string.google_ai_delete)) }
            }
            FilledTonalButton(
                enabled = savedMasked != null && check != CheckState.Running,
                onClick = {
                    check = CheckState.Running
                    scope.launch { check = checkConnection(container) }
                },
            ) { Text(stringResource(R.string.google_ai_check)) }
            when (val c = check) {
                CheckState.Idle -> Unit
                CheckState.Running -> Text(stringResource(R.string.google_ai_checking))
                is CheckState.Ok -> Text(
                    if (model.isNotBlank() && model !in c.models) stringResource(R.string.google_ai_check_model_missing, model)
                    else stringResource(R.string.google_ai_check_ok, c.models.size),
                    color = MaterialTheme.colorScheme.primary,
                )
                is CheckState.Failed -> Text(
                    stringResource(errorTitle(c.kind)) + "\n" + c.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ---- Model và cách chép lời
        SectionCard(stringResource(R.string.google_ai_model_section), hint = stringResource(R.string.google_ai_model_hint)) {
            Text(stringResource(R.string.google_ai_model_current, model), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it },
                    label = { Text(stringResource(R.string.google_ai_model_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = modelInput.isNotBlank() && modelInput.trim() != model,
                    onClick = { setModel(modelInput) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.google_ai_model_use)) }
            }
            modelNote?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Hint(stringResource(if (isTranscribeModel(model)) R.string.google_ai_rb_transcribe else R.string.google_ai_rb_general))
            val models = (check as? CheckState.Ok)?.models.orEmpty()
            if (models.isNotEmpty()) {
                Text(stringResource(R.string.google_ai_model_pick), style = MaterialTheme.typography.labelLarge)
                // Danh sách có thể dài (vài chục model): cuộn riêng trong khung, trang ngoài vẫn cuộn bình thường.
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(models) { name ->
                        Text(
                            name,
                            fontWeight = if (name == model) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { setModel(name) }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
            HorizontalDivider()
            ChoiceSettingRow(
                settings, SettingKeys.TRANSCRIBE_MODE, stringResource(R.string.google_ai_mode),
                listOf(
                    "smart" to stringResource(R.string.google_ai_mode_smart),
                    "verbatim" to stringResource(R.string.google_ai_mode_verbatim),
                ),
            )
            TextSetting(
                settings, SettingKeys.LANGUAGES, stringResource(R.string.google_ai_languages), allowBlank = true,
                hint = stringResource(R.string.google_ai_languages_hint),
            )
            if (shouldSuggestLanguages(model, languages)) {
                Column(Modifier.testTag("languages_suggestion"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Hint(stringResource(R.string.google_ai_languages_suggest, SUGGESTED_LANGUAGES))
                    AssistChip(
                        onClick = { scope.launch { settings.set(SettingKeys.LANGUAGES, SUGGESTED_LANGUAGES) } },
                        label = { Text(stringResource(R.string.google_ai_languages_fill, SUGGESTED_LANGUAGES)) },
                    )
                }
            }
            TextSetting(settings, SettingKeys.VOCABULARY, stringResource(R.string.google_ai_vocabulary), allowBlank = true, singleLine = false)
        }

        AdvancedSection {
            SectionCard(stringResource(R.string.google_ai_request_section)) {
                SwitchSetting(settings, SettingKeys.STORE_ON_SERVER, stringResource(R.string.google_ai_store))
                SwitchSetting(settings, SettingKeys.DELETE_REMOTE_FILE, stringResource(R.string.google_ai_delete_remote))
                NumberSetting(settings, SettingKeys.TIMEOUT_SECONDS, stringResource(R.string.google_ai_timeout))
                NumberSetting(settings, SettingKeys.CHUNK_RETRIES, stringResource(R.string.google_ai_retries))
                NumberSetting(
                    settings, SettingKeys.TOKEN_RATE_LIMIT, stringResource(R.string.google_ai_token_limit),
                    hint = stringResource(R.string.google_ai_token_limit_hint),
                )
            }
            SectionCard(stringResource(R.string.google_ai_fallback_section), hint = stringResource(R.string.google_ai_fallback_hint)) {
                SwitchSetting(settings, SettingKeys.FALLBACK_ENABLED, stringResource(R.string.google_ai_fallback_enable))
                TextSetting(
                    settings, SettingKeys.FALLBACK_MODELS, stringResource(R.string.google_ai_fallback_models), singleLine = false,
                    validate = { text ->
                        val bad = text.split(',').map { it.trim() }.filter { it.isNotEmpty() && !isValidModelName(it) }
                        if (bad.isEmpty()) null else context.getString(R.string.google_ai_fallback_models_invalid, bad.joinToString(", "))
                    },
                )
                NumberSetting(settings, SettingKeys.RETRIES_BEFORE_SWITCH, stringResource(R.string.google_ai_fallback_retries))
                NumberSetting(settings, SettingKeys.OVERLOAD_LOCK_MINUTES, stringResource(R.string.google_ai_fallback_overload_lock))
                SwitchSetting(
                    settings, SettingKeys.RETURN_TO_MAIN_MODEL, stringResource(R.string.google_ai_fallback_return),
                    hint = stringResource(R.string.google_ai_fallback_return_hint),
                )
                NumberSetting(settings, SettingKeys.SWITCH_WAIT_THRESHOLD_SEC, stringResource(R.string.google_ai_fallback_wait_switch))
                NumberSetting(settings, SettingKeys.MAX_ALL_LOCKED_WAIT_MIN, stringResource(R.string.google_ai_fallback_max_wait))
                SwitchSetting(settings, SettingKeys.PROACTIVE_DAILY_LOCK, stringResource(R.string.google_ai_fallback_proactive))
            }
            ResetDefaultsButton(settings, pageKeys)
        }
    }
}

/** Gọi `list_models` với key đã lưu. Model sắp xếp: gemini trước, theo tên. */
private suspend fun checkConnection(container: AppContainer): CheckState {
    val client = withContext(Dispatchers.IO) { container.geminiClient() }
        ?: return CheckState.Failed(ErrorKind.CONFIG, "")
    return try {
        val models = client.listModels().sortedWith(compareBy({ !it.startsWith("gemini") }, { it }))
        GuzzLog.info("Trang Google AI: kiểm tra kết nối được, ${models.size} model")
        CheckState.Ok(models)
    } catch (e: GoogleAIException) {
        GuzzLog.warning("Trang Google AI: kiểm tra kết nối lỗi ${e.kind.code}: ${e.message}")
        CheckState.Failed(e.kind, e.message)
    }
}

private fun errorTitle(kind: ErrorKind?): Int = when (kind) {
    ErrorKind.CONFIG -> R.string.google_ai_error_config
    ErrorKind.TEMPORARY -> R.string.google_ai_error_network
    ErrorKind.QUOTA -> R.string.google_ai_error_quota
    ErrorKind.OVERLOAD -> R.string.google_ai_error_overload
    else -> R.string.google_ai_error_other
}

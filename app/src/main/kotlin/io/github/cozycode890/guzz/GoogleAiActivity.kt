package io.github.cozycode890.guzz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.gemini.ErrorKind
import io.github.cozycode890.guzz.core.gemini.GoogleAIException
import io.github.cozycode890.guzz.core.gemini.isValidModelName
import io.github.cozycode890.guzz.core.gemini.maskApiKey
import io.github.cozycode890.guzz.ui.theme.GuzzTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Trang Google AI (D56): nhập API key (lưu mã hoá, hiện dạng che), Kiểm tra kết nối (`list_models`), chọn model chính.
 * Bản tối thiểu của trang Google AI desktop; đủ các mục để Stage 8.
 */
class GoogleAiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GuzzApp).container
        setContent {
            GuzzTheme {
                GoogleAiScreen(container)
            }
        }
    }
}

/** Kết quả lần Kiểm tra kết nối gần nhất. */
private sealed interface CheckState {
    data object Idle : CheckState
    data object Running : CheckState
    data class Ok(val models: List<String>) : CheckState
    data class Failed(val kind: ErrorKind?, val message: String) : CheckState
}

@Composable
private fun GoogleAiScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var savedMasked by remember { mutableStateOf<String?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var check by remember { mutableStateOf<CheckState>(CheckState.Idle) }
    val model by container.settings.flow(SettingKeys.MODEL).collectAsState(initial = "")
    var modelInput by remember { mutableStateOf("") }
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
            check = CheckState.Failed(null, invalidModelMessage)
            return
        }
        scope.launch {
            container.settings.set(SettingKeys.MODEL, trimmed)
            GuzzLog.info("Trang Google AI: đổi model chính sang $trimmed")
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.google_ai_title), style = MaterialTheme.typography.headlineSmall)

            // ---- API key
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

            // ---- Kiểm tra kết nối
            Button(
                enabled = savedMasked != null && check != CheckState.Running,
                onClick = {
                    check = CheckState.Running
                    scope.launch {
                        check = checkConnection(container)
                    }
                },
            ) { Text(stringResource(R.string.google_ai_check)) }
            when (val c = check) {
                CheckState.Idle -> Unit
                CheckState.Running -> Text(stringResource(R.string.google_ai_checking))
                is CheckState.Ok -> Text(
                    stringResource(R.string.google_ai_check_ok, c.models.size),
                    color = MaterialTheme.colorScheme.primary,
                )
                is CheckState.Failed -> Text(
                    stringResource(errorTitle(c.kind)) + "\n" + c.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()

            // ---- Model chính
            Text(stringResource(R.string.google_ai_model_current, model), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
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
            val models = (check as? CheckState.Ok)?.models.orEmpty()
            if (models.isNotEmpty()) {
                Text(stringResource(R.string.google_ai_model_pick), style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.weight(1f)) {
                    items(models) { name ->
                        Text(
                            name,
                            fontWeight = if (name == model) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { setModel(name) }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
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

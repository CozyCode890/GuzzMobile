package io.github.cozycode890.guzz.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.AppContainer
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.gemini.maskApiKey
import io.github.cozycode890.guzz.core.remote.Reasons
import io.github.cozycode890.guzz.core.remote.RemoteUnavailableException
import io.github.cozycode890.guzz.ui.components.SectionCard
import io.github.cozycode890.guzz.ui.components.SettingsPage
import io.github.cozycode890.guzz.ui.components.SwitchSetting
import io.github.cozycode890.guzz.ui.components.TextSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ServerCheck {
    data object Idle : ServerCheck
    data object Running : ServerCheck
    data class Ok(val version: String) : ServerCheck
    data class Failed(val reason: String, val message: String) : ServerCheck
}

/**
 * Cài đặt → Server xử lý (Stage 11 F2): công tắc, URL, token (lưu Keystore, chỉ hiện dạng che), nút Kiểm tra kết nối
 * (`GET /health`), và một dòng giải thích đường dự phòng (D145).
 */
@Composable
fun ServerScreen(container: AppContainer, onBack: () -> Unit) {
    val settings = container.settings
    val scope = rememberCoroutineScope()
    var savedMasked by remember { mutableStateOf<String?>(null) }
    var tokenInput by remember { mutableStateOf("") }
    var check by remember { mutableStateOf<ServerCheck>(ServerCheck.Idle) }
    val url by settings.flow(SettingKeys.SERVER_URL).collectAsState(initial = "")

    LaunchedEffect(Unit) {
        savedMasked = withContext(Dispatchers.IO) { container.serverTokenStore.read()?.let(::maskApiKey) }
    }

    SettingsPage(stringResource(R.string.server_title), onBack) {
        SectionCard(null, hint = stringResource(R.string.server_fallback_hint)) {
            SwitchSetting(
                settings, SettingKeys.SERVER_ENABLED, stringResource(R.string.server_enabled),
                hint = stringResource(R.string.server_enabled_hint),
            )
        }
        SectionCard(stringResource(R.string.server_connection), hint = stringResource(R.string.server_connection_hint)) {
            TextSetting(
                settings, SettingKeys.SERVER_URL, stringResource(R.string.server_url), allowBlank = true,
                hint = stringResource(R.string.server_url_hint),
                validate = { if (it.isBlank() || it.trim().startsWith("https://")) null else "https://…" },
            )
            Text(
                savedMasked?.let { stringResource(R.string.server_token_saved, it) } ?: stringResource(R.string.server_token_none),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text(stringResource(R.string.server_token_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = tokenInput.isNotBlank(),
                    onClick = {
                        val t = tokenInput
                        tokenInput = ""
                        scope.launch {
                            withContext(Dispatchers.IO) { container.serverTokenStore.save(t) }
                            savedMasked = maskApiKey(t.trim())
                            check = ServerCheck.Idle
                            GuzzLog.info("Trang Server: đã lưu token ${maskApiKey(t.trim())}")
                        }
                    },
                ) { Text(stringResource(R.string.google_ai_save)) }
                OutlinedButton(
                    enabled = savedMasked != null,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { container.serverTokenStore.clear() }
                            savedMasked = null
                            check = ServerCheck.Idle
                            GuzzLog.info("Trang Server: đã xoá token")
                        }
                    },
                ) { Text(stringResource(R.string.google_ai_delete)) }
            }
            FilledTonalButton(
                enabled = url.isNotBlank() && savedMasked != null && check != ServerCheck.Running,
                onClick = {
                    check = ServerCheck.Running
                    scope.launch {
                        check = try {
                            ServerCheck.Ok(container.serverRemote.health().server)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: RemoteUnavailableException) {
                            ServerCheck.Failed(e.reason, e.message.orEmpty())
                        } catch (e: Exception) {
                            ServerCheck.Failed(Reasons.NETWORK, e.message.orEmpty())
                        }
                        GuzzLog.info("Trang Server: kiểm tra kết nối: $check")
                    }
                },
            ) { Text(stringResource(R.string.server_check)) }
            when (val c = check) {
                ServerCheck.Idle -> Unit
                ServerCheck.Running -> Text(stringResource(R.string.server_checking))
                is ServerCheck.Ok -> Text(stringResource(R.string.server_check_ok, c.version), color = MaterialTheme.colorScheme.primary)
                is ServerCheck.Failed -> Text(
                    stringResource(
                        when (c.reason) {
                            Reasons.AUTH -> R.string.server_check_auth
                            Reasons.PROTOCOL -> R.string.server_check_protocol
                            Reasons.CONFIG -> R.string.server_reason_config_ui
                            else -> R.string.server_check_unreachable
                        },
                    ) + "\n" + c.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

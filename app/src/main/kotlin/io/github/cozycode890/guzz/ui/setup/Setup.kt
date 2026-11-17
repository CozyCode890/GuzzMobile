package io.github.cozycode890.guzz.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.AppContainer
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.ui.components.Hint
import io.github.cozycode890.guzz.ui.nav.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Một việc của Thiết lập ban đầu (D116). */
enum class SetupItem { API_KEY, OUTPUT_FOLDER, NOTIFICATIONS, BATTERY }

/** Trạng thái các việc của Thiết lập ban đầu; `true` = đã xong. */
data class SetupState(val done: Map<SetupItem, Boolean>) {
    val pending: List<SetupItem> get() = SetupItem.entries.filter { done[it] == false }
}

/** Thẻ "Để sau" chỉ ẩn tới lần mở app sau (sống cùng process). */
private object SetupDismissed {
    var value = false
}

/** Đã được miễn tối ưu pin chưa (D69). Stage 6 có `BatteryStatus` riêng: khi gộp (H2) thì dùng chung một chỗ. */
private fun isBatteryExempt(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Đọc lại trạng thái khi [resumes] đổi (quay về từ trang Cài đặt hệ thống) hoặc khi cài đặt đổi. */
@Composable
fun rememberSetupState(container: AppContainer, resumes: StateFlow<Int>, refresh: Int = 0): SetupState? {
    val context = LocalContext.current
    val resumeTick by resumes.collectAsState()
    val outputDir by container.settings.flow(SettingKeys.OUTPUT_DIR).collectAsState(initial = null)
    var state by remember { mutableStateOf<SetupState?>(null) }
    LaunchedEffect(resumeTick, outputDir, refresh) {
        val folder = outputDir ?: return@LaunchedEffect
        val hasKey = withContext(Dispatchers.IO) { container.apiKeyStore.read() != null }
        state = SetupState(
            mapOf(
                SetupItem.API_KEY to (hasKey || container.fakeGeminiEnabled()),
                SetupItem.OUTPUT_FOLDER to folder.isNotBlank(),
                SetupItem.NOTIFICATIONS to hasNotificationPermission(context),
                SetupItem.BATTERY to isBatteryExempt(context),
            ),
        )
    }
    return state
}

/** Thẻ "Thiết lập ban đầu" trên tab Chuyển đổi (D116): chỉ hiện khi còn việc chưa xong và chưa bấm "Để sau". */
@Composable
fun SetupCard(container: AppContainer, resumes: StateFlow<Int>, open: (String) -> Unit, onPickFolder: () -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    var dismissed by remember { mutableStateOf(SetupDismissed.value) }
    val state = rememberSetupState(container, resumes, refresh) ?: return
    if (dismissed || state.pending.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("setup_card"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
            Hint(stringResource(R.string.setup_hint))
            SetupList(state, onlyPending = true, open = open, onPickFolder = onPickFolder) { refresh++ }
            TextButton(onClick = { SetupDismissed.value = true; dismissed = true }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.setup_later))
            }
        }
    }
}

/**
 * Danh sách việc của Thiết lập ban đầu, mỗi việc kèm nút làm ngay. Dùng ở thẻ trên tab Chuyển đổi ([onlyPending]) và ở
 * Cài đặt → Hệ thống (đủ cả việc đã xong). [onChanged]: gọi sau khi một việc có thể vừa xong (vd vừa cấp quyền).
 */
@Composable
fun SetupList(
    state: SetupState,
    onlyPending: Boolean,
    open: (String) -> Unit,
    onPickFolder: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    for (item in SetupItem.entries) {
        val done = state.done[item] ?: continue
        if (onlyPending && done) continue
        val (label, action) = when (item) {
            SetupItem.API_KEY -> R.string.setup_api_key to R.string.setup_api_key_action
            SetupItem.OUTPUT_FOLDER -> R.string.setup_folder to R.string.queue_choose_folder
            SetupItem.NOTIFICATIONS -> R.string.setup_notifications to R.string.setup_allow
            SetupItem.BATTERY -> R.string.setup_battery to R.string.queue_open_app_info
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("setup:${item.name}")) {
            Icon(
                if (done) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = stringResource(if (done) R.string.setup_done else R.string.setup_todo),
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(stringResource(label), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            if (!done) {
                FilledTonalButton(onClick = {
                    when (item) {
                        SetupItem.API_KEY -> open(Routes.GOOGLE_AI)
                        SetupItem.OUTPUT_FOLDER -> onPickFolder()
                        SetupItem.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        // Trang thông tin app: HyperOS đặt "Không hạn chế" ở mục Pin của trang này (D69).
                        SetupItem.BATTERY -> context.startActivity(
                            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }) { Text(stringResource(action)) }
            }
        }
    }
}

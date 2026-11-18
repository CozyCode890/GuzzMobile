package io.github.cozycode890.guzz.ui.automation

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.cozycode890.guzz.AppContainer
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.feature.automation.AutomationActions
import io.github.cozycode890.guzz.feature.automation.AutomationSettingsModel
import io.github.cozycode890.guzz.feature.automation.AutomationSettingsScreen
import io.github.cozycode890.guzz.feature.automation.AutomationUiState
import io.github.cozycode890.guzz.feature.automation.R as AutomationR
import io.github.cozycode890.guzz.ui.components.AdvancedSection
import io.github.cozycode890.guzz.ui.components.SettingsPage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Trang Tự động hoá trong tab Cài đặt (Stage 8 H1, D114): trước là `AutomationActivity` của Stage 6. Trang cấp các
 * launcher cần `ActivityResult` cho [AutomationSettingsScreen] và đọc lại quyền mỗi lần app quay lại ([resumes]).
 */
@Composable
fun AutomationRoute(container: AppContainer, resumes: StateFlow<Int>, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val automation = container.automation
    val model = remember { AutomationSettingsModel(automation, container.settings, context.packageName) }
    val state by model.state.collectAsState(initial = AutomationUiState(allFilesFlavor = automation.allFilesFlavor))
    val resumeTick by resumes.collectAsState()
    LaunchedEffect(resumeTick) { model.refresh() }

    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                runCatching { model.addSourceDir(it) }.onFailure { e -> GuzzLog.warning("Không thêm được Thư mục nguồn: ${e.message}") }
            }
        }
    }
    val askRead = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
    val actions = remember(model) {
        AutomationActions(
            setEnabled = { v -> scope.launch { model.setEnabled(v) } },
            setSecret = { v -> scope.launch { model.setSecret(v) } },
            setIncludePaths = { v -> scope.launch { model.setIncludePaths(v) } },
            setSaveBesideSource = { v -> scope.launch { model.setSaveBesideSource(v) } },
            requestAllFiles = {
                val intent = automation.fileAccess.allFilesAccessIntent()
                if (intent != null) openSystemPage(context, intent) else askRead.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
            addSourceDir = { pickTree.launch(null) },
            removeSourceDir = { uri -> scope.launch { model.removeSourceDir(uri) } },
            requestBattery = { openSystemPage(context, automation.battery.requestIntent()) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openSystemPage(
                        context,
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            },
            sendTestEvent = { scope.launch { model.sendTestEvent() } },
        )
    }

    SettingsPage(stringResource(AutomationR.string.automation_title), onBack) {
        AutomationSettingsScreen(state, actions, advanced = { content -> AdvancedSection { content() } })
    }
}

/** Mở trang cài đặt hệ thống; máy thiếu trang đó thì mở trang thông tin app. */
fun openSystemPage(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}

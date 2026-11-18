package io.github.cozycode890.guzz

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.feature.automation.AutomationActions
import io.github.cozycode890.guzz.feature.automation.AutomationSettingsModel
import io.github.cozycode890.guzz.feature.automation.AutomationSettingsScreen
import io.github.cozycode890.guzz.feature.automation.AutomationUiState
import io.github.cozycode890.guzz.ui.theme.GuzzTheme
import kotlinx.coroutines.launch

/**
 * Trang Tự động hoá (Stage 6, D99): Activity riêng như trang Âm thanh, chỉ cấp launcher cho [AutomationSettingsScreen].
 * Stage 8 (H1) chuyển thành route trong tab Cài đặt.
 */
class AutomationActivity : ComponentActivity() {
    private lateinit var model: AutomationSettingsModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GuzzApp).container
        val automation = container.automation
        model = AutomationSettingsModel(automation, container.settings, packageName)
        setContent {
            GuzzTheme {
                val scope = rememberCoroutineScope()
                val state by model.state.collectAsState(initial = AutomationUiState(allFilesFlavor = automation.allFilesFlavor))
                val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                    uri?.let { scope.launch { runCatching { model.addSourceDir(it) }.onFailure { e -> GuzzLog.warning("Không thêm được Thư mục nguồn: ${e.message}") } } }
                }
                val askRead = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
                val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refresh() }
                val actions = AutomationActions(
                    setEnabled = { v -> scope.launch { model.setEnabled(v) } },
                    setSecret = { v -> scope.launch { model.setSecret(v) } },
                    setIncludePaths = { v -> scope.launch { model.setIncludePaths(v) } },
                    setSaveBesideSource = { v -> scope.launch { model.setSaveBesideSource(v) } },
                    requestAllFiles = {
                        val intent = automation.fileAccess.allFilesAccessIntent()
                        if (intent != null) open(intent) else askRead.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    },
                    addSourceDir = { pickTree.launch(null) },
                    removeSourceDir = { uri -> scope.launch { model.removeSourceDir(uri) } },
                    requestBattery = { open(automation.battery.requestIntent()) },
                    requestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            open(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName))
                        }
                    },
                    sendTestEvent = { scope.launch { model.sendTestEvent() } },
                )
                Surface(Modifier.fillMaxSize()) {
                    AutomationSettingsScreen(state, actions, Modifier.safeDrawingPadding())
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    /** Mở trang cài đặt hệ thống; máy thiếu trang đó thì mở trang thông tin app. */
    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}

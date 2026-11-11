package io.github.cozycode890.guzz.debug

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.ui.theme.GuzzTheme

/**
 * Màn hình thử lõi âm thanh, chỉ có ở bản debug (D49): chọn tệp (trình chọn tệp → content:// URI), chọn luồng, Chạy /
 * Dừng, xem tiến độ và kết quả. Việc chạy nằm trong [AudioDebugService] để tắt màn hình vẫn chạy.
 *
 * Chạy bằng adb (tệp đã `adb push` vào `files/input/`):
 *
 *     adb shell am start -n io.github.cozycode890.guzz.debug/io.github.cozycode890.guzz.debug.AudioDebugActivity \
 *         --es path L1_120m.m4a --es track auto --ez auto_run true
 *
 * Dừng bằng adb: cùng lệnh với `--ez stop true` khi màn hình đang bật; màn hình tắt thì dùng [AudioDebugStopReceiver].
 * Kết quả: `files/debug-audio/<tên bài>/ket_qua.json`.
 *
 * Gửi thử Gemini (Stage 3, [GeminiDebug]) một đoạn đã cắt, bằng nút hoặc adb (`tools/device/gemini_debug.ps1`):
 *
 *     adb shell am start -n .../io.github.cozycode890.guzz.debug.AudioDebugActivity \
 *         --ez gemini_send true --es gemini_folder <bài> --es gemini_mode smart [--es gemini_model <model>]
 *     adb shell am start -n .../io.github.cozycode890.guzz.debug.AudioDebugActivity --ez gemini_list_models true
 */
class AudioDebugActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        if (savedInstanceState == null) autoRun(intent)
        enableEdgeToEdge()
        setContent {
            GuzzTheme {
                AudioDebugScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        autoRun(intent)
    }

    private fun autoRun(intent: Intent) {
        // `--ez stop true`: bấm Dừng bằng adb (service không exported nên adb không gửi thẳng tới nó được).
        if (intent.getBooleanExtra("stop", false)) {
            AudioDebugService.stop(this)
            return
        }
        if (intent.getBooleanExtra(EXTRA_GEMINI_LIST_MODELS, false)) {
            GeminiDebug.listModels(this)
            return
        }
        if (intent.getBooleanExtra(EXTRA_GEMINI_SEND, false)) {
            val folder = intent.getStringExtra(EXTRA_GEMINI_FOLDER) ?: return
            GeminiDebug.send(
                this, folder,
                intent.getStringExtra(EXTRA_GEMINI_CHUNK) ?: DEFAULT_CHUNK,
                intent.getStringExtra(EXTRA_GEMINI_MODEL),
                intent.getStringExtra(EXTRA_GEMINI_MODE) ?: "smart",
            )
            return
        }
        if (!intent.getBooleanExtra("auto_run", false)) return
        val path = intent.getStringExtra(AudioDebugService.EXTRA_PATH)
        val uri = intent.getStringExtra(AudioDebugService.EXTRA_URI)?.let(Uri::parse)
        if (path == null && uri == null) return
        // Khởi động FGS ngay khi activity được mở (đang là app trên cùng), nên không vướng hạn chế chạy nền.
        AudioDebugService.start(this, uri, path, intent.getStringExtra(AudioDebugService.EXTRA_TRACK) ?: "auto")
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    companion object {
        const val EXTRA_GEMINI_SEND = "gemini_send"
        const val EXTRA_GEMINI_LIST_MODELS = "gemini_list_models"
        const val EXTRA_GEMINI_FOLDER = "gemini_folder"
        const val EXTRA_GEMINI_CHUNK = "gemini_chunk"
        const val EXTRA_GEMINI_MODEL = "gemini_model"
        const val EXTRA_GEMINI_MODE = "gemini_mode"
        const val DEFAULT_CHUNK = "doan_001.flac"
    }
}

@Composable
private fun AudioDebugScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var picked by remember { mutableStateOf<Uri?>(null) }
    var track by remember { mutableStateOf("auto") }
    val run by AudioDebugService.runState.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Giữ quyền đọc cho cả service (process khác thành phần, cùng app) và các lần mở sau.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            picked = uri
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.debug_audio_title), style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }) {
                Text(stringResource(R.string.debug_audio_pick))
            }
            Text(picked?.toString() ?: stringResource(R.string.debug_audio_none), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = track,
                onValueChange = { track = it },
                label = { Text(stringResource(R.string.debug_audio_track)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = picked != null && !run.running,
                    onClick = { AudioDebugService.start(context, picked, null, track) },
                ) { Text(stringResource(R.string.debug_audio_run)) }
                OutlinedButton(enabled = run.running, onClick = { AudioDebugService.stop(context) }) {
                    Text(stringResource(R.string.debug_audio_stop))
                }
            }
            if (run.input.isNotEmpty()) {
                Text("${run.input}: ${run.step}", style = MaterialTheme.typography.bodyMedium)
                if (run.running) LinearProgressIndicator(progress = { run.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                if (run.result.isNotEmpty()) Text(run.result, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            GeminiDebugSection(lastFolder = run.input.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}._-]"), "_"))
        }
    }
}

/** Gửi thử Gemini một đoạn đã cắt (Stage 3): thư mục bài, model (trống = model chính), chế độ. */
@Composable
private fun GeminiDebugSection(lastFolder: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by GeminiDebug.runState.collectAsState()
    var folder by remember(lastFolder) { mutableStateOf(lastFolder) }
    var model by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("smart") }
    Text(stringResource(R.string.debug_gemini_title), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = folder,
        onValueChange = { folder = it },
        label = { Text(stringResource(R.string.debug_gemini_folder)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text(stringResource(R.string.debug_gemini_model)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in GeminiDebug.MODES) {
            FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            enabled = folder.isNotBlank() && !state.running,
            onClick = { GeminiDebug.send(context, folder.trim(), AudioDebugActivity.DEFAULT_CHUNK, model.trim(), mode) },
        ) { Text(stringResource(R.string.debug_gemini_send)) }
        OutlinedButton(enabled = !state.running, onClick = { GeminiDebug.listModels(context) }) {
            Text(stringResource(R.string.debug_gemini_list_models))
        }
    }
    if (state.line.isNotEmpty()) Text(state.line, style = MaterialTheme.typography.bodySmall)
}

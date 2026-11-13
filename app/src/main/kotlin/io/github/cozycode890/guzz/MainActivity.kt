package io.github.cozycode890.guzz

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.gemini.isValidModelName
import io.github.cozycode890.guzz.core.pipeline.ItemStatus
import io.github.cozycode890.guzz.core.pipeline.QueueItem
import io.github.cozycode890.guzz.core.pipeline.StartProblem
import io.github.cozycode890.guzz.core.pipeline.Transcript
import io.github.cozycode890.guzz.feature.queue.QueueService
import io.github.cozycode890.guzz.ui.theme.GuzzTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.saveable.rememberSaveable

/** Màn hình chính (Stage 4, E1): Hàng đợi. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GuzzApp).container
        setContent {
            GuzzTheme {
                QueueScreen(container, resumes)
            }
        }
    }

    /** Tăng mỗi lần màn hình hiện lại, để dải báo (key, tối ưu pin) đọc lại sau khi quay về từ trang khác. */
    private val resumes = MutableStateFlow(0)

    /**
     * D64: hàng đợi bị giết / bị hệ thống dừng (`onTimeout`) khi đang chạy dở thì mở app là làm tiếp. Ở `onResume` chứ
     * không ở `onCreate`: sau `onTimeout` màn hình này thường vẫn còn, mở lại app chỉ gọi `onResume`. Gọi nhiều lần không
     * sao: chỉ chạy khi cờ đang chạy còn bật và chưa có vòng chạy nào.
     */
    override fun onResume() {
        super.onResume()
        resumes.value++
        (application as GuzzApp).container.queueController.resumeIfNeeded()
    }
}

/** URI để mở / chia sẻ bản chép lời: URI SAF giữ nguyên, đường dẫn (thư mục ra của bản debug) đi qua FileProvider. */
fun shareableUri(context: Context, location: String): Uri =
    if (location.startsWith("/")) {
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(location))
    } else {
        Uri.parse(location)
    }

/** Mở bản chép lời bằng app khác (D61). */
fun openWithOtherApp(context: Context, location: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(shareableUri(context, location), "text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null))
    }.onFailure { GuzzLog.warning("Không mở được $location bằng app khác: ${it.message}") }
}

/** Chia sẻ tệp .txt (D62): `ACTION_SEND` kèm URI, cấp quyền đọc cho app nhận. */
fun shareTranscript(context: Context, location: String, name: String?) {
    runCatching {
        // getUriForFile ném lỗi khi đường dẫn nằm ngoài thư mục đã khai cho FileProvider: bắt chung, không làm sập app.
        val uri = shareableUri(context, location)
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData để quyền đọc đi theo cả qua màn hình chọn app.
        intent.clipData = ClipData.newRawUri(name, uri)
        context.startActivity(Intent.createChooser(intent, null))
    }.onFailure { GuzzLog.warning("Không chia sẻ được $location: ${it.message}") }
}

/** Gợi ý thư mục `Documents` cho trình chọn thư mục lưu (D59). */
private val DOCUMENTS_HINT: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")

private val SUGGESTED_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-3.8-flash", "gemini-3.5-transcribe")

@Composable
private fun QueueScreen(container: AppContainer, resumes: StateFlow<Int>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue = container.queueController
    val state by queue.state.collectAsState()
    val active by queue.active.collectAsState()
    val outputDir by container.settings.flow(SettingKeys.OUTPUT_DIR).collectAsState(initial = null)
    val model by container.settings.flow(SettingKeys.MODEL).collectAsState(initial = "")
    val trimSilence by container.settings.flow(SettingKeys.TRIM_SILENCE).collectAsState(initial = true)
    var hasKey by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // Saveable: trình chọn thư mục / hộp xin quyền có thể làm màn hình này bị dựng lại.
    var startAfterPick by rememberSaveable { mutableStateOf(false) }
    val resumeTick by resumes.collectAsState()
    var refreshTick by remember { mutableStateOf(0) }
    val noKeyMessage = stringResource(R.string.queue_problem_no_key)
    val nothingMessage = stringResource(R.string.queue_problem_nothing)
    val invalidModelMessage = stringResource(R.string.google_ai_model_invalid)

    LaunchedEffect(refreshTick, resumeTick) {
        hasKey = withContext(Dispatchers.IO) { container.apiKeyStore.read() != null }
    }

    fun doStart() {
        scope.launch {
            when (val problem = queue.checkStart()) {
                null -> {
                    message = null
                    queue.start()
                }
                is StartProblem.NoApiKey -> message = noKeyMessage
                is StartProblem.NothingToDo -> message = nothingMessage
                else -> message = problem.message
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            // Không trả quyền thư mục cũ: tệp đang chạy và các bản chép lời đã ghi ở đó (Xem, Mở, Chia sẻ) vẫn cần nó.
            scope.launch {
                container.settings.set(SettingKeys.OUTPUT_DIR, uri.toString())
                GuzzLog.info("Chọn thư mục lưu bản chép lời: $uri")
                if (startAfterPick) doStart()
                startAfterPick = false
            }
        } else {
            startAfterPick = false
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch(Dispatchers.IO) { queue.addUris(uris) }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Không có quyền thì service vẫn chạy, chỉ không thấy thông báo tiến độ.
        if (outputDir.isNullOrBlank()) {
            startAfterPick = true
            pickFolder.launch(DOCUMENTS_HINT)
        } else {
            doStart()
        }
    }

    fun onStartClicked() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (outputDir.isNullOrBlank()) {
            startAfterPick = true
            pickFolder.launch(DOCUMENTS_HINT)
        } else {
            doStart()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // ---- thanh trên
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.main_open_google_ai)) }, onClick = {
                            menuOpen = false
                            context.startActivity(Intent(context, GoogleAiActivity::class.java))
                        })
                        if (BuildConfig.DEBUG) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.queue_menu_debug)) }, onClick = {
                                menuOpen = false
                                context.startActivity(Intent().setClassName(context, "$PACKAGE.debug.AudioDebugActivity"))
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.queue_clear_done)) }, onClick = {
                            menuOpen = false
                            queue.clearFinished()
                        })
                    }
                }
            }

            // ---- dải báo
            val fake = container.fakeGeminiEnabled()
            if (fake) Banner(stringResource(R.string.queue_banner_fake), null) {}
            if (!hasKey && !fake) {
                Banner(stringResource(R.string.queue_banner_no_key), stringResource(R.string.main_open_google_ai)) {
                    context.startActivity(Intent(context, GoogleAiActivity::class.java))
                    refreshTick++
                }
            }
            if (outputDir != null && outputDir!!.isBlank()) {
                Banner(stringResource(R.string.queue_banner_no_folder), stringResource(R.string.queue_choose_folder)) {
                    pickFolder.launch(DOCUMENTS_HINT)
                }
            }
            val power = context.getSystemService(PowerManager::class.java)
            val batteryExempt = remember(resumeTick) { power.isIgnoringBatteryOptimizations(context.packageName) }
            if (!batteryExempt) {
                Banner(stringResource(R.string.queue_banner_battery), stringResource(R.string.queue_open_app_info)) {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                    )
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }

            // ---- cài đặt nhanh (E2)
            QuickSettings(
                outputLabel = outputDir?.takeIf { it.isNotBlank() }?.let { Uri.parse(it).lastPathSegment?.substringAfter(':') ?: it },
                model = model,
                trimSilence = trimSilence,
                onPickFolder = { pickFolder.launch(DOCUMENTS_HINT) },
                onModel = { m ->
                    if (isValidModelName(m)) {
                        scope.launch { container.settings.set(SettingKeys.MODEL, m) }
                        message = null
                    } else {
                        message = invalidModelMessage
                    }
                },
                onTrimSilence = { v -> scope.launch { container.settings.set(SettingKeys.TRIM_SILENCE, v) } },
            )

            // ---- nút
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { pickFiles.launch(arrayOf("audio/*", "video/*")) }) {
                    Text(stringResource(R.string.queue_add))
                }
                if (active) {
                    Button(onClick = { queue.stop() }) { Text(stringResource(R.string.queue_stop_button)) }
                } else {
                    Button(onClick = ::onStartClicked) { Text(stringResource(R.string.queue_start)) }
                }
            }

            // ---- danh sách
            if (state.items.isEmpty()) {
                Text(stringResource(R.string.queue_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(state.items, key = { it.id }) { item ->
                    QueueRow(
                        item = item,
                        onView = { item.outFile?.let { context.startActivity(TranscriptActivity.intent(context, it, item.outName)) } },
                        onOpenWith = { item.outFile?.let { openWithOtherApp(context, it) } },
                        onShare = { item.outFile?.let { shareTranscript(context, it, item.outName) } },
                        onRerun = { queue.rerun(item.id) },
                        onRemove = { queue.remove(item.id) },
                    )
                }
            }
        }
    }
}

private const val PACKAGE = "io.github.cozycode890.guzz"

@Composable
private fun Banner(text: String, action: String?, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun QuickSettings(
    outputLabel: String?,
    model: String,
    trimSilence: Boolean,
    onPickFolder: () -> Unit,
    onModel: (String) -> Unit,
    onTrimSilence: (Boolean) -> Unit,
) {
    var modelInput by remember(model) { mutableStateOf(model) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.queue_folder, outputLabel ?: stringResource(R.string.queue_folder_none)),
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onPickFolder) { Text(stringResource(R.string.queue_choose_folder)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = modelInput, onValueChange = { modelInput = it }, singleLine = true,
                    label = { Text(stringResource(R.string.google_ai_model_label)) }, modifier = Modifier.weight(1f),
                )
                TextButton(enabled = modelInput.trim() != model, onClick = { onModel(modelInput.trim()) }) {
                    Text(stringResource(R.string.google_ai_model_use))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SUGGESTED_MODELS.filter { it != model }.forEach { m ->
                    AssistChip(onClick = { onModel(m) }, label = { Text(m) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.queue_trim_silence), modifier = Modifier.weight(1f))
                Switch(checked = trimSilence, onCheckedChange = onTrimSilence)
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    onView: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    onRerun: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val duration = item.durationSec?.let { Transcript.hms(it) }
                    val status = stringResource(statusLabel(item.status))
                    val detail = if (item.status == ItemStatus.RUNNING) QueueService.describe(context, item) else null
                    Text(
                        listOfNotNull(duration, status, detail).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (item.outFile != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.queue_view)) }, onClick = { menu = false; onView() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.queue_open_with)) }, onClick = { menu = false; onOpenWith() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.queue_share)) }, onClick = { menu = false; onShare() })
                        }
                        if (item.status != ItemStatus.RUNNING) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.queue_rerun)) }, onClick = { menu = false; onRerun() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.queue_remove)) }, onClick = { menu = false; onRemove() })
                        }
                    }
                }
            }
            if (item.status == ItemStatus.RUNNING) {
                LinearProgressIndicator(progress = { item.progress.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            item.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun statusLabel(status: ItemStatus): Int = when (status) {
    ItemStatus.WAITING -> R.string.queue_status_waiting
    ItemStatus.RUNNING -> R.string.queue_status_running
    ItemStatus.DONE -> R.string.queue_status_done
    ItemStatus.ERROR -> R.string.queue_status_error
    ItemStatus.STOPPED -> R.string.queue_status_stopped
    ItemStatus.SKIPPED -> R.string.queue_status_skipped
}

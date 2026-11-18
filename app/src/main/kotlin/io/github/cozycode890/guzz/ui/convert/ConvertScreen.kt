package io.github.cozycode890.guzz.ui.convert

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.AppContainer
import io.github.cozycode890.guzz.BuildConfig
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.gemini.isValidModelName
import io.github.cozycode890.guzz.core.pipeline.ItemStatus
import io.github.cozycode890.guzz.core.pipeline.QueueItem
import io.github.cozycode890.guzz.core.pipeline.StartProblem
import io.github.cozycode890.guzz.core.pipeline.Transcript
import io.github.cozycode890.guzz.feature.queue.QueueService
import io.github.cozycode890.guzz.feature.queue.errorText
import io.github.cozycode890.guzz.openWithOtherApp
import io.github.cozycode890.guzz.shareTranscript
import io.github.cozycode890.guzz.ui.audio.presetFlow
import io.github.cozycode890.guzz.ui.audio.presetLabel
import io.github.cozycode890.guzz.ui.components.MaxContentWidth
import io.github.cozycode890.guzz.ui.components.PageScaffold
import io.github.cozycode890.guzz.ui.nav.Routes
import io.github.cozycode890.guzz.ui.setup.SetupCard
import io.github.cozycode890.guzz.ui.speakers.methodLabel
import io.github.cozycode890.guzz.ui.speakers.switchMethodForModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Gợi ý `Documents` cho trình chọn thư mục lưu (D59). */
val DOCUMENTS_HINT: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")

private val SUGGESTED_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-3.8-flash", "gemini-3.5-transcribe")

/**
 * Tab Chuyển đổi (Stage 4 E1, Stage 8 B5): thẻ Thiết lập ban đầu (D116), cài đặt nhanh, nút Thêm tệp / Bắt đầu, hàng
 * đợi. [open]: mở một trang khác (route).
 */
@Composable
fun ConvertScreen(container: AppContainer, resumes: StateFlow<Int>, open: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue = container.queueController
    val state by queue.state.collectAsState()
    val active by queue.active.collectAsState()
    val outputDir by container.settings.flow(SettingKeys.OUTPUT_DIR).collectAsState(initial = null)
    val model by container.settings.flow(SettingKeys.MODEL).collectAsState(initial = "")
    val audioPreset by remember { presetFlow(container.settings) }.collectAsState(initial = null)
    val speakersOn by container.settings.flow(SettingKeys.SPEAKERS_ENABLED).collectAsState(initial = false)
    val speakersMethod by container.settings.flow(SettingKeys.SPEAKERS_METHOD).collectAsState(initial = "")
    var message by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // Saveable: trình chọn thư mục / hộp xin quyền có thể làm màn hình này bị dựng lại.
    var startAfterPick by rememberSaveable { mutableStateOf(false) }
    val noKeyMessage = stringResource(R.string.queue_problem_no_key)
    val nothingMessage = stringResource(R.string.queue_problem_nothing)
    val invalidModelMessage = stringResource(R.string.google_ai_model_invalid)

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

    PageScaffold(
        title = stringResource(R.string.nav_convert),
        onBack = null,
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.queue_clear_done)) }, onClick = {
                        menuOpen = false
                        queue.clearFinished()
                    })
                    if (BuildConfig.DEBUG) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.queue_menu_debug)) }, onClick = {
                            menuOpen = false
                            context.startActivity(Intent().setClassName(context, DEBUG_AUDIO_ACTIVITY))
                        })
                    }
                }
            }
        },
    ) { modifier ->
        Box(modifier, contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
            ) {
                // ---- dải báo + Thiết lập ban đầu (D116)
                item(key = "banners") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (container.fakeGeminiEnabled()) Banner(stringResource(R.string.queue_banner_fake), null) {}
                        SetupCard(container, resumes, open = open, onPickFolder = { pickFolder.launch(DOCUMENTS_HINT) })
                        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }

                // ---- cài đặt nhanh (Stage 4 E2)
                item(key = "quick") {
                    QuickSettings(
                        outputLabel = outputDir?.takeIf { it.isNotBlank() }?.let { Uri.parse(it).lastPathSegment?.substringAfter(':') ?: it },
                        model = model,
                        audioLabel = audioPreset?.let { presetLabel(context, it) } ?: "",
                        onAudio = { open(Routes.AUDIO) },
                        speakersLabel = if (speakersOn) methodLabel(context, speakersMethod) else stringResource(R.string.speakers_off),
                        onSpeakers = { open(Routes.SPEAKERS) },
                        onPickFolder = { pickFolder.launch(DOCUMENTS_HINT) },
                        onModel = { m ->
                            if (isValidModelName(m)) {
                                message = null
                                scope.launch {
                                    container.settings.set(SettingKeys.MODEL, m)
                                    // E5: cách nhận diện không hợp model mới thì tự chuyển và báo.
                                    switchMethodForModel(context, container.settings, m)?.let { message = it }
                                }
                            } else {
                                message = invalidModelMessage
                            }
                        },
                    )
                }

                // ---- nút
                item(key = "buttons") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickFiles.launch(arrayOf("audio/*", "video/*")) }) {
                            Text(stringResource(R.string.queue_add))
                        }
                        if (active) {
                            Button(onClick = { queue.stop() }) { Text(stringResource(R.string.queue_stop_button)) }
                        } else {
                            Button(onClick = ::onStartClicked) { Text(stringResource(R.string.queue_start)) }
                        }
                    }
                }

                // ---- danh sách
                if (state.items.isEmpty()) {
                    item(key = "empty") {
                        Text(stringResource(R.string.queue_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.items, key = { it.id }) { item ->
                    QueueRow(
                        item = item,
                        onView = { item.outFile?.let { open(Routes.transcript(it, item.outName)) } },
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

private const val DEBUG_AUDIO_ACTIVITY = "io.github.cozycode890.guzz.debug.AudioDebugActivity"

@Composable
fun Banner(text: String, action: String?, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
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
    audioLabel: String,
    onAudio: () -> Unit,
    speakersLabel: String,
    onSpeakers: () -> Unit,
    onPickFolder: () -> Unit,
    onModel: (String) -> Unit,
) {
    var modelInput by remember(model) { mutableStateOf(model) }
    Card(Modifier.fillMaxWidth()) {
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
                // Stage 7 (D89): công tắc "Cắt lặng" cũ chuyển vào trang Âm thanh.
                Text(
                    stringResource(R.string.queue_audio, audioLabel), modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onAudio) { Text(stringResource(R.string.queue_speakers_open)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.queue_speakers, speakersLabel), modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onSpeakers) { Text(stringResource(R.string.queue_speakers_open)) }
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
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more))
                    }
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
            errorText(LocalContext.current, item)?.let {
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

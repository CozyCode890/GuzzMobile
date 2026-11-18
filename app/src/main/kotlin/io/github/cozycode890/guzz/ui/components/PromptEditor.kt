package io.github.cozycode890.guzz.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.gemini.PromptKind
import io.github.cozycode890.guzz.feature.queue.promptSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thẻ sửa một prompt (Stage 8 G3): ô nhiều dòng, Lưu, Khôi phục mặc định. [lockedReason] khác `null` thì ô bị khoá
 * (model `*-transcribe` không nhận prompt) và hiện lý do. [requiredText]: chuỗi prompt nên có (vd `{speakers}`), thiếu
 * thì nhắc nhưng vẫn cho lưu, như desktop không kiểm tệp prompt.
 */
@Composable
fun PromptEditor(kind: PromptKind, title: String, hint: String, lockedReason: String?, requiredText: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = remember { promptSource(context) }
    // null: đang đọc tệp.
    var saved by remember { mutableStateOf<String?>(null) }
    var custom by remember { mutableStateOf(false) }
    var text by rememberSaveable(kind) { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val c = withContext(Dispatchers.IO) { source.custom(kind) }
        custom = c != null
        saved = c ?: kind.default
    }
    LaunchedEffect(kind) {
        reload()
        if (text == null) text = saved
    }

    SectionCard(title, Modifier.testTag("prompt:${kind.name}"), hint = hint) {
        lockedReason?.let { Hint(it) }
        val current = text ?: return@SectionCard
        OutlinedTextField(
            value = current,
            onValueChange = { text = it },
            enabled = lockedReason == null,
            minLines = 6,
            maxLines = 14,
            modifier = Modifier.fillMaxWidth().testTag("prompt_text:${kind.name}"),
        )
        if (requiredText != null && !current.contains(requiredText)) {
            Hint(stringResource(R.string.prompt_missing_placeholder, requiredText))
        }
        Hint(stringResource(if (custom) R.string.prompt_state_custom else R.string.prompt_state_default))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = lockedReason == null && current.trim() != saved?.trim(),
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { source.save(kind, current) }
                        GuzzLog.info("Sửa prompt ${kind.fileName} (${current.trim().length} ký tự).")
                        reload()
                        text = saved
                    }
                },
            ) { Text(stringResource(R.string.prompt_save)) }
            TextButton(
                enabled = lockedReason == null && (custom || current.trim() != kind.default),
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { source.reset(kind) }
                        GuzzLog.info("Khôi phục prompt mặc định ${kind.fileName}.")
                        reload()
                        text = saved
                    }
                },
            ) { Text(stringResource(R.string.prompt_reset)) }
        }
    }
}

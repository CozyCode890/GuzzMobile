package io.github.cozycode890.guzz.ui.transcript

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.openWithOtherApp
import io.github.cozycode890.guzz.shareTranscript
import io.github.cozycode890.guzz.ui.components.MaxContentWidth
import io.github.cozycode890.guzz.ui.components.PageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Dấu BOM của UTF-8 (`utf-8-sig`), viết bằng mã để không lẫn ký tự vô hình vào mã nguồn. */
private val BOM = Char(0xFEFF).toString()

/** Đọc bản chép lời, bỏ BOM, đổi CRLF thành LF, tách đoạn theo dòng trống. */
private fun readParagraphs(context: Context, location: String): List<String> {
    val bytes = if (location.startsWith("/")) {
        File(location).readBytes()
    } else {
        context.contentResolver.openInputStream(Uri.parse(location))?.use { it.readBytes() } ?: ByteArray(0)
    }
    return bytes.toString(Charsets.UTF_8).removePrefix(BOM).replace("\r\n", "\n")
        .split("\n\n").map { it.trimEnd() }.filter { it.isNotEmpty() }
}

/**
 * Xem bản chép lời trong app (D61): chỉ đọc, chọn và chép được chữ từng đoạn văn; nút Mở bằng app khác và Chia sẻ (D62).
 * Mỗi đoạn văn một dòng của danh sách lười, để bản chép lời 2 giờ vẫn cuộn mượt. [location]: URI SAF hoặc đường dẫn.
 */
@Composable
fun TranscriptScreen(location: String, name: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    var paragraphs by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(location) {
        try {
            paragraphs = withContext(Dispatchers.IO) { readParagraphs(context, location) }
        } catch (e: Exception) {
            GuzzLog.warning("Không đọc được bản chép lời $location: ${e.message}")
            error = e.message ?: e.javaClass.simpleName
        }
    }
    PageScaffold(name ?: stringResource(R.string.transcript_title), onBack) { modifier ->
        Box(modifier, contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    OutlinedButton(onClick = { openWithOtherApp(context, location) }) { Text(stringResource(R.string.queue_open_with)) }
                    OutlinedButton(onClick = { shareTranscript(context, location, name) }) { Text(stringResource(R.string.queue_share)) }
                }
                error?.let {
                    Text(
                        stringResource(R.string.transcript_read_error, it), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                SelectionContainer {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        items(paragraphs.orEmpty()) { p -> Text(p, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
    }
}

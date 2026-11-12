package io.github.cozycode890.guzz

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.ui.theme.GuzzTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Xem bản chép lời trong app (D61): chỉ đọc, chọn và chép được chữ từng đoạn văn; nút Mở bằng app khác và Chia sẻ (D62).
 * Mỗi đoạn văn một dòng của danh sách lười, để bản chép lời 2 giờ vẫn cuộn mượt.
 */
class TranscriptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME)
        setContent {
            GuzzTheme {
                TranscriptScreen(location, name)
            }
        }
    }

    companion object {
        private const val EXTRA_LOCATION = "location"
        private const val EXTRA_NAME = "name"

        /** [location]: URI SAF hoặc đường dẫn (thư mục ra của bản debug). */
        fun intent(context: Context, location: String, name: String?): Intent =
            Intent(context, TranscriptActivity::class.java).putExtra(EXTRA_LOCATION, location).putExtra(EXTRA_NAME, name)
    }
}

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

@Composable
private fun TranscriptScreen(location: String, name: String?) {
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
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(name ?: location, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { openWithOtherApp(context, location) }) { Text(stringResource(R.string.queue_open_with)) }
                OutlinedButton(onClick = { shareTranscript(context, location, name) }) { Text(stringResource(R.string.queue_share)) }
            }
            error?.let { Text(stringResource(R.string.transcript_read_error, it), color = MaterialTheme.colorScheme.error) }
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(paragraphs.orEmpty()) { p -> Text(p, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}

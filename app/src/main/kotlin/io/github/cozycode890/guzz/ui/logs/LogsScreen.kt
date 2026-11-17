package io.github.cozycode890.guzz.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.AppContainer
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.log.LogLevel
import io.github.cozycode890.guzz.core.common.log.LogView
import io.github.cozycode890.guzz.shareFile
import io.github.cozycode890.guzz.ui.components.Hint
import io.github.cozycode890.guzz.ui.components.MaxContentWidth
import io.github.cozycode890.guzz.ui.components.PageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Phần cuối `guzz.log` được đọc: đủ vài nghìn dòng mà không làm chậm trang. */
private const val TAIL_BYTES = 256L * 1024

/** Nhịp đọc lại tệp khi trang đang mở. */
private const val REFRESH_MS = 2_000L

/**
 * Cài đặt → Nhật ký (D3): đuôi `guzz.log`, lọc Tất cả / Cảnh báo / Lỗi như trang Nhật ký của desktop, ô tìm, tự làm mới
 * khi trang đang mở, nút Chia sẻ tệp log (gửi kèm khi báo lỗi).
 */
@Composable
fun LogsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var level by rememberSaveable { mutableStateOf(LogLevel.DEBUG.name) }
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        while (true) {
            text = withContext(Dispatchers.IO) { LogView.readTail(container.logFile, TAIL_BYTES) }
            delay(REFRESH_MS)
        }
    }
    val all = remember(text) { LogView.entries(text.orEmpty()) }
    val shown = remember(all, level, query) { LogView.filter(all, LogLevel.valueOf(level), query) }
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }

    PageScaffold(
        stringResource(R.string.logs_title), onBack,
        actions = {
            IconButton(onClick = { shareFile(context, container.logFile.path, container.logFile.name, "text/plain") }) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.logs_share))
            }
        },
    ) { modifier ->
        Box(modifier.imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(horizontal = 16.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        LogLevel.DEBUG to R.string.logs_level_all,
                        LogLevel.WARNING to R.string.logs_level_warning,
                        LogLevel.ERROR to R.string.logs_level_error,
                    ).forEach { (l, label) ->
                        FilterChip(selected = level == l.name, onClick = { level = l.name }, label = { Text(stringResource(label)) })
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.logs_search)) }, modifier = Modifier.fillMaxWidth(),
                )
                when {
                    text == null -> Unit
                    all.isEmpty() -> Hint(stringResource(R.string.logs_empty), Modifier.padding(vertical = 8.dp))
                    shown.isEmpty() -> Hint(stringResource(R.string.logs_no_match), Modifier.padding(vertical = 8.dp))
                }
                SelectionContainer {
                    LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(shown) { e ->
                            Text(
                                e.text,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (e.level) {
                                    LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                    LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

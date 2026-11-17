package io.github.cozycode890.guzz.feature.automation

import io.github.cozycode890.guzz.core.pipeline.ItemStatus
import io.github.cozycode890.guzz.core.pipeline.QueueItem
import io.github.cozycode890.guzz.core.pipeline.QueueState
import io.github.cozycode890.guzz.feature.automation.GuzzIntents as G
import kotlin.math.roundToInt

/** Một sự kiện sắp phát: [action] là `GuzzIntents.EVENT_*`, mọi extra là chuỗi (app nào cũng đọc được). */
data class AutomationEvent(val action: String, val extras: Map<String, String>) {
    val requestId: String? get() = extras[G.EXTRA_REQUEST_ID]?.takeIf { it.isNotEmpty() }

    override fun toString() = "${G.shortName(action)} ${extras.filterKeys { it != G.EXTRA_PACKAGE }}"
}

/** Dựng sự kiện từ một mục hàng đợi / trạng thái. [includePaths] = công tắc "Kèm đường dẫn trong sự kiện" (D97). */
object EventFactory {
    fun fileStarted(item: QueueItem, includePaths: Boolean) = AutomationEvent(G.EVENT_FILE_STARTED, base(item, includePaths))

    fun fileDone(item: QueueItem, includePaths: Boolean): AutomationEvent {
        val e = base(item, includePaths)
        item.outFile?.takeIf { includePaths }?.let { out ->
            if (out.startsWith("/")) e[G.EXTRA_OUTPUT_PATH] = out else e[G.EXTRA_OUTPUT_URI] = out
        }
        item.durationSec?.let { e[G.EXTRA_DURATION_SEC] = "%.1f".format(java.util.Locale.ROOT, it) }
        item.charCount?.let { e[G.EXTRA_CHARS] = it.toString() }
        item.modelsUsed?.let { e[G.EXTRA_MODELS] = it.joinToString(",") }
        e[G.EXTRA_SKIPPED] = (item.status == ItemStatus.SKIPPED).toString()
        return AutomationEvent(G.EVENT_FILE_DONE, e)
    }

    fun fileFailed(item: QueueItem, includePaths: Boolean) = AutomationEvent(
        G.EVENT_FILE_FAILED,
        base(item, includePaths).apply {
            put(G.EXTRA_ERROR, item.error.orEmpty())
            put(G.EXTRA_ERROR_KIND, item.errorKind.orEmpty())
        },
    )

    /** Tệp không thêm được vào hàng đợi (không đọc được, không thấy). [source] chỉ có khi [includePaths]. */
    fun sourceFailed(requestId: String?, source: String, error: String, kind: String, includePaths: Boolean) =
        AutomationEvent(
            G.EVENT_FILE_FAILED,
            linkedMapOf(
                G.EXTRA_REQUEST_ID to requestId.orEmpty(),
                G.EXTRA_NAME to displayName(source),
            ).apply {
                if (includePaths) put(G.EXTRA_SOURCE, source)
                put(G.EXTRA_ERROR, error)
                put(G.EXTRA_ERROR_KIND, kind)
            },
        )

    fun error(requestId: String?, command: String?, error: String, kind: String) = AutomationEvent(
        G.EVENT_ERROR,
        linkedMapOf(
            G.EXTRA_REQUEST_ID to requestId.orEmpty(),
            G.EXTRA_COMMAND to G.shortName(command),
            G.EXTRA_ERROR to error,
            G.EXTRA_ERROR_KIND to kind,
        ),
    )

    fun queueFinished(done: Int, failed: Int, skipped: Int, stopped: Boolean) = AutomationEvent(
        G.EVENT_QUEUE_FINISHED,
        linkedMapOf(
            G.EXTRA_DONE to done.toString(), G.EXTRA_FAILED to failed.toString(),
            G.EXTRA_SKIPPED to skipped.toString(), G.EXTRA_STOPPED to stopped.toString(),
        ),
    )

    /** Trả lời lệnh STATUS (D92): thay cho sự kiện tiến độ. [active] = vòng chạy đang sống. */
    fun status(requestId: String?, state: QueueState, active: Boolean): AutomationEvent {
        val current = state.items.firstOrNull { it.status == ItemStatus.RUNNING }
        val e = linkedMapOf(
            G.EXTRA_REQUEST_ID to requestId.orEmpty(),
            G.EXTRA_RUNNING to active.toString(),
            G.EXTRA_WAITING to state.items.count { it.status == ItemStatus.WAITING }.toString(),
            G.EXTRA_DONE to state.items.count { it.status == ItemStatus.DONE }.toString(),
            G.EXTRA_FAILED to state.items.count { it.status == ItemStatus.ERROR }.toString(),
            G.EXTRA_CURRENT to current?.displayName.orEmpty(),
            G.EXTRA_PROGRESS to ((current?.progress ?: 0.0) * 100).roundToInt().toString(),
            G.EXTRA_CHUNK to current?.chunk?.toString().orEmpty(),
            G.EXTRA_CHUNK_TOTAL to current?.chunkTotal?.toString().orEmpty(),
        )
        return AutomationEvent(G.EVENT_STATUS, e)
    }

    private fun base(item: QueueItem, includePaths: Boolean): LinkedHashMap<String, String> = linkedMapOf(
        G.EXTRA_REQUEST_ID to item.requestId.orEmpty(),
        G.EXTRA_NAME to item.displayName,
    ).apply { if (includePaths) put(G.EXTRA_SOURCE, item.source) }

    fun displayName(source: String): String =
        source.trimEnd('/').substringAfterLast('/').let { java.net.URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
            .substringAfterLast('/').substringAfterLast(':').ifEmpty { source }
}

/**
 * So trạng thái hàng đợi cũ / mới để ra sự kiện (Stage 6 C1). Nghe `QueueController.state` (StateFlow, có thể gộp bớt
 * trạng thái giữa chừng) nên chỉ dựa vào chuyển trạng thái của từng mục theo `id`:
 *
 * - vào `dang_chay` → FILE_STARTED (chạy quá nhanh mà không thấy thì thiếu sự kiện này, các sự kiện sau vẫn có);
 * - vào `xong` / `bo_qua` → FILE_DONE (`skipped`), vào `loi` → FILE_FAILED;
 * - cờ chạy tắt (hoặc đã có tệp xong / lỗi mà không còn gì chạy) → QUEUE_FINISHED với số đếm từ lần trước.
 *
 * Trạng thái đầu tiên (lúc process mới khởi động) chỉ là mốc: không phát lại sự kiện cũ.
 */
class EventTracker {
    private var last: QueueState? = null
    private var done = 0
    private var failed = 0
    private var skipped = 0
    private var stopped = false

    @Synchronized
    fun update(next: QueueState, includePaths: Boolean): List<AutomationEvent> {
        val prev = last
        last = next
        if (prev == null) return emptyList()
        if (next.running && !prev.running) reset()
        val before = prev.items.associateBy { it.id }
        val out = ArrayList<AutomationEvent>()
        for (item in next.items) {
            val old = before[item.id]?.status ?: ItemStatus.WAITING
            if (old == item.status) continue
            when (item.status) {
                ItemStatus.RUNNING -> out += EventFactory.fileStarted(item, includePaths)
                ItemStatus.DONE -> {
                    done++
                    out += EventFactory.fileDone(item, includePaths)
                }
                ItemStatus.SKIPPED -> {
                    skipped++
                    out += EventFactory.fileDone(item, includePaths)
                }
                ItemStatus.ERROR -> {
                    failed++
                    out += EventFactory.fileFailed(item, includePaths)
                }
                ItemStatus.STOPPED -> stopped = true
                ItemStatus.WAITING -> Unit
            }
        }
        val anyRunning = next.items.any { it.status == ItemStatus.RUNNING }
        if (!next.running) {
            if (prev.running) {
                // Bấm Dừng: cờ chạy tắt trước khi tệp đang chạy về `dung`, nên tệp còn "đang chạy" nghĩa là đã dừng.
                out += EventFactory.queueFinished(done, failed, skipped, stopped || anyRunning)
                reset()
            } else if (done + failed + skipped > 0 && !anyRunning) {
                // Cả vòng chạy lọt giữa hai lần đọc StateFlow: vẫn báo xong hàng đợi.
                out += EventFactory.queueFinished(done, failed, skipped, stopped)
                reset()
            }
        }
        return out
    }

    private fun reset() {
        done = 0
        failed = 0
        skipped = 0
        stopped = false
    }
}

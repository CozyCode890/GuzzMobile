package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.log.Logger
import io.github.cozycode890.guzz.core.gemini.ChunkTranscriber
import io.github.cozycode890.guzz.core.gemini.ConstraintException
import io.github.cozycode890.guzz.core.gemini.GoogleAIException
import io.github.cozycode890.guzz.core.gemini.QuotaTracker
import io.github.cozycode890.guzz.core.gemini.constraintError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Trạng thái một tệp trong hàng đợi; mã ghi vào tệp JSON viết không dấu như desktop. */
@Serializable
enum class ItemStatus {
    @SerialName("cho") WAITING,
    @SerialName("dang_chay") RUNNING,
    @SerialName("xong") DONE,
    @SerialName("loi") ERROR,
    @SerialName("dung") STOPPED,
    @SerialName("bo_qua") SKIPPED,
    ;

    /** Bấm Bắt đầu thì chạy các tệp này. Port của gui/pages/transcribe.py: `_RUNNABLE`. */
    val runnable: Boolean get() = this == WAITING || this == ERROR || this == STOPPED
}

/**
 * Một tệp trong hàng đợi.
 *
 * @param source đường dẫn tuyệt đối hoặc content:// URI.
 * @param stage / [step] / [chunk] / [chunkTotal] / [model]: chi tiết tiến độ để giao diện hiện (`go_chu`, đoạn 2/6…).
 * @param tempDir thư mục tạm của tệp (biết khi bắt đầu chạy), để xoá khi bỏ khỏi danh sách (D66).
 * @param chunkDelaySec chỉ bản debug (D67): chờ giữa các đoạn.
 */
@Serializable
data class QueueItem(
    val id: String = UUID.randomUUID().toString(),
    val source: String,
    val displayName: String,
    val mimeType: String? = null,
    val durationSec: Double? = null,
    val status: ItemStatus = ItemStatus.WAITING,
    val progress: Double = 0.0,
    val stage: String? = null,
    val step: String? = null,
    val chunk: Int? = null,
    val chunkTotal: Int? = null,
    val model: String? = null,
    val outFile: String? = null,
    val outName: String? = null,
    val error: String? = null,
    val errorKind: String? = null,
    val tempDir: String? = null,
    val chunkDelaySec: Double = 0.0,
)

/** Cả hàng đợi. [running]: đang chạy (hoặc bị giết giữa chừng); mở lại app thì tự chạy tiếp (D64). */
@Serializable
data class QueueState(
    val running: Boolean = false,
    val items: List<QueueItem> = emptyList(),
)

/**
 * Lưu hàng đợi bằng tệp JSON (D63): ghi `.tmp` rồi đổi tên, nên bị giết lúc đang ghi thì vẫn còn bản cũ. Tệp hỏng thì
 * hàng đợi rỗng (đổi tên tệp hỏng thành `.hong` để xem lại) và ghi log, không ném lỗi.
 */
class QueueStore(private val file: File?, private val logger: Logger = GuzzLog) {
    private val lock = Any()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private fun load(): QueueState {
        if (file == null || !file.isFile) return QueueState()
        return try {
            json.decodeFromString(QueueState.serializer(), file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            logger.warning("Tệp hàng đợi $file hỏng (${e.message}), bắt đầu với hàng đợi rỗng.")
            file.renameTo(File(file.path + ".hong"))
            QueueState()
        }
    }

    /** Đổi trạng thái; [persist] = false chỉ đổi trong bộ nhớ (tiến độ trong một đoạn, ghi thưa). */
    fun update(persist: Boolean = true, transform: (QueueState) -> QueueState): QueueState = synchronized(lock) {
        val next = transform(_state.value)
        _state.value = next
        if (persist) save(next)
        next
    }

    fun updateItem(id: String, persist: Boolean = true, transform: (QueueItem) -> QueueItem) = update(persist) { s ->
        s.copy(items = s.items.map { if (it.id == id) transform(it) else it })
    }

    private fun save(state: QueueState) {
        if (file == null) return
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(QueueState.serializer(), state), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            logger.error("Không ghi được tệp hàng đợi $file: ${e.message}", e)
        }
    }

    companion object {
        const val FILENAME = "hang_doi.json"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }
}

/** Thứ hàng đợi cần từ app: cài đặt, client, nguồn, thư mục lưu. Trên máy là `AppContainer`, test thay bằng bản giả. */
interface QueueEnvironment {
    /** Đọc lại cài đặt (mỗi tệp một lần, như desktop). */
    suspend fun loadConfig(): PipelineConfig

    /** Client Gemini; `null` = chưa có key. */
    fun client(cfg: PipelineConfig): ChunkTranscriber?

    /** Sổ hạn mức, để kiểm trước khi bắt đầu (mọi model đều khoá lâu). */
    val tracker: QuotaTracker

    fun source(item: QueueItem): ChunkSource

    /** Thư mục lưu theo cài đặt; chưa chọn hoặc không dùng được thì ném [PipelineException] (dừng hàng đợi). */
    fun output(cfg: PipelineConfig, item: QueueItem): OutputFolder
}

/** Lý do không bắt đầu được hàng đợi. Port các bước kiểm của gui/pages/transcribe.py: `_start`. */
sealed class StartProblem(val message: String) {
    class NothingToDo : StartProblem("Không có tệp nào để chạy.")
    class NoApiKey : StartProblem("Chưa có API key của Google AI Studio. Nhập key ở trang Google AI.")
    class Constraint(message: String) : StartProblem(message)
    class AllModelsLocked(message: String) : StartProblem(message)
    class Config(message: String) : StartProblem(message)
}

/** Kết quả một lượt chạy hàng đợi, cho thông báo tổng kết. */
data class QueueSummary(val done: Int, val failed: Int, val skipped: Int, val stopped: Boolean)

/**
 * Chạy lần lượt từng tệp (D60). Port của gui/threads.py: `TranscribeThread.run` và phần kiểm trước của
 * gui/pages/transcribe.py: `_start`.
 *
 * - Thêm tệp lúc đang chạy được: vòng lặp lấy tệp `cho` kế tiếp mỗi lần.
 * - Lỗi có `stopQueue` (key sai, mọi model khoá lâu…) hay ràng buộc model ↔ người nói: dừng cả hàng đợi.
 * - Lỗi khác: tệp lỗi; chạy tiếp hay dừng theo `[QUEUE] on_file_error`.
 * - Dừng (huỷ coroutine): tệp đang chạy về `dung`, hoặc về `cho` và giữ cờ đang chạy khi dừng vì hệ thống (onTimeout,
 *   D64) để mở app thì làm tiếp.
 */
class QueueRunner(
    val store: QueueStore,
    private val env: QueueEnvironment,
    private val pipeline: FilePipeline,
    private val logger: Logger = GuzzLog,
) {
    val state: StateFlow<QueueState> get() = store.state

    @Volatile
    private var resumeAfterStop = false

    /**
     * Sau khi tiến trình bị giết giữa chừng: tệp `dang_chay` về `cho`. Trả về cờ "đang chạy" của hàng đợi: `true` thì
     * nên tự chạy tiếp (D64).
     */
    fun recover(): Boolean {
        val s = store.update { s ->
            s.copy(items = s.items.map { if (it.status == ItemStatus.RUNNING) it.copy(status = ItemStatus.WAITING) else it })
        }
        return s.running && s.items.any { it.status == ItemStatus.WAITING }
    }

    /** Thêm tệp; tệp trùng nguồn với một tệp đang chờ / đang chạy thì bỏ qua. Trả về các mục đã thêm. */
    fun add(items: List<QueueItem>): List<QueueItem> {
        val added = ArrayList<QueueItem>()
        store.update { s ->
            val pending = s.items.filter { it.status == ItemStatus.WAITING || it.status == ItemStatus.RUNNING }
                .mapTo(HashSet()) { it.source }
            for (item in items) if (pending.add(item.source)) added += item
            s.copy(items = s.items + added)
        }
        return added
    }

    /** Bỏ khỏi danh sách (không bỏ được tệp đang chạy) và xoá thư mục tạm của nó (D66). Trả về mục đã bỏ. */
    fun remove(id: String): QueueItem? {
        var removed: QueueItem? = null
        store.update { s ->
            removed = s.items.firstOrNull { it.id == id && it.status != ItemStatus.RUNNING }
            s.copy(items = s.items.filter { it !== removed })
        }
        removed?.tempDir?.let { File(it).deleteRecursively() }
        return removed
    }

    /** Bỏ các tệp đã xong / bỏ qua. Trả về các mục đã bỏ. */
    fun clearFinished(): List<QueueItem> {
        var removed = emptyList<QueueItem>()
        store.update { s ->
            removed = s.items.filter { it.status == ItemStatus.DONE || it.status == ItemStatus.SKIPPED }
            s.copy(items = s.items - removed.toSet())
        }
        removed.forEach { item -> item.tempDir?.let { File(it).deleteRecursively() } }
        return removed
    }

    /** Chạy lại một tệp (kể cả đã xong): về `cho`, xoá kết quả và lỗi cũ. */
    fun rerun(id: String) {
        store.updateItem(id) { if (it.status == ItemStatus.RUNNING) it else resetForRun(it) }
    }

    private fun resetForRun(item: QueueItem) = item.copy(
        status = ItemStatus.WAITING, progress = 0.0, stage = null, step = null, chunk = null, chunkTotal = null,
        model = null, error = null, errorKind = null,
    )

    /** Đánh dấu cách dừng sắp tới: `true` = dừng vì hệ thống, giữ cờ đang chạy để làm tiếp (D64). Gọi trước khi huỷ. */
    fun prepareStop(resumeLater: Boolean) {
        resumeAfterStop = resumeLater
    }

    /** Đã bấm Dừng (hoặc chưa chạy): bỏ cờ đang chạy để không tự chạy lại. */
    fun clearRunningFlag() {
        store.update { it.copy(running = false) }
    }

    /** Kiểm trước khi bắt đầu, như `_start` của desktop: có tệp để chạy, có key, ràng buộc, còn model dùng được. */
    suspend fun checkStart(): StartProblem? {
        if (state.value.items.none { it.status.runnable }) return StartProblem.NothingToDo()
        val cfg = try {
            env.loadConfig()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return StartProblem.Config(e.message ?: e.toString())
        }
        if (env.client(cfg) == null) return StartProblem.NoApiKey()
        constraintError(cfg.gemini)?.let { return StartProblem.Constraint(it) }
        return allLockedProblem(cfg)
    }

    /** Mọi model trong chuỗi đều khoá lâu hơn `max_all_locked_wait_min`. Port của transcribe.py (GUI): `_has_usable_model`. */
    private fun allLockedProblem(cfg: PipelineConfig): StartProblem? {
        val tracker = env.tracker
        val unlockTimes = ArrayList<Double>()
        for (m in cfg.gemini.modelChain()) {
            val status = tracker.lockStatus(m)
            if (!status.locked) return null
            status.until?.let { unlockTimes += it }
        }
        val earliest = unlockTimes.minOrNull()
        if (earliest != null && earliest - tracker.clock() <= cfg.gemini.maxAllLockedWaitMin * 60) return null
        val describe = cfg.gemini.modelChain().joinToString("; ") { m ->
            val s = tracker.lockStatus(m)
            "$m khoá (${s.reason})"
        }
        return StartProblem.AllModelsLocked("Mọi model dùng được đều đang khoá lâu: $describe.")
    }

    /**
     * Chạy tới khi hết tệp `cho`, hoặc dừng vì lỗi. Bắt đầu: mọi tệp chạy được (`cho`, `loi`, `dung`) về `cho` và bật cờ
     * đang chạy. Huỷ coroutine (Dừng) thì ném lại `CancellationException` sau khi ghi trạng thái.
     */
    suspend fun run(): QueueSummary {
        resumeAfterStop = false
        store.update { s ->
            s.copy(running = true, items = s.items.map { if (it.status.runnable) resetForRun(it) else it })
        }
        var done = 0
        var failed = 0
        var skipped = 0
        var cfg: PipelineConfig? = null
        var current: QueueItem? = null
        try {
            while (true) {
                val item = state.value.items.firstOrNull { it.status == ItemStatus.WAITING } ?: break
                current = item
                cfg = try {
                    env.loadConfig()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (cfg == null) {
                        fail(item, "Cài đặt: ${e.message}", "cau_hinh")
                        failed++
                        break
                    }
                    logger.error("Không đọc được cài đặt (${e.message}), dùng cài đặt cũ.")
                    cfg
                }
                val c = cfg!!
                store.updateItem(item.id) { it.copy(status = ItemStatus.RUNNING, progress = 0.0, error = null, errorKind = null) }
                val stop = try {
                    val source = env.source(item)
                    val info = source.info()
                    store.updateItem(item.id) { it.copy(tempDir = pipeline.tempDirFor(info).path) }
                    val output = env.output(c, item)
                    val result = pipeline.run(source, output, c, env.client(c), item.chunkDelaySec) { p ->
                        onProgress(item.id, p)
                    }
                    store.updateItem(item.id) {
                        it.copy(
                            status = if (result.skipped) ItemStatus.SKIPPED else ItemStatus.DONE, progress = 1.0,
                            outFile = result.outFile ?: it.outFile, outName = result.outName ?: it.outName,
                            stage = null, step = null,
                        )
                    }
                    if (result.skipped) skipped++ else done++
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GoogleAIException) {
                    failed++
                    logger.error("Google AI báo lỗi khi chuyển ${item.displayName}: ${e.message}")
                    fail(item, e.message ?: "", e.kind.code)
                    if (e.stopQueue) logger.error("Lỗi này sẽ lặp lại ở mọi tệp: dừng hàng đợi.")
                    e.stopQueue || c.onFileError == PipelineConfig.ON_ERROR_STOP
                } catch (e: ConstraintException) {
                    failed++
                    logger.error(e.message ?: "")
                    fail(item, e.message ?: "", "rang_buoc")
                    true
                } catch (e: PipelineException) {
                    failed++
                    logger.error("Lỗi khi chuyển ${item.displayName}: ${e.message}")
                    fail(item, e.message ?: "", if (e.stopQueue) "cau_hinh" else "khac")
                    if (e.stopQueue) logger.error("Lỗi này sẽ lặp lại ở mọi tệp: dừng hàng đợi.")
                    e.stopQueue || c.onFileError == PipelineConfig.ON_ERROR_STOP
                } catch (e: Exception) {
                    failed++
                    logger.error("Lỗi khi chuyển ${item.displayName}: ${e.message}", e)
                    fail(item, "${e.javaClass.simpleName}: ${e.message}", "khac")
                    c.onFileError == PipelineConfig.ON_ERROR_STOP
                }
                current = null
                if (stop) break
            }
        } catch (e: CancellationException) {
            val resume = resumeAfterStop
            current?.let { item ->
                logger.info(
                    "Đã dừng giữa chừng ${item.displayName}. " +
                        if (resume) "Mở app thì tự làm tiếp từ đoạn đang dở." else "Bấm Bắt đầu để làm tiếp từ đoạn đang dở.",
                )
                store.updateItem(item.id) { it.copy(status = if (resume) ItemStatus.WAITING else ItemStatus.STOPPED) }
            }
            store.update { it.copy(running = resume) }
            throw e
        }
        store.update { it.copy(running = false) }
        logger.info("Hàng đợi xong: $done tệp xong, $failed tệp lỗi, $skipped tệp bỏ qua.")
        return QueueSummary(done, failed, skipped, stopped = false)
    }

    private fun fail(item: QueueItem, message: String, kind: String) {
        store.updateItem(item.id) { it.copy(status = ItemStatus.ERROR, error = message, errorKind = kind, step = null) }
    }

    /** Tiến độ: luôn cập nhật trong bộ nhớ, chỉ ghi tệp khi đổi bước hoặc đoạn (ghi thưa). */
    private fun onProgress(id: String, p: FileProgress) {
        val before = state.value.items.firstOrNull { it.id == id } ?: return
        val changed = before.stage != p.stage.code || before.chunk != p.chunk || before.model != p.model
        store.updateItem(id, persist = changed) {
            it.copy(
                progress = p.fraction, stage = p.stage.code, step = p.step ?: it.step.takeIf { p.stage == Stage.CLEAN },
                chunk = p.chunk ?: it.chunk.takeIf { p.stage == Stage.TRANSCRIBE },
                chunkTotal = p.total ?: it.chunkTotal, model = p.model ?: it.model,
            )
        }
    }
}

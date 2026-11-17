package io.github.cozycode890.guzz.feature.queue

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.log.Logger
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.core.gemini.ChunkTranscriber
import io.github.cozycode890.guzz.core.gemini.QuotaTracker
import io.github.cozycode890.guzz.core.pipeline.DirectoryOutputFolder
import io.github.cozycode890.guzz.core.diarization.AndroidSpeakerDiarizer
import io.github.cozycode890.guzz.core.pipeline.FilePipeline
import io.github.cozycode890.guzz.core.pipeline.ItemOverrides
import io.github.cozycode890.guzz.core.pipeline.ItemStatus
import io.github.cozycode890.guzz.core.pipeline.OutputChoice
import io.github.cozycode890.guzz.core.pipeline.OutputFolder
import io.github.cozycode890.guzz.core.pipeline.PipelineConfig
import io.github.cozycode890.guzz.core.pipeline.PipelineException
import io.github.cozycode890.guzz.core.pipeline.QueueEnvironment
import io.github.cozycode890.guzz.core.pipeline.QueueItem
import io.github.cozycode890.guzz.core.pipeline.QueueRunner
import io.github.cozycode890.guzz.core.pipeline.QueueState
import io.github.cozycode890.guzz.core.pipeline.QueueStore
import io.github.cozycode890.guzz.core.pipeline.QueueSummary
import io.github.cozycode890.guzz.core.pipeline.StartProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Điều khiển hàng đợi cho cả app: thêm, Bắt đầu, Dừng, Bỏ, Chạy lại; `StateFlow` cho giao diện. Stage 6 (intent) cũng
 * gọi vào đây. Vòng chạy thật nằm trong [QueueService] (foreground service), dùng [launchRun].
 *
 * @param clientFactory client Gemini cho một tệp (dựng lại mỗi tệp, như desktop đọc lại key); `null` = chưa có key.
 * @param fallbackOutput thư mục ra khi chưa chọn thư mục SAF: chỉ bản debug (`files/output`, lệnh adb), bản thật `null`.
 * @param cacheSalt mã thêm cho kết quả đoạn theo cấu hình của tệp (Gemini giả của bản debug).
 * @param allFilesAccess đang có quyền "Truy cập mọi tệp" (Stage 6): mới ghi bằng đường dẫn được (D96).
 */
class QueueController(
    private val context: Context,
    private val settings: Settings,
    private val tracker: () -> QuotaTracker,
    private val clientFactory: (PipelineConfig) -> ChunkTranscriber?,
    private val fallbackOutput: File? = null,
    private val cacheSalt: (PipelineConfig) -> String = { "" },
    private val allFilesAccess: () -> Boolean = { false },
    private val logger: Logger = GuzzLog,
) {
    /** Gốc thư mục tạm (mục "Mặc định kỹ thuật"): không phải `cacheDir`, hệ thống không tự xoá; `adb pull` xem được. */
    val tempRoot = File(context.getExternalFilesDir(null) ?: context.filesDir, "tmp")

    // Tách giọng trên máy (Stage 5): sherpa-onnx trong process :diarize, Dừng thì giết process đó.
    private val pipeline = FilePipeline(
        tempRoot, logger, diarizer = AndroidSpeakerDiarizer(context, log = { logger.info(it) }),
    )

    private val env = object : QueueEnvironment {
        override suspend fun loadConfig() = PipelineConfig.load(settings)
        override fun client(cfg: PipelineConfig) = clientFactory(cfg)
        override val tracker: QuotaTracker get() = this@QueueController.tracker()
        override fun source(item: QueueItem) = AndroidChunkSource(context, item.source)
        override fun output(cfg: PipelineConfig, item: QueueItem): OutputFolder {
            // D96: output_dir của lệnh / "Lưu cạnh tệp nguồn" chỉ khi có quyền "Truy cập mọi tệp"; không thì thư mục SAF.
            val choice = OutputChoice.choose(item.source, item.overrides?.outputDir, cfg.saveBesideSource, allFilesAccess())
            choice.note?.let { logger.warning("${item.displayName}: $it.") }
            (choice.target as? OutputChoice.Directory)?.let { return DirectoryOutputFolder(File(it.dir)) }
            return when {
                cfg.outputDir.isNotBlank() -> SafOutputFolder(context, Uri.parse(cfg.outputDir))
                fallbackOutput != null -> DirectoryOutputFolder(fallbackOutput)
                else -> throw PipelineException("Chưa chọn thư mục lưu bản chép lời.", stopQueue = true)
            }
        }
        override fun cacheSalt(cfg: PipelineConfig) = this@QueueController.cacheSalt(cfg)
    }

    private val runner = QueueRunner(QueueStore(File(context.filesDir, "queue/${QueueStore.FILENAME}"), logger), env, pipeline, logger)

    init {
        // Tiến trình mới: tệp còn "đang chạy" là của lần bị giết trước (D64).
        runner.recover()
    }

    val state: StateFlow<QueueState> get() = runner.state

    private val _active = MutableStateFlow(false)

    /** Vòng chạy đang sống (trong service). Khác `state.running`: cờ đó còn bật sau khi bị giết, để tự làm tiếp. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile
    private var job: Job? = null

    // ------------------------------------------------------------------ thêm / bỏ

    /**
     * Thêm tệp từ trình chọn tệp, Chia sẻ tới Guzz hay lệnh của app khác (Stage 6); giữ quyền đọc URI để đọc lại được sau
     * khi app bị giết (D64). URI tài liệu trong Thư mục nguồn (D104) không giữ quyền riêng được nhưng đọc được nhờ quyền
     * của thư mục.
     */
    fun addUris(uris: List<Uri>, requestId: String? = null, overrides: ItemOverrides? = null): List<QueueItem> {
        val items = uris.mapNotNull { uri ->
            try {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val info = querySource(context, uri.toString())
                QueueItem(
                    source = uri.toString(), displayName = info.displayName, mimeType = info.mimeType,
                    durationSec = duration(uri.toString()), requestId = requestId, overrides = overrides,
                )
            } catch (e: Exception) {
                logger.warning("Không thêm được $uri: ${e.message}")
                // Không thêm được thì trả quyền vừa giữ, kẻo quyền bền tích lại mãi.
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                null
            }
        }
        return add(items)
    }

    /** Thêm đường dẫn tuyệt đối (lệnh adb của bản debug; Stage 6: intent khi có quyền "Truy cập mọi tệp"). */
    fun addPath(
        file: File,
        chunkDelaySec: Double = 0.0,
        requestId: String? = null,
        overrides: ItemOverrides? = null,
        ownedCopy: Boolean = false,
        displayName: String = file.name,
    ): List<QueueItem> {
        if (!file.isFile) {
            logger.warning("Không thêm được ${file.path}: không thấy tệp.")
            return emptyList()
        }
        return add(
            listOf(
                QueueItem(
                    source = file.path, displayName = displayName, mimeType = mimeFromName(displayName),
                    durationSec = duration(file.path), chunkDelaySec = chunkDelaySec, requestId = requestId,
                    overrides = overrides, ownedCopy = ownedCopy,
                ),
            ),
        )
    }

    private fun add(items: List<QueueItem>): List<QueueItem> {
        val added = runner.add(items)
        items.filter { it !in added }.forEach { item ->
            logger.info("Bỏ qua ${item.displayName}: đã có trong hàng đợi.")
            releaseIfUnused(item.source)
        }
        added.forEach { logger.info("Thêm vào hàng đợi: ${it.displayName}") }
        return added
    }

    private fun duration(source: String): Double? = try {
        MediaMetadataRetriever().run {
            try {
                if (source.startsWith("/")) setDataSource(source) else setDataSource(context, Uri.parse(source))
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000.0)
            } finally {
                release()
            }
        }
    } catch (e: Exception) {
        null
    }

    fun remove(id: String) {
        runner.remove(id)?.let {
            releaseIfUnused(it.source)
            deleteCopyIfUnused(it)
        }
    }

    fun clearFinished() {
        runner.clearFinished().forEach {
            releaseIfUnused(it.source)
            deleteCopyIfUnused(it)
        }
    }

    /** Tệp được chia sẻ mà Guzz phải tự chép (D103): xoá bản chép khi không còn mục nào dùng nó. */
    private fun deleteCopyIfUnused(item: QueueItem) {
        if (!item.ownedCopy || state.value.items.any { it.source == item.source }) return
        deleteCopy(item)
    }

    private fun deleteCopy(item: QueueItem) {
        val f = File(item.source)
        if (f.exists() && f.delete()) logger.info("Xoá bản chép của ${item.displayName}.")
        f.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
    }

    /** Xong vòng chạy: tệp xong mà là bản chép thì xoá bản chép theo `[SYSTEM] delete_temp_when_done` (D103). */
    private suspend fun deleteFinishedCopies() {
        if (!settings.get(SettingKeys.DELETE_TEMP_WHEN_DONE)) return
        state.value.items.filter { it.ownedCopy && it.status == ItemStatus.DONE }.forEach(::deleteCopy)
    }

    fun rerun(id: String) = runner.rerun(id)

    /** Trả quyền đọc URI khi không còn mục nào dùng nó (mục "Mặc định kỹ thuật"). */
    private fun releaseIfUnused(source: String) {
        if (source.startsWith("/") || state.value.items.any { it.source == source }) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(source), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ------------------------------------------------------------------ chạy / dừng

    /** Kiểm trước khi bắt đầu (như `_start` của desktop); `null` = bắt đầu được. */
    suspend fun checkStart(): StartProblem? = runner.checkStart()

    /** Bắt đầu: khởi động [QueueService] (service tự gọi [launchRun]). */
    fun start() {
        QueueService.start(context)
    }

    /** Bấm Dừng: tệp đang chạy về `dung`, không tự chạy lại. */
    fun stop() {
        val j = job
        if (j == null || !j.isActive) {
            runner.clearRunningFlag()
            return
        }
        logger.info("Người dùng bấm Dừng.")
        runner.prepareStop(resumeLater = false)
        j.cancel()
    }

    /** Hệ thống bắt dừng (`onTimeout`, service bị huỷ): giữ cờ đang chạy để mở app thì làm tiếp (D64). */
    fun stopForSystem() {
        runner.prepareStop(resumeLater = true)
        job?.cancel()
    }

    /** Hàng đợi bị giết khi đang chạy dở (cờ đang chạy còn bật) mà chưa có vòng chạy nào: nên tự chạy tiếp (D64). */
    fun shouldResume(): Boolean = job?.isActive != true && state.value.running && state.value.items.any { it.status.runnable }

    /** `[QUEUE] notify_when_done`: xong hàng đợi thì có thông báo tổng kết. */
    suspend fun notifyWhenDone(): Boolean = settings.get(SettingKeys.NOTIFY_WHEN_DONE)

    /** Gọi khi mở app (D64). */
    fun resumeIfNeeded() {
        if (shouldResume()) {
            logger.info("Hàng đợi đang chạy dở lúc app bị tắt: tự làm tiếp.")
            start()
        }
    }

    /**
     * Chạy hàng đợi trong [scope] của service. Chỉ một vòng một lúc; [onFinished] nhận tổng kết (`null` khi bị dừng) và
     * lỗi lạ nếu có.
     */
    internal fun launchRun(scope: CoroutineScope, onFinished: (QueueSummary?, Throwable?) -> Unit): Boolean {
        if (job?.isActive == true) return false
        _active.value = true
        // LAZY: gán job trước khi chạy, để khối finally (đặt job = null) không bị phép gán này ghi đè.
        val j = scope.launch(start = CoroutineStart.LAZY) {
            var summary: QueueSummary? = null
            var error: Throwable? = null
            try {
                summary = runner.run()
                try {
                    deleteFinishedCopies()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.warning("Không xoá được bản chép của tệp đã xong: ${e.message}")
                }
            } catch (e: CancellationException) {
                summary = null
            } catch (e: Throwable) {
                logger.error("Hàng đợi dừng vì lỗi không mong đợi: ${e.message}", e)
                error = e
                runner.clearRunningFlag()
            } finally {
                job = null
                _active.value = false
                onFinished(summary, error)
            }
        }
        job = j
        j.start()
        return true
    }
}

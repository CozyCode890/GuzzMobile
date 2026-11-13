package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.audio.ChunkInfo
import io.github.cozycode890.guzz.core.audio.ChunkList
import io.github.cozycode890.guzz.core.audio.ChunkPreparer
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.log.Logger
import io.github.cozycode890.guzz.core.gemini.ChunkDispatcher
import io.github.cozycode890.guzz.core.gemini.ChunkTranscriber
import io.github.cozycode890.guzz.core.gemini.ConstraintException
import io.github.cozycode890.guzz.core.gemini.DefaultPrompt
import io.github.cozycode890.guzz.core.gemini.ErrorKind
import io.github.cozycode890.guzz.core.gemini.GeminiConfig
import io.github.cozycode890.guzz.core.gemini.GoogleAIException
import io.github.cozycode890.guzz.core.gemini.TranscribeOptions
import io.github.cozycode890.guzz.core.gemini.constraintError
import io.github.cozycode890.guzz.core.gemini.coroutineSleep
import io.github.cozycode890.guzz.core.gemini.extractText
import io.github.cozycode890.guzz.core.gemini.isTranscribeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** Các bước của một tệp (mã như desktop: `lam_sach`, `go_chu`, `noi_lai`). */
enum class Stage(val code: String) { CLEAN("lam_sach"), TRANSCRIBE("go_chu"), MERGE("noi_lai") }

/**
 * Tiến độ một tệp: [fraction] là của cả tệp (0..1). [step] là bước con của xử lý âm thanh; [chunk] / [total] / [model]
 * khi đang chép lời.
 */
data class FileProgress(
    val stage: Stage,
    val fraction: Double,
    val step: String? = null,
    val chunk: Int? = null,
    val total: Int? = null,
    val model: String? = null,
)

/** Kết quả một tệp. Port của transcribe.py: `FileResult` (chưa có số phút theo người nói, Stage 5). */
data class FileResult(
    val outFile: String? = null,
    val outName: String? = null,
    val skipped: Boolean = false,
    val chunkCount: Int = 0,
    val charCount: Int = 0,
    val origDuration: Double = 0.0,
    val models: Map<String, Int> = emptyMap(),
)

/**
 * Chuyển một tệp thành bản chép lời. Port của transcribe.py: `transcribe_single_file` + `prepare_chunks` (phần cache) +
 * `transcribe_chunks`, nhánh tắt nhận diện người nói.
 *
 * Làm lại một tệp vừa lỗi / dừng giữa chừng: tham số âm thanh không đổi thì dùng lại các đoạn đã cắt (`ma` trong
 * `danh_sach_doan.json`), đoạn nào đã có `doan_NNN.<mã>.json` thì không gửi lại.
 *
 * @param tempRoot gốc thư mục tạm; mỗi tệp một thư mục con `<tên>_<mã vân tay>` (không nằm trong `cacheDir` vì hệ thống
 * có thể xoá thư mục đó lúc đang làm dở).
 * @param sleep hàm chờ của bộ đổi model (chờ model mở khoá) và của chờ giữa các đoạn (bản debug, D67).
 */
class FilePipeline(
    private val tempRoot: File,
    private val logger: Logger = GuzzLog,
    private val sleep: suspend (Double) -> Unit = coroutineSleep,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun tempDirFor(info: SourceInfo): File = File(tempRoot, CacheKeys.tempDirName(info.displayName, fingerprint(info)))

    private fun fingerprint(info: SourceInfo) = CacheKeys.fingerprint(info.id, info.size, info.modifiedMillis)

    /**
     * Ném [GoogleAIException], [ConstraintException], [PipelineException], `CancellationException` (Dừng), hoặc lỗi
     * khác (đọc tệp, giải mã…).
     *
     * @param chunkDelaySec chỉ bản debug (D67): chờ chừng đó giây trước mỗi đoạn phải gửi, trừ đoạn đầu.
     * @param cacheSalt chỉ bản debug: khác rỗng khi dùng Gemini giả, để kết quả đoạn giả nằm dưới mã khác ([CacheKeys.sharedKey]).
     */
    suspend fun run(
        source: ChunkSource,
        output: OutputFolder,
        cfg: PipelineConfig,
        client: ChunkTranscriber?,
        chunkDelaySec: Double = 0.0,
        cacheSalt: String = "",
        onProgress: (FileProgress) -> Unit = {},
    ): FileResult {
        val progress = ProgressTracker(onProgress)
        val info = withContext(io) { source.info() }
        val startedAt = now()
        val recordedAt = if (info.modifiedMillis > 0) {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(info.modifiedMillis), zone)
        } else {
            startedAt
        }
        OutputNaming.templateError(cfg.filenameTemplate)?.let { throw PipelineException(it, stopQueue = true) }
        val outName = OutputNaming.outputFilename(
            cfg.filenameTemplate, info.displayName, recordedAt, startedAt, cfg.gemini.model, cfg.extension,
        )

        logger.info("-".repeat(70))
        logger.info("Tệp    : ${info.displayName} (${info.id})")
        logger.info("Kết quả: ${output.label}/$outName")
        val chain = cfg.gemini.modelChain()
        logger.info("Model  : ${cfg.gemini.model}" + if (chain.size > 1) " (dự phòng: ${chain.drop(1).joinToString(", ")})" else "")

        val existing = withContext(io) { output.listNames() }
        if (cfg.onConflict == PipelineConfig.CONFLICT_SKIP && existing.any { it.equals(outName, ignoreCase = true) }) {
            logger.info("Đã có bản chép lời cùng tên, bỏ qua tệp này.")
            return FileResult(outName = outName, skipped = true)
        }
        if (client == null) {
            throw GoogleAIException("Chưa có API key của Google AI Studio. Nhập key ở trang Google AI.", ErrorKind.CONFIG)
        }
        // Báo sớm, trước khi mất vài phút xử lý âm thanh.
        constraintError(cfg.gemini)?.let { throw ConstraintException(it) }
        if (cfg.gemini.diarizationEnabled) {
            throw PipelineException(
                "Nhận diện người nói chưa có trên điện thoại (Stage 5). Tắt [SPEAKERS] enabled rồi chạy lại.",
                stopQueue = true,
            )
        }

        val folder = tempDirFor(info)
        val isVideo = Transcript.isVideo(info.displayName, info.mimeType)
        val audioKey = CacheKeys.audioHash(
            fingerprint(info), PipelineConfig.SAMPLE_RATE, cfg.chunk, cfg.chunkFormat,
            if (isVideo) listOf("track", cfg.audioTrack) else null,
        )

        // ---- (1) xử lý âm thanh + cắt đoạn ----
        progress(Stage.CLEAN, 0.0)
        val chunkList = try {
            prepareChunks(source, folder, audioKey, cfg, progress)
        } catch (e: NoAudioTrackException) {
            if (!cfg.skipSilent) throw e
            logger.warning("${e.message} Bỏ qua tệp này.")
            return FileResult(skipped = true)
        }
        currentCoroutineContext().ensureActive()

        // ---- (2) chép lời ----
        val results = transcribeChunks(
            cfg.gemini, folder, chunkList.chunks, client, audioKey, chunkDelaySec, progress, cacheSalt,
        )
        val models = Transcript.countModels(results, cfg.gemini.model)
        if (models.size > 1) {
            logger.info("Các model đã chép lời tệp này: " + models.entries.joinToString(", ") { "${it.key} ${it.value} đoạn" })
        }

        // ---- (3) ghép, đặt tên, ghi ----
        progress(Stage.MERGE, 0.0)
        val body = Transcript.assemble(results, cfg.markChunks)
        var content = body
        if (cfg.addHeader) {
            content = Transcript.formatHeader(
                isVideo, info.displayName, now(), chunkList.originalDuration, models, cfg.gemini.model,
            ) + if (body.isNotEmpty()) "\n\n" + body else ""
        }
        if (body.isEmpty()) logger.warning("Bản chép lời không có chữ nào.")
        val bytes = OutputNaming.encodeText(content + if (content.isNotEmpty()) "\n" else "", cfg.encoding, cfg.newline)
        // Đã tới bước ghi thì làm cho xong (NonCancellable): bị Dừng giữa lúc ghi mà ném CancellationException thì tệp
        // đã ghi nhưng mục bị coi là dừng, chạy lại sẽ ra thêm bản "(2)".
        val location = withContext(io + NonCancellable) {
            val names = output.listNames()
            val name = if (cfg.onConflict == PipelineConfig.CONFLICT_NUMBER) OutputNaming.uniqueName(outName, names) else outName
            val written = name to output.write(name, bytes, overwrite = names.any { it.equals(name, ignoreCase = true) })
            logger.info("HOÀN THÀNH: ${written.second}")
            if (cfg.deleteTempWhenDone) folder.deleteRecursively()
            written
        }
        progress(Stage.MERGE, 1.0)
        return FileResult(
            outFile = location.second, outName = location.first, chunkCount = chunkList.chunks.size,
            charCount = body.length, origDuration = chunkList.originalDuration, models = models,
        )
    }

    // ------------------------------------------------------------------ (1) cắt đoạn có cache

    /**
     * Port của transcribe.py: `prepare_chunks` (phần cache): danh sách đoạn đúng mã và đủ tệp thì dùng lại, không thì
     * gọi [ChunkSource]. Xong (hoặc dừng / lỗi) thì xoá `goc.pcm`, `sach.pcm` (D66): Stage 4 không cần PCM nữa, lần chạy
     * lại không có danh sách đoạn thì giải mã lại từ đầu.
     */
    private suspend fun prepareChunks(
        source: ChunkSource,
        folder: File,
        digest: String,
        cfg: PipelineConfig,
        progress: ProgressTracker,
    ): ChunkList {
        val existing = withContext(io) { ChunkCache.read(folder, digest) }
        if (existing != null) {
            logger.info("Các đoạn âm thanh đã có sẵn (${existing.chunks.size} đoạn), bỏ qua bước xử lý âm thanh.")
            progress(Stage.CLEAN, 1.0)
            return existing
        }
        val context = currentCoroutineContext()
        val started = System.nanoTime()
        val list = withContext(io) {
            try {
                folder.mkdirs()
                source.prepare(
                    folder, digest, cfg.chunk, cfg.audioTrack,
                    object : SourceListener {
                        override fun onProgress(fraction: Double, step: String) = progress(Stage.CLEAN, fraction, step = step)
                        override fun checkCancelled() = context.ensureActive()
                    },
                )
            } finally {
                ChunkCache.deletePcm(folder)
            }
        }
        progress(Stage.CLEAN, 1.0)
        logger.info(
            "Xử lý âm thanh và cắt đoạn xong sau ${seconds(started)} giây: ${list.chunks.size} đoạn.",
        )
        return list
    }

    // ------------------------------------------------------------------ (2) chép lời từng đoạn

    /**
     * Port của transcribe.py: `transcribe_chunks`, nhánh tắt nhận diện người nói: một [ChunkDispatcher] cho cả tệp,
     * prompt mặc định cho model đa năng (`null` cho `*-transcribe`), bản chép lời rỗng thì thử model khác cho đoạn đó và
     * không lưu kết quả rỗng. Thêm so với desktop: dòng log "dùng lại kết quả đã có" để kiểm được việc không gửi lại.
     */
    internal suspend fun transcribeChunks(
        cfg: GeminiConfig,
        folder: File,
        chunks: List<ChunkInfo>,
        client: ChunkTranscriber,
        audioKey: String,
        chunkDelaySec: Double = 0.0,
        progress: ProgressTracker = ProgressTracker {},
        cacheSalt: String = "",
    ): List<ChunkResult> {
        val dispatcher = ChunkDispatcher(cfg, client, sleep, logger)
        fun promptFor(model: String): String? = if (isTranscribeModel(model)) null else DefaultPrompt.text
        val sharedKey = CacheKeys.sharedKey(audioKey, promptFor(cfg.model), cfg, cacheSalt)
        val results = ArrayList<ChunkResult>()
        val total = chunks.size
        var sent = 0
        for ((index, d) in chunks.withIndex()) {
            val i = index + 1
            val resultFile = File(folder, CacheKeys.resultFileName(d.file, sharedKey))
            val cached = withContext(io) { ChunkCache.readResult(resultFile) }
            if (cached != null) {
                logger.info("Đoạn $i/$total: dùng lại kết quả đã có (${cached.model ?: cfg.model}), không gửi lại.")
                results += cached
                progress(Stage.TRANSCRIBE, i.toDouble() / total, chunk = i, total = total)
                continue
            }
            currentCoroutineContext().ensureActive()
            if (sent > 0 && chunkDelaySec > 0) {
                logger.info("Chờ ${"%.0f".format(Locale.ROOT, chunkDelaySec)} giây trước đoạn $i (chờ giữa các đoạn, bản debug).")
                sleep(chunkDelaySec)
            }
            sent++

            val seconds = d.endSeconds - d.startSeconds
            var reported = false
            val onModel: (String) -> Unit = { model ->
                if (!reported) {
                    logger.info(
                        "Đoạn $i/$total (${Transcript.hms(d.startSeconds)} - ${Transcript.hms(d.endSeconds)}): " +
                            "gửi lên $model...",
                    )
                    reported = true
                }
                progress(Stage.TRANSCRIBE, (i - 1).toDouble() / total, chunk = i, total = total, model = model)
            }
            val optionsFor: (String) -> Pair<String?, TranscribeOptions> = { model -> promptFor(model) to TranscribeOptions() }
            val started = System.nanoTime()
            val chunkFile = File(folder, d.file)
            var (response, model) = dispatcher.send(chunkFile, seconds, optionsFor, onModel)
            var text = extractText(response).pyStrip()
            // Đoạn im lặng đã bị cắt từ trước, nên bản chép lời rỗng gần như chắc chắn là model trả về hỏng (Google vẫn
            // tính token). Bỏ qua âm thầm = mất hẳn mấy phút bài giảng, nên thử lại model khác.
            if (text.isEmpty() && Transcript.hasSpeech(null)) {
                // Còn model khác thì bỏ qua model vừa hỏng; chỉ còn một model thì thử lại chính nó (bỏ qua hết sẽ thành
                // "mọi model đều hỏng" và làm hỏng cả tệp).
                val others = dispatcher.availableModels().filter { it != model }
                logger.warning(
                    "Đoạn $i/$total: $model trả về bản chép lời RỖNG trong khi đoạn này có tiếng nói. " +
                        "Thử lại bằng ${others.firstOrNull() ?: "chính model đó"}.",
                )
                reported = false
                try {
                    val skip = if (others.isNotEmpty()) {
                        mapOf(model to GoogleAIException("Trả về bản chép lời rỗng", ErrorKind.BLOCKED))
                    } else {
                        emptyMap()
                    }
                    val retry = dispatcher.send(chunkFile, seconds, optionsFor, onModel, skip)
                    val text2 = extractText(retry.response).pyStrip()
                    if (text2.isNotEmpty()) {
                        response = retry.response
                        model = retry.model
                        text = text2
                    } else {
                        logger.warning("Đoạn $i/$total: ${retry.model} cũng trả về rỗng. Đoạn này sẽ trống.")
                    }
                } catch (e: GoogleAIException) {
                    // Thử lại thất bại: giữ kết quả rỗng và đi tiếp, đừng làm hỏng cả tệp.
                    logger.warning("Đoạn $i/$total: thử lại cũng không được (${e.message}). Đoạn này sẽ trống.")
                }
            }
            val result = ChunkResult(text, model = model)
            // Không nhớ kết quả rỗng của đoạn có tiếng nói: nhớ thì chạy lại cũng bỏ qua đoạn đó vĩnh viễn.
            // NonCancellable: Google đã trả lời (và đã tính vào hạn mức); bị Dừng lúc client đang xoá tệp tải lên thì vẫn
            // phải lưu, không thì chạy lại sẽ gửi lại đoạn này.
            if (text.isNotEmpty()) withContext(io + NonCancellable) { ChunkCache.writeResult(resultFile, result) }
            results += result

            val tokens = client.lastTokenCount
            if (text.isNotEmpty()) {
                logger.info(
                    "Đoạn $i/$total xong sau ${seconds(started)} giây bằng $model, ${text.length} ký tự" +
                        (if (tokens > 0) ", $tokens token đầu vào" else "") + ".",
                )
            } else {
                logger.warning("Đoạn $i/$total không có chữ nào (đoạn không ai nói, hoặc model không nghe ra).")
            }
            progress(Stage.TRANSCRIBE, i.toDouble() / total, chunk = i, total = total, model = model)
        }
        return results
    }

    private fun seconds(startedNanos: Long) =
        "%.0f".format(Locale.ROOT, (System.nanoTime() - startedNanos) / 1e9)
}

/**
 * Đổi tiến độ trong từng bước (0..1) thành tiến độ cả tệp. Port của transcribe.py: `ProgressTracker` (nhánh không có
 * pyannote): âm thanh 0–0,15, chép lời 0,15–0,98, ghép 0,98–1.
 */
class ProgressTracker(private val report: (FileProgress) -> Unit) {
    operator fun invoke(
        stage: Stage,
        fraction: Double,
        step: String? = null,
        chunk: Int? = null,
        total: Int? = null,
        model: String? = null,
    ) {
        val (a, b) = when (stage) {
            Stage.CLEAN -> 0.0 to 0.15
            Stage.TRANSCRIBE -> 0.15 to 0.98
            Stage.MERGE -> 0.98 to 1.0
        }
        report(FileProgress(stage, a + (b - a) * fraction.coerceIn(0.0, 1.0), step, chunk, total, model))
    }
}

/** Đọc / ghi cache trong thư mục tạm của một tệp, cùng tên tệp và khoá JSON với desktop. */
object ChunkCache {
    const val RAW_PCM_FILE = "goc.pcm"

    // encodeDefaults: ghi cả "tu": [] như desktop.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Danh sách đoạn đã cắt với đúng mã, hoặc `null` nếu chưa có, hỏng, khác mã, rỗng hay thiếu tệp đoạn nào đó. Port
     * của transcribe.py: `read_chunk_list`.
     */
    fun read(folder: File, digest: String): ChunkList? {
        val list = ChunkPreparer.readChunkList(folder) ?: return null
        if (list.digest != digest || list.chunks.isEmpty()) return null
        if (list.chunks.any { File(folder, it.file).let { f -> !f.isFile || f.length() == 0L } }) return null
        return list
    }

    fun deletePcm(folder: File) {
        File(folder, RAW_PCM_FILE).delete()
        File(folder, ChunkPreparer.CLEAN_PCM_FILE).delete()
    }

    /** Kết quả đoạn đã lưu; tệp rỗng hoặc hỏng coi như chưa có (gửi lại), như `file_exists` + `json.load` của desktop. */
    fun readResult(file: File): ChunkResult? {
        if (!file.isFile || file.length() == 0L) return null
        return try {
            json.decodeFromString(ChunkResult.serializer(), file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    /** Ghi `.tmp` rồi đổi tên: bị giết giữa chừng thì không để lại kết quả dở. */
    fun writeResult(file: File, result: ChunkResult) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(ChunkResult.serializer(), result), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

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
import io.github.cozycode890.guzz.core.gemini.DiarizationMethod
import io.github.cozycode890.guzz.core.gemini.extractWords
import io.github.cozycode890.guzz.core.common.audio.TimeMap
import io.github.cozycode890.guzz.core.speaker.DiarizeRequest
import io.github.cozycode890.guzz.core.speaker.SpeakerDiarizer
import io.github.cozycode890.guzz.core.speaker.SpeakerTurn
import io.github.cozycode890.guzz.core.speaker.Speakers
import io.github.cozycode890.guzz.core.speaker.loadSpeakerTurns
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
enum class Stage(val code: String) { CLEAN("lam_sach"), DIARIZE("nguoi_noi"), TRANSCRIBE("go_chu"), MERGE("noi_lai") }

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

/** Kết quả một tệp. Port của transcribe.py: `FileResult`. */
data class FileResult(
    val outFile: String? = null,
    val outName: String? = null,
    val skipped: Boolean = false,
    val chunkCount: Int = 0,
    val charCount: Int = 0,
    val origDuration: Double = 0.0,
    val models: Map<String, Int> = emptyMap(),
    /** {tên: phút nói}, rỗng khi tắt nhận diện người nói. */
    val minutesPerSpeaker: Map<String, Double> = emptyMap(),
)

/**
 * Chuyển một tệp thành bản chép lời. Port của transcribe.py: `transcribe_single_file` + `prepare_chunks` (phần cache) +
 * `diarize_speakers` + `transcribe_chunks` + `assemble_transcript`, đủ 3 cách nhận diện người nói.
 *
 * Làm lại một tệp vừa lỗi / dừng giữa chừng: tham số âm thanh không đổi thì dùng lại các đoạn đã cắt (`ma` trong
 * `danh_sach_doan.json`), đoạn nào đã có `doan_NNN.<mã>.json` thì không gửi lại.
 *
 * @param tempRoot gốc thư mục tạm; mỗi tệp một thư mục con `<tên>_<mã vân tay>` (không nằm trong `cacheDir` vì hệ thống
 * có thể xoá thư mục đó lúc đang làm dở).
 * @param sleep hàm chờ của bộ đổi model (chờ model mở khoá) và của chờ giữa các đoạn (bản debug, D67).
 * @param diarizer tách giọng trên máy (cách `pyannote` và `hybrid`); `null` thì hai cách đó báo lỗi.
 */
class FilePipeline(
    private val tempRoot: File,
    private val logger: Logger = GuzzLog,
    private val sleep: suspend (Double) -> Unit = coroutineSleep,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val diarizer: SpeakerDiarizer? = null,
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
        val method = cfg.gemini.currentDiarizationMethod()
        val onDevice = method == DiarizationMethod.PYANNOTE || method == DiarizationMethod.HYBRID
        val progress = ProgressTracker(onProgress, hasDiarization = onDevice)
        val info = withContext(io) { source.info() }
        val startedAt = now()
        val recordedAt = if (info.modifiedMillis > 0) {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(info.modifiedMillis), zone)
        } else {
            startedAt
        }
        OutputNaming.templateError(cfg.filenameTemplate)?.let {
            throw PipelineException(it, stopQueue = true, code = ErrorCodes.FILENAME_TEMPLATE, args = listOf(cfg.filenameTemplate))
        }
        val outName = OutputNaming.outputFilename(
            cfg.filenameTemplate, info.displayName, recordedAt, startedAt, cfg.gemini.model, cfg.extension,
        )

        logger.info("-".repeat(70))
        logger.info("Tệp    : ${info.displayName} (${info.id})")
        logger.info("Kết quả: ${output.label}/$outName")
        val chain = cfg.gemini.modelChain()
        logger.info(
            "Model  : ${cfg.gemini.model}" + (if (chain.size > 1) " (dự phòng: ${chain.drop(1).joinToString(", ")})" else "") +
                (if (method != null) " | người nói: $method" else ""),
        )

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
        if (onDevice && diarizer == null) {
            throw PipelineException("Bản này không có tách giọng trên máy.", stopQueue = true, code = ErrorCodes.NO_DIARIZATION)
        }

        val folder = tempDirFor(info)
        val isVideo = Transcript.isVideo(info.displayName, info.mimeType)
        val audioKey = CacheKeys.audioHash(
            fingerprint(info), PipelineConfig.SAMPLE_RATE, cfg.chunk, cfg.clean, cfg.chunkFormat,
            if (isVideo) listOf("track", cfg.audioTrack) else null,
        )

        val sp = cfg.speakers
        val speakerKey = CacheKeys.speakerHash(
            audioKey, sp.runOn, sp.diarization.signature, sp.numSpeakers, sp.minSpeakers, sp.maxSpeakers,
        )
        val speakersJson = File(folder, "nguoi_noi.$speakerKey.json")
        // D75: chưa có kết quả tách giọng thì giữ PCM mà tách giọng cần tới lúc có, rồi xoá ngay.
        val keepPcm = if (onDevice && !nonEmpty(speakersJson)) {
            if (sp.runOnOriginal) ChunkCache.RAW_PCM_FILE else ChunkPreparer.CLEAN_PCM_FILE
        } else {
            null
        }

        // ---- (1) xử lý âm thanh + cắt đoạn ----
        progress(Stage.CLEAN, 0.0)
        val chunkList = try {
            prepareChunks(source, folder, audioKey, cfg, progress, keepPcm)
        } catch (e: NoAudioTrackException) {
            if (!cfg.skipSilent) throw e
            logger.warning("${e.message} Bỏ qua tệp này.")
            return FileResult(skipped = true)
        }
        currentCoroutineContext().ensureActive()

        val timeMap = chunkList.toTimeMap()

        // ---- (2) tách giọng trên máy ----
        var turns: List<SpeakerTurn> = emptyList()
        var nameMap: Map<String, String> = emptyMap()
        if (onDevice) {
            turns = diarizeSpeakers(sp, folder, speakersJson, keepPcm, timeMap, progress)
            if (turns.isEmpty()) logger.warning("Tách giọng không nghe thấy ai nói trong tệp này.")
            if (method == DiarizationMethod.PYANNOTE) {
                val (processed, changed) = Speakers.processTurns(turns, sp.options)
                turns = processed
                nameMap = Speakers.buildNameMap(turns, sp.options, primary = Speakers.findMainSpeaker(turns))
                logger.info(
                    "Bản đồ người nói: ${turns.size} lượt" +
                        (if (changed > 0) " (làm mượt đổi nhãn $changed lượt)" else "") + ". " +
                        SpeakerTranscript.minutesLog(Speakers.minutesPerSpeaker(turns, nameMap)),
                )
            }
        }
        currentCoroutineContext().ensureActive()

        // ---- (3) chép lời ----
        val results = transcribeChunks(
            cfg.gemini, folder, chunkList.chunks, client, audioKey, chunkDelaySec, progress, cacheSalt,
            method, sp, turns, nameMap,
        )
        val models = Transcript.countModels(results, cfg.gemini.model)
        if (models.size > 1) {
            logger.info("Các model đã chép lời tệp này: " + models.entries.joinToString(", ") { "${it.key} ${it.value} đoạn" })
        }

        // ---- (4) ghép, đặt tên, ghi ----
        progress(Stage.MERGE, 0.0)
        val (body, minutes) = SpeakerTranscript.assemble(
            method, sp, cfg.markChunks, chunkList.chunks, results, turns, nameMap, timeMap,
        )
        var content = body
        if (cfg.addHeader) {
            content = Transcript.formatHeader(
                isVideo, info.displayName, now(), chunkList.originalDuration, models, cfg.gemini.model, minutes,
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
            if (sp.saveSpeakerMap && turns.isNotEmpty()) {
                val mapNames = nameMap.ifEmpty {
                    Speakers.buildNameMap(turns, sp.options, primary = Speakers.findMainSpeaker(turns))
                }
                // Như desktop: cạnh bản chép lời, cùng tên gốc, có thì ghi đè.
                val mapName = pySplitext(name).first + ".nguoi_noi.txt"
                val mapBytes = OutputNaming.encodeText(
                    SpeakerTranscript.fullSpeakerMap(turns, mapNames, timeMap), cfg.encoding, cfg.newline,
                )
                output.write(mapName, mapBytes, overwrite = names.any { it.equals(mapName, ignoreCase = true) })
            }
            val cleanAudio = File(folder, ChunkSource.CLEAN_AUDIO_FILE)
            if (cfg.saveCleanAudio && nonEmpty(cleanAudio)) {
                // Như desktop: <tên>.sach.flac cạnh bản chép lời, có thì ghi đè.
                val cleanName = pySplitext(name).first + ".sach.flac"
                output.copy(cleanName, cleanAudio, overwrite = names.any { it.equals(cleanName, ignoreCase = true) })
            }
            if (minutes.isNotEmpty()) logger.info("Thời gian nói: " + SpeakerTranscript.minutesLog(minutes))
            logger.info("HOÀN THÀNH: ${written.second}")
            if (cfg.deleteTempWhenDone) folder.deleteRecursively()
            written
        }
        progress(Stage.MERGE, 1.0)
        return FileResult(
            outFile = location.second, outName = location.first, chunkCount = chunkList.chunks.size,
            charCount = body.length, origDuration = chunkList.originalDuration, models = models,
            minutesPerSpeaker = minutes,
        )
    }

    // ------------------------------------------------------------------ (1) cắt đoạn có cache

    /**
     * Port của transcribe.py: `prepare_chunks` (phần cache): danh sách đoạn đúng mã và đủ tệp thì dùng lại, không thì
     * gọi [ChunkSource]. Xong (hoặc dừng / lỗi) thì xoá `goc.pcm`, `sach.pcm` (D66), trừ [keepPcm] mà tách giọng còn
     * cần (D75). Có danh sách đoạn mà thiếu [keepPcm] thì giải mã lại, như `need_clean` của desktop.
     */
    private suspend fun prepareChunks(
        source: ChunkSource,
        folder: File,
        digest: String,
        cfg: PipelineConfig,
        progress: ProgressTracker,
        keepPcm: String? = null,
    ): ChunkList {
        val existing = withContext(io) {
            ChunkCache.read(folder, digest)?.takeIf {
                (keepPcm == null || nonEmpty(File(folder, keepPcm))) &&
                    // Như need_clean của desktop: cần lưu âm thanh sạch mà chưa có thì xử lý lại.
                    (!cfg.saveCleanAudio || nonEmpty(File(folder, ChunkSource.CLEAN_AUDIO_FILE)))
            }
        }
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
                    folder, digest, cfg.chunk, cfg.clean, cfg.saveCleanAudio, cfg.audioTrack,
                    object : SourceListener {
                        override fun onProgress(fraction: Double, step: String) = progress(Stage.CLEAN, fraction, step = step)
                        override fun checkCancelled() = context.ensureActive()
                        override fun log(message: String) = logger.info(message.trim())
                    },
                )
            } finally {
                ChunkCache.deletePcm(folder, keep = keepPcm)
            }
        }
        progress(Stage.CLEAN, 1.0)
        logger.info(
            "Xử lý âm thanh và cắt đoạn xong sau ${seconds(started)} giây: ${list.chunks.size} đoạn.",
        )
        return list
    }

    // ------------------------------------------------------------------ (2) tách giọng

    /**
     * Lượt nói cả bài, mốc trên âm thanh ĐÃ LÀM SẠCH. Port của transcribe.py: `diarize_speakers`. Xong thì xoá PCM đã giữ
     * cho tách giọng (D75); Dừng giữa chừng thì PCM còn, chạy lại tách giọng tiếp từ phần đã xong.
     */
    private suspend fun diarizeSpeakers(
        sp: SpeakerConfig,
        folder: File,
        json: File,
        keepPcm: String?,
        timeMap: TimeMap,
        progress: ProgressTracker,
    ): List<SpeakerTurn> {
        if (nonEmpty(json)) {
            logger.info("Dùng lại kết quả nhận diện người nói đã có.")
        } else {
            val pcm = File(folder, keepPcm ?: error("thiếu PCM cho tách giọng"))
            logger.info(
                "Nhận diện người nói bằng ${sp.diarization.signature.substringBefore(", shift")} " +
                    "(âm thanh ${if (sp.runOnOriginal) "gốc" else "đã làm sạch"})...",
            )
            progress(Stage.DIARIZE, 0.0)
            val started = System.nanoTime()
            val result = diarizer!!.diarize(DiarizeRequest(pcm, json, sp.bounds)) { progress(Stage.DIARIZE, it) }
            logger.info(
                "Tách giọng xong sau ${seconds(started)} giây: ${result.turns.size} lượt nói, ${result.numSpeakers} người nói.",
            )
            withContext(io) { pcm.delete() }
        }
        progress(Stage.DIARIZE, 1.0)
        var turns = withContext(io) { loadSpeakerTurns(json) }
        if (sp.runOnOriginal) turns = Speakers.remapTimestamps(turns, timeMap::toClean)
        return turns
    }

    // ------------------------------------------------------------------ (3) chép lời từng đoạn

    /**
     * Port của transcribe.py: `transcribe_chunks`: một [ChunkDispatcher] cho cả tệp, prompt mặc định cho model đa năng
     * (`null` cho `*-transcribe`), bản chép lời rỗng thì thử model khác cho đoạn đó và không lưu kết quả rỗng. Cách
     * `pyannote`: bản đồ người nói của từng đoạn gửi kèm (tệp hoặc chữ) và prompt người nói; `gemini` / `hybrid`: yêu cầu
     * tách giọng / mốc từng từ của model `*-transcribe`. Thêm so với desktop: dòng log "dùng lại kết quả đã có" để kiểm
     * được việc không gửi lại.
     */
    internal suspend fun transcribeChunks(
        cfg: GeminiConfig,
        folder: File,
        chunks: List<ChunkInfo>,
        client: ChunkTranscriber,
        audioKey: String,
        chunkDelaySec: Double = 0.0,
        progress: ProgressTracker = ProgressTracker({}),
        cacheSalt: String = "",
        method: String? = null,
        sp: SpeakerConfig = SpeakerConfig(),
        turns: List<SpeakerTurn> = emptyList(),
        nameMap: Map<String, String> = emptyMap(),
    ): List<ChunkResult> {
        constraintError(cfg)?.let { throw ConstraintException(it) }
        val dispatcher = ChunkDispatcher(cfg, client, sleep, logger)
        fun promptFor(model: String): String? = if (isTranscribeModel(model)) null else DefaultPrompt.text
        val speakerPrompt = if (method == DiarizationMethod.PYANNOTE) DefaultPrompt.diarization else null
        if (method == DiarizationMethod.GEMINI || method == DiarizationMethod.HYBRID) {
            if (cfg.vocabulary.isNotEmpty()) {
                logger.warning("Từ vựng chuyên ngành bị bỏ qua: Google không cho dùng cùng tách người nói / mốc từng từ.")
            }
            if (cfg.transcribeMode == "smart") logger.info("Chế độ smart không đi cùng tách người nói / mốc từng từ, dùng verbatim.")
        }
        val sharedKey = CacheKeys.sharedKey(
            audioKey, promptFor(cfg.model), cfg, cacheSalt, method, speakerPrompt, sp.mapDelivery, sp.timestamps,
        )
        val results = ArrayList<ChunkResult>()
        val total = chunks.size
        var sent = 0
        for ((index, d) in chunks.withIndex()) {
            val i = index + 1
            var mapText: String? = null
            var chunkPrompt: String? = null
            var attachment: File? = null
            var attachedText: String? = null
            if (method == DiarizationMethod.PYANNOTE) {
                val chunkTurns = Speakers.splitTurnsByChunk(turns, d.startSeconds, d.endSeconds)
                mapText = Speakers.speakerMapText(chunkTurns, nameMap)
                val names = Speakers.speakersInChunk(chunkTurns, nameMap)
                    .ifEmpty { nameMap.values.distinct() }.ifEmpty { listOf("Speaker 1") }
                chunkPrompt = SpeakerTranscript.chunkPrompt(speakerPrompt!!, names, sp.timestamps)
                if (sp.mapDelivery == SpeakerConfig.DELIVERY_FILE) {
                    val f = File(folder, "${pySplitext(d.file).first}.nguoi_noi.txt")
                    withContext(io) { f.writeText(mapText, Charsets.UTF_8) }
                    attachment = f
                } else {
                    attachedText = mapText
                }
            }
            val resultFile = File(folder, CacheKeys.resultFileName(d.file, sharedKey, mapText))
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
            val optionsFor: (String) -> Pair<String?, TranscribeOptions> = { model ->
                when (method) {
                    DiarizationMethod.PYANNOTE ->
                        chunkPrompt to TranscribeOptions(attachment = attachment, attachedText = attachedText)
                    DiarizationMethod.GEMINI -> null to TranscribeOptions(diarize = true, wordTimestamps = true)
                    DiarizationMethod.HYBRID -> null to TranscribeOptions(wordTimestamps = true)
                    else -> promptFor(model) to TranscribeOptions()
                }
            }
            val started = System.nanoTime()
            val chunkFile = File(folder, d.file)
            var (response, model) = dispatcher.send(chunkFile, seconds, optionsFor, onModel)
            var text = extractText(response).pyStrip()
            // Đoạn im lặng đã bị cắt từ trước, nên bản chép lời rỗng gần như chắc chắn là model trả về hỏng (Google vẫn
            // tính token). Bỏ qua âm thầm = mất hẳn mấy phút bài giảng, nên thử lại model khác.
            if (text.isEmpty() && Transcript.hasSpeech(mapText)) {
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
            val words = if (method == DiarizationMethod.GEMINI || method == DiarizationMethod.HYBRID) {
                extractWords(response)
            } else {
                emptyList()
            }
            val result = ChunkResult(text, SpeakerTranscript.wordsToJson(words), model = model)
            // Không nhớ kết quả rỗng của đoạn có tiếng nói: nhớ thì chạy lại cũng bỏ qua đoạn đó vĩnh viễn.
            // NonCancellable: Google đã trả lời (và đã tính vào hạn mức); bị Dừng lúc client đang xoá tệp tải lên thì vẫn
            // phải lưu, không thì chạy lại sẽ gửi lại đoạn này.
            if (text.isNotEmpty() || !Transcript.hasSpeech(mapText)) {
                withContext(io + NonCancellable) { ChunkCache.writeResult(resultFile, result) }
            }
            results += result

            val tokens = client.lastTokenCount
            if (text.isNotEmpty()) {
                logger.info(
                    "Đoạn $i/$total xong sau ${seconds(started)} giây bằng $model, ${text.length} ký tự" +
                        (if (words.isNotEmpty()) ", ${words.size} từ có mốc" else "") +
                        (if (tokens > 0) ", $tokens token đầu vào" else "") + ".",
                )
                if ((method == DiarizationMethod.GEMINI || method == DiarizationMethod.HYBRID) && words.isEmpty()) {
                    logger.warning("Đoạn $i/$total không có mốc thời gian từng từ: đoạn này sẽ không có tên người nói.")
                }
            } else {
                logger.warning("Đoạn $i/$total không có chữ nào (đoạn không ai nói, hoặc model không nghe ra).")
            }
            progress(Stage.TRANSCRIBE, i.toDouble() / total, chunk = i, total = total, model = model)
        }
        return results
    }

    private fun nonEmpty(f: File) = f.isFile && f.length() > 0

    private fun seconds(startedNanos: Long) =
        "%.0f".format(Locale.ROOT, (System.nanoTime() - startedNanos) / 1e9)
}

/**
 * Đổi tiến độ trong từng bước (0..1) thành tiến độ cả tệp. Port của transcribe.py: `ProgressTracker`: âm thanh 0–0,15,
 * (tách giọng 0,15–0,35), chép lời tới 0,98, ghép 0,98–1.
 */
class ProgressTracker(private val report: (FileProgress) -> Unit, private val hasDiarization: Boolean = false) {
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
            Stage.DIARIZE -> 0.15 to 0.35
            Stage.TRANSCRIBE -> (if (hasDiarization) 0.35 else 0.15) to 0.98
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

    /** Xoá PCM tạm, trừ [keep] (tên tệp) mà tách giọng còn cần (D75). */
    fun deletePcm(folder: File, keep: String? = null) {
        for (name in listOf(RAW_PCM_FILE, ChunkPreparer.CLEAN_PCM_FILE)) {
            if (name != keep) File(folder, name).delete()
        }
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

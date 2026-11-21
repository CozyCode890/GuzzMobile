package io.github.cozycode890.guzz.server

import io.github.cozycode890.guzz.core.audio.ChunkPreparer
import io.github.cozycode890.guzz.core.audio.NoSpeechException
import io.github.cozycode890.guzz.core.audio.PrepareListener
import io.github.cozycode890.guzz.core.audio.PrepareStep
import io.github.cozycode890.guzz.core.audio.SampleRange
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.remote.JobErrors
import io.github.cozycode890.guzz.core.remote.JobRequest
import io.github.cozycode890.guzz.core.remote.JobResult
import io.github.cozycode890.guzz.core.remote.JobState
import io.github.cozycode890.guzz.core.remote.JobStatus
import io.github.cozycode890.guzz.core.remote.JobSteps
import io.github.cozycode890.guzz.core.remote.Protocol
import io.github.cozycode890.guzz.core.remote.ResultFile
import io.github.cozycode890.guzz.core.speaker.ChunkedDiarization
import io.github.cozycode890.guzz.core.speaker.DiarizeRequest
import io.github.cozycode890.guzz.core.speaker.Piece
import io.github.cozycode890.guzz.core.speaker.PieceEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Job bị huỷ (SIGTERM / `DELETE /jobs/{id}`), dừng ở điểm kiểm tiếp theo. */
class JobCancelledException : RuntimeException("Job bị huỷ")

/** Yêu cầu sai (khác phiên bản giao thức, thiếu trường...). */
class BadRequestException(message: String) : RuntimeException(message)

/** Ghi trạng thái ra tệp: `.tmp` rồi đổi tên, để vỏ Modal không bao giờ đọc phải tệp ghi dở. */
class StatusFile(private val file: File) {
    fun write(status: JobStatus) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(Protocol.json.encodeToString(JobStatus.serializer(), status), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * Một job của server (Stage 11 C1–C5): giải mã ([FfmpegDecoder]) → làm sạch + cắt đoạn ([ChunkPreparer] với
 * [FfmpegFlacEncoder], như `AudioPreparation` của điện thoại) → `sach.flac` / `am_thanh.flac` nếu cần → tách giọng
 * ([ChunkedDiarization] + [SherpaJvmPieceEngine]) trên cùng âm thanh, nên mốc lượt nói khớp TimeMap của các đoạn.
 * Xong thì trong [outDir] chỉ còn các tệp cache mà `FilePipeline` của điện thoại đọc.
 *
 * Log không ghi tên tệp hay nội dung (Rủi ro 5), chỉ bước và số đo.
 */
class JobProcessor(
    private val modelDir: File,
    private val threads: Int,
    private val cancelled: AtomicBoolean = AtomicBoolean(false),
    private val log: (String) -> Unit = ::println,
) {
    private fun check() {
        if (cancelled.get()) throw JobCancelledException()
    }

    /** Chạy job, ghi tiến độ qua [status]. Trả về trạng thái cuối (DONE hoặc FAILED), không ném lỗi. */
    fun process(request: JobRequest, input: File, outDir: File, status: (JobStatus) -> Unit): JobStatus {
        val started = System.nanoTime()
        fun report(step: String, fraction: Double) =
            status(JobStatus(state = JobState.RUNNING, step = step, fraction = fraction.coerceIn(0.0, 1.0)))
        val final = try {
            if (request.protocol != Protocol.VERSION) {
                throw BadRequestException("Giao thức ${request.protocol}, server dùng ${Protocol.VERSION}")
            }
            val result = run(request, input, outDir, ::report)
            JobStatus(state = JobState.DONE, step = JobSteps.PACK, fraction = 1.0, result = result)
        } catch (e: Throwable) {
            val code = when (e) {
                is NoAudioStreamException -> JobErrors.NO_AUDIO
                is NoSpeechException -> JobErrors.NO_SPEECH
                is JobCancelledException -> JobErrors.CANCELLED
                is BadRequestException -> JobErrors.BAD_REQUEST
                else -> JobErrors.INTERNAL
            }
            if (code == JobErrors.INTERNAL) log("Lỗi: ${e::class.java.simpleName}: ${e.message}")
            cleanup(outDir, keep = emptySet())
            JobStatus(state = JobState.FAILED, error = code, message = e.message)
        }
        log("Job ${final.state} sau %.1f s".format(java.util.Locale.ROOT, (System.nanoTime() - started) / 1e9))
        status(final)
        return final
    }

    private fun run(req: JobRequest, input: File, outDir: File, report: (String, Double) -> Unit): JobResult {
        outDir.mkdirs()
        val sr = req.sampleRate
        val diarize = req.speakerKey != null
        val cleanEnd = if (diarize) 0.35 else 0.97

        // (1) Giải mã.
        val raw = File(outDir, RAW_PCM_FILE)
        var t = System.nanoTime()
        report(JobSteps.DECODE, 0.0)
        val samples = FfmpegDecoder(sr).decode(input, req.streamIndex, raw, { report(JobSteps.DECODE, 0.1 * it) }, ::check)
        log("Giải mã xong: %.0f s âm thanh trong %.1f s".format(java.util.Locale.ROOT, samples.toDouble() / sr, secs(t)))

        // (2) Làm sạch + cắt đoạn.
        t = System.nanoTime()
        val steps = PrepareStep.entries.size
        val list = Pcm16File(raw).use { pcm ->
            ChunkPreparer(req.chunk, FfmpegFlacEncoder()).prepare(
                pcm, sr, req.sourceName, outDir,
                object : PrepareListener {
                    override fun onProgress(step: PrepareStep, fraction: Double) =
                        report(JobSteps.CLEAN, 0.1 + (cleanEnd - 0.1) * (step.ordinal + fraction) / steps)
                    override fun checkCancelled() = check()
                },
                req.audioKey, clean = req.clean, log = { log(it.trim()) },
            )
        }
        log("Làm sạch + cắt đoạn xong trong %.1f s: %d đoạn".format(java.util.Locale.ROOT, secs(t), list.chunks.size))
        val keep = LinkedHashSet<String>()
        keep += ChunkPreparer.CHUNK_LIST_FILE
        list.chunks.mapTo(keep) { it.file }
        val encoder = FfmpegFlacEncoder()
        if (req.saveClean) {
            Pcm16File(File(outDir, ChunkPreparer.CLEAN_PCM_FILE)).use { encoder.encode(it, SampleRange(0, it.size), sr, File(outDir, CLEAN_AUDIO_FILE)) }
            keep += CLEAN_AUDIO_FILE
        }
        if (req.saveExtracted) {
            Pcm16File(raw).use { encoder.encode(it, SampleRange(0, it.size), sr, File(outDir, EXTRACTED_AUDIO_FILE)) }
            keep += EXTRACTED_AUDIO_FILE
        }
        check()

        // (3) Tách giọng, trên âm thanh đã làm sạch hoặc gốc (D75) như điện thoại.
        if (diarize) {
            t = System.nanoTime()
            val settings = req.diarization.copy(threads = threads)
            val out = File(outDir, Protocol.speakersFile(req.speakerKey!!))
            val pcm = File(outDir, if (req.runOnOriginal) RAW_PCM_FILE else ChunkPreparer.CLEAN_PCM_FILE)
            report(JobSteps.DIARIZE, cleanEnd)
            SherpaJvmPieceEngine(modelDir, settings).use { engine ->
                val chunked = ChunkedDiarization(CancellableEngine(engine), settings, { log(it) })
                val request = DiarizeRequest(pcm, out, req.bounds)
                val r = runBlocking { chunked.diarize(request) { report(JobSteps.DIARIZE, cleanEnd + (0.97 - cleanEnd) * it) } }
                chunked.cleanPieces(request)
                log("Tách giọng xong trong %.1f s: %d lượt, %d người".format(java.util.Locale.ROOT, secs(t), r.turns.size, r.numSpeakers))
            }
            keep += out.name
        }

        // (4) Dọn, kê tệp kết quả.
        report(JobSteps.PACK, 0.98)
        cleanup(outDir, keep)
        return JobResult(keep.map { name -> File(outDir, name).let { ResultFile(name, it.length(), sha256(it)) } })
    }

    /** Kiểm Dừng trước mỗi phần: sherpa-onnx không dừng được giữa một phần (Stage 1 D7). */
    private inner class CancellableEngine(private val inner: PieceEngine) : PieceEngine {
        override suspend fun run(pcm: File, a: Long, b: Long, numClusters: Int, onProgress: (Int, Int) -> Unit): Piece {
            check()
            return inner.run(pcm, a, b, numClusters, onProgress)
        }
    }

    /** Xoá mọi tệp không thuộc kết quả (PCM tạm, `tam_NN.f32`, tệp từng phần, `.part`). */
    private fun cleanup(outDir: File, keep: Set<String>) {
        outDir.listFiles()?.forEach { f -> if (f.name !in keep) f.deleteRecursively() }
    }

    private fun secs(t: Long) = (System.nanoTime() - t) / 1e9

    companion object {
        const val RAW_PCM_FILE = "goc.pcm"
        const val CLEAN_AUDIO_FILE = "sach.flac"
        const val EXTRACTED_AUDIO_FILE = "am_thanh.flac"

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

package io.github.cozycode890.guzz.core.speaker

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Tách giọng cả bài cho pipeline (bước (3) của transcribe.py). Bản chạy thật: [ChunkedDiarization] với sherpa-onnx trong
 * process `:diarize` (`:core:diarization`); test của pipeline dùng bản giả.
 */
interface SpeakerDiarizer {
    /**
     * Tách giọng [request] và ghi kết quả vào [DiarizeRequest.outFile] (cùng định dạng `diarization_worker.py`). Huỷ
     * coroutine thì dừng ngay (bản Android giết process `:diarize`); phần đã xong vẫn dùng lại được lần sau.
     *
     * @param onProgress tỉ lệ đã xong trong [0, 1].
     */
    suspend fun diarize(request: DiarizeRequest, onProgress: (Double) -> Unit = {}): DiarizationJson
}

/**
 * @property pcm PCM 16-bit 16 kHz mono (`sach.pcm` hoặc `goc.pcm`, D75).
 * @property workDir thư mục tạm của tệp; tệp từng phần `<tên outFile không đuôi>.phan_NN[.kN].json` nằm ở đây.
 * @property outFile `nguoi_noi.<mã>.json`.
 */
data class DiarizeRequest(val pcm: File, val outFile: File, val bounds: SpeakerBounds) {
    val workDir: File get() = outFile.absoluteFile.parentFile
}

/** Cấu hình sherpa-onnx và chia phần (Stage 1, D80). Đổi bất kỳ số nào thì [signature] đổi, nên mã cache đổi. */
data class DiarizationSettings(
    val segmentation: String = "segmentation-3.0",
    val embedding: String = "campplus_zh_en_advanced",
    val threads: Int = 4,
    val windowShiftRatio: Float = 0.1f,
    val minDurationOn: Float = 0.2f,
    val minDurationOff: Float = 0.5f,
    /** Ngưỡng phân cụm trong phần và nối người nói giữa các phần (B1, D80). */
    val threshold: Double = 1.1,
    val pieceMinutes: Double = 20.0,
    val overlapSeconds: Double = 60.0,
    val sampleRate: Int = 16_000,
) {
    /**
     * Chuỗi `diarization_model` của mã cache (`speaker_hash`): gom model + ngưỡng + chia phần, nên đổi bất kỳ thứ nào
     * cũng tách giọng lại ("Mặc định kỹ thuật" của Stage 5).
     */
    val signature: String
        get() = "sherpa-onnx $SHERPA_VERSION: $segmentation + $embedding, shift $windowShiftRatio, on $minDurationOn, " +
            "off $minDurationOff, t $threshold, P $pieceMinutes, O $overlapSeconds"

    companion object {
        const val SHERPA_VERSION = "2.0.1"
    }
}

/** Tách giọng một phần [a, b) (mẫu) của tệp PCM. Bản Android chạy trong process `:diarize`. */
interface PieceEngine {
    /**
     * @param numClusters `> 0` = số cụm cố định, `≤ 0` = ngưỡng.
     * @param onProgress (khối đã xong, tổng khối) của sherpa-onnx.
     * @return lượt nói (giây trên toàn bài, đã bỏ chồng lấn) + embedding từng nhãn.
     */
    suspend fun run(pcm: File, a: Long, b: Long, numClusters: Int, onProgress: (Int, Int) -> Unit): Piece
}

/**
 * Tách giọng chia phần: P = 20 phút, chồng lấn O = 60 s (Stage 1 D5), luật min–max (D72), nối người nói, ghi kết quả.
 * Kết quả từng phần ghi vào `phan_NN.json` ngay khi xong, nên Dừng / app bị giết rồi chạy lại chỉ làm các phần còn
 * thiếu ("Dùng lại phần N đã tách giọng").
 */
class ChunkedDiarization(
    private val engine: PieceEngine,
    private val settings: DiarizationSettings = DiarizationSettings(),
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : SpeakerDiarizer {

    /** Tệp kết quả của phần [index] (đánh số từ 1 như desktop đánh số đoạn), số cụm cố định thì thêm `.kN`. */
    fun pieceFile(request: DiarizeRequest, index: Int, numClusters: Int): File {
        val base = request.outFile.name.removeSuffix(".json")
        val k = if (numClusters > 0) ".k$numClusters" else ""
        return File(request.workDir, "%s.phan_%02d%s.json".format(java.util.Locale.ROOT, base, index + 1, k))
    }

    override suspend fun diarize(request: DiarizeRequest, onProgress: (Double) -> Unit): DiarizationJson {
        val started = clock()
        val total = request.pcm.length() / 2
        val sr = settings.sampleRate.toLong()
        val bounds = PieceLinker.pieceBounds(
            total, (settings.pieceMinutes * 60 * sr).toLong(), (settings.overlapSeconds * sr).toLong(),
        )
        var runs = 0
        val runner = PieceRunner { k, n ->
            coroutineContext.ensureActive()
            val f = pieceFile(request, k, n)
            if (f.isFile) {
                runCatching { PieceJson.read(f).toPiece() }.getOrNull()?.let {
                    log("Dùng lại phần ${k + 1}/${bounds.size} đã tách giọng")
                    return@PieceRunner it
                }
            }
            val (a, b) = bounds[k]
            val base = runs.toDouble()
            val piece = engine.run(request.pcm, a, b, n) { done, all ->
                if (all > 0) onProgress(((base + done.toDouble() / all) / bounds.size).coerceAtMost(1.0))
            }
            runs++
            PieceJson.write(f, PieceJson.from(piece, n))
            piece
        }
        val outcome = SpeakerCountRule.run(bounds.size, request.bounds, settings.threshold, runner)
        outcome.notes.forEach { log("Luật số người: $it") }
        val result = DiarizationJson.build(
            model = settings.signature.substringBefore(", shift"),
            durationSec = total.toDouble() / sr,
            processingSec = (clock() - started) / 1000.0,
            turns = outcome.turns.map { Triple(it.start, it.end, it.label) },
            sherpa = buildJsonObject {
                put("cau_hinh", settings.signature)
                put("so_phan", bounds.size)
                put("min", request.bounds.min)
                put("max", request.bounds.max)
                put("luat_min_max", JsonArray(outcome.notes.map(::JsonPrimitive)))
                put("noi_phan", JsonArray(outcome.link.log))
            },
        )
        DiarizationJson.write(request.outFile, result)
        onProgress(1.0)
        return result
    }

    /** Xoá các tệp từng phần sau khi đã có `nguoi_noi.<mã>.json`. */
    fun cleanPieces(request: DiarizeRequest) {
        val prefix = request.outFile.name.removeSuffix(".json") + ".phan_"
        request.workDir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
    }
}

package io.github.cozycode890.guzz.spike.diarize

import android.os.Build
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import io.github.cozycode890.guzz.spike.Paths
import io.github.cozycode890.guzz.spike.bench.Bench
import io.github.cozycode890.guzz.spike.bench.BenchContext
import io.github.cozycode890.guzz.spike.bench.json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

/**
 * Bench `diarize` (D2–D5, D7): tách người nói một tệp f32 bằng sherpa-onnx 2.0.1, ghi JSON cùng định dạng với
 * spikes/diarization/run_sherpa.py vào `results/diarize/<out>.json`.
 *
 * Tham số (extra): `input` (tệp trong input/), `emb` (trong models/), `seg` (mặc định seg/model.onnx), `threads`,
 * `shift`, `threshold`, `num_clusters`, `piece_minutes` (> 0: chia phần, D5), `overlap_seconds`, `out`.
 * D7 cách 1: `callback_return` ≠ 0 thì callback trả về giá trị đó từ lúc xong `callback_after` (phần) số khối.
 * D7 cách 2: chia phần + lệnh Dừng (`bench=stop`) thì dừng ở ranh giới phần, ghi độ trễ.
 */
object DiarizeBench : Bench {
    private const val SR = F32File.SAMPLE_RATE
    private const val MIN_DURATION_ON = 0.2f
    private const val MIN_DURATION_OFF = 0.5f
    private const val EMBEDDING_MAX_SEC = 30.0
    private const val EMBEDDING_MIN_TURN_SEC = 1.0

    override fun run(b: BenchContext): JSONObject {
        val ctx = b.ctx
        val input = Paths.resolve(Paths.input(ctx), requireNotNull(b.string("input")) { "thiếu input" })
        val seg = Paths.resolve(Paths.models(ctx), b.string("seg", "seg/model.onnx")!!)
        val emb = Paths.resolve(Paths.models(ctx), requireNotNull(b.string("emb")) { "thiếu emb" })
        val threads = b.int("threads", 4)
        val shift = b.float("shift", 0.1f)
        // Ngưỡng đọc dạng chuỗi rồi đổi sang Double, để so khoảng cách cosine giống hệt bản Python.
        val threshold = b.string("threshold", "0.5")!!.toDouble()
        val numClusters = b.int("num_clusters", -1)
        val pieceMinutes = b.string("piece_minutes", "0")!!.toDouble()
        val overlapSeconds = b.string("overlap_seconds", "60")!!.toDouble()
        val cbReturn = b.int("callback_return", 0)
        val cbAfter = b.float("callback_after", 0.1f)
        val outName = b.string("out") ?: "${input.nameWithoutExtension}__${emb.nameWithoutExtension}_t$threads"
        require(input.isFile) { "không có $input" }
        require(seg.isFile && emb.isFile) { "thiếu model: $seg / $emb" }

        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = seg.path, windowShiftRatio = shift),
                numThreads = threads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = emb.path, numThreads = threads),
            clustering = FastClusteringConfig(numClusters = numClusters, threshold = threshold.toFloat()),
            minDurationOn = MIN_DURATION_ON,
            minDurationOff = MIN_DURATION_OFF,
        )
        val total = F32File.length(input)
        val extra = JSONObject()
        val t0 = SystemClock.elapsedRealtime()
        val sd = OfflineSpeakerDiarization(null, config)
        b.log.write("su_kien" to "da_nap_model", "ms" to SystemClock.elapsedRealtime() - t0)
        val segs: List<Seg>
        var overlap = 0.0
        var speech = 0.0
        var linkLog: JSONArray? = null
        try {
            if (pieceMinutes > 0) {
                val extractor = SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = emb.path,
                    numThreads = threads))
                val bounds = pieceBounds(total, (pieceMinutes * 60 * SR).toLong(), (overlapSeconds * SR).toLong())
                val pieces = ArrayList<Piece>()
                val pieceTimes = JSONArray()
                for ((k, ab) in bounds.withIndex()) {
                    val stopAt = b.stopRequestedAt()
                    if (stopAt != 0L) {
                        extra.put("dung_o_phan", k).put("dung_tre_ms", SystemClock.elapsedRealtime() - stopAt)
                        break
                    }
                    val (a, e) = ab
                    val tp = SystemClock.elapsedRealtime()
                    val samples = F32File.read(input, a, e)
                    val raw = diarize(sd, samples, "phần ${k + 1}/${bounds.size}", b, null)
                    val (ov, sp) = overlapSeconds(raw)
                    overlap += ov
                    speech += sp
                    val excl = toExclusive(raw)
                    val embs = LinkedHashMap<Int, DoubleArray?>()
                    for (l in excl.map { it.spk }.distinct().sorted()) {
                        embs[l] = meanEmbedding(extractor, samples, excl.filter { it.spk == l }.map { it.start to it.end })
                    }
                    val off = a.toDouble() / SR
                    pieces.add(Piece(off, e.toDouble() / SR, excl.map { Seg(it.start + off, it.end + off, it.spk) }, embs))
                    pieceTimes.put(SystemClock.elapsedRealtime() - tp)
                    b.log.write("su_kien" to "xong_phan", "phan" to k, "ms" to SystemClock.elapsedRealtime() - tp,
                        "ram" to b.mem.toJson())
                    // Mảng của phần này không còn dùng; gợi ý GC trước khi cấp mảng phần sau để đỉnh heap thấp.
                    System.gc()
                }
                extractor.release()
                extra.put("ms_tung_phan", pieceTimes)
                val linked = PieceLinker.link(pieces, threshold)
                segs = linked.turns
                linkLog = linked.log
            } else {
                b.log.write("su_kien" to "doc_tep", "mau" to total, "mb" to total * 4 / (1024 * 1024))
                val samples = try {
                    F32File.read(input)
                } catch (e: OutOfMemoryError) {
                    return json("oom" to "cap_mang", "loi" to e.toString(), "ram" to b.mem.toJson())
                }
                b.log.write("su_kien" to "truoc_native", "ram" to b.mem.toJson())
                val cb = if (cbReturn != 0) CallbackProbe(cbReturn, cbAfter) else null
                val raw = try {
                    diarize(sd, samples, input.name, b, cb)
                } catch (e: OutOfMemoryError) {
                    return json("oom" to "native", "loi" to e.toString(), "ram" to b.mem.toJson())
                }
                cb?.let { extra.put("callback", it.toJson()) }
                val (ov, sp) = overlapSeconds(raw)
                overlap = ov
                speech = sp
                segs = toExclusive(raw)
            }
        } finally {
            sd.release()
        }
        val elapsed = (SystemClock.elapsedRealtime() - t0) / 1000.0

        // Đánh số lại theo thứ tự xuất hiện, như nhãn SPEAKER_xx của pyannote.
        val order = LinkedHashMap<Int, Int>()
        segs.forEach { order.getOrPut(it.spk) { order.size } }
        val doan = JSONArray()
        segs.forEach {
            doan.put(JSONObject().put("bat_dau", r3(it.start)).put("ket_thuc", r3(it.end))
                .put("nguoi_noi", "SPEAKER_%02d".format(order.getValue(it.spk))))
        }
        val ram = b.mem.toJson()
        val data = JSONObject()
            .put("model", "sherpa-onnx 2.0.1 android: ${seg.parentFile?.name}/${seg.name} + ${emb.nameWithoutExtension}")
            .put("thiet_bi", "cpu ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
            .put("file_wav", input.path)
            .put("thoi_luong_giay", r3(total.toDouble() / SR))
            .put("giay_xu_ly", elapsed)
            .put("so_nguoi", order.size)
            .put("sherpa", JSONObject()
                .put("phien_ban", "2.0.1")
                .put("seg", seg.name)
                .put("emb", emb.nameWithoutExtension)
                .put("threads", threads)
                .put("window_shift_ratio", shift.toDouble())
                .put("num_clusters", numClusters)
                .put("threshold", threshold)
                .put("min_duration_on", MIN_DURATION_ON.toDouble())
                .put("min_duration_off", MIN_DURATION_OFF.toDouble())
                .put("piece_minutes", if (pieceMinutes > 0) pieceMinutes else JSONObject.NULL)
                .put("overlap_seconds", if (pieceMinutes > 0) overlapSeconds else JSONObject.NULL))
            .put("ram_dinh_mb", ram.getLong("pss_dinh_mb"))
            .put("ram", ram)
            .put("ty_le_chong_lan", if (speech > 0) overlap / speech else 0.0)
            .put("doan", doan)
        linkLog?.let { data.put("noi_phan", it) }
        extra.keys().forEach { data.put(it, extra.get(it)) }
        val outFile = File(File(Paths.results(ctx), "diarize").apply { mkdirs() }, "$outName.json")
        outFile.writeText(data.toString(1))
        return JSONObject().put("tep", outFile.path).put("giay_xu_ly", elapsed).put("so_nguoi", order.size)
            .put("so_luot", segs.size).put("rtf", elapsed / (total.toDouble() / SR)).apply {
                extra.keys().forEach { put(it, extra.get(it)) }
            }
    }

    /** D7 cách 1: từ lúc xong [after] số khối thì trả về [value]; ghi lại sau đó process còn chạy bao lâu. */
    private class CallbackProbe(val value: Int, val after: Float) {
        var firstAt = 0L
        var callsAfter = 0
        var lastDone = 0
        var total = 0

        fun onProgress(done: Int, totalChunks: Int): Int {
            lastDone = done
            total = totalChunks
            if (done < totalChunks * after) return 0
            if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime() else callsAfter++
            return value
        }

        fun toJson(): JSONObject = JSONObject().put("tra_ve", value).put("tu_khoi", (total * after).toInt())
            .put("goi_them_sau_khi_tra_ve", callsAfter).put("khoi_cuoi", lastDone).put("tong_khoi", total)
            .put("ms_tu_lan_tra_ve_dau_toi_khi_xong", if (firstAt > 0) SystemClock.elapsedRealtime() - firstAt else -1)
    }

    private fun diarize(sd: OfflineSpeakerDiarization, samples: FloatArray, label: String, b: BenchContext,
                        probe: CallbackProbe?): List<Seg> {
        val result = sd.processWithCallback(samples, ProgressCallback(label, b, probe), 0L)
        return result.sortedBy { it.start }.map { Seg(it.start.toDouble(), it.end.toDouble(), it.speaker) }
    }

    /**
     * Callback tiến độ phải là lớp viết rõ, không dùng lambda: JNI của sherpa-onnx tìm method
     * `invoke(IIJ)Ljava/lang/Integer;`, mà lambda của Kotlin 2.x (invokedynamic, D8 desugar) chỉ có bản
     * `invoke(Object, Object, Object)`, nên JNI ném NoSuchMethodError và process bị abort (gặp ở D2).
     */
    private class ProgressCallback(val label: String, val b: BenchContext, val probe: CallbackProbe?) :
        (Int, Int, Long) -> Int {
        private var last = 0L

        override fun invoke(done: Int, total: Int, arg: Long): Int {
            val now = SystemClock.elapsedRealtime()
            if (done == total || now - last > 5000) {
                last = now
                b.progress("$label: $done/$total khối")
            }
            return probe?.onProgress(done, total) ?: 0
        }
    }

    /** Embedding của tối đa 30 s tiếng nói (lượt dài trước), chuẩn hoá L2. Port của run_sherpa.py: mean_embedding. */
    private fun meanEmbedding(ex: SpeakerEmbeddingExtractor, audio: FloatArray, turns: List<Pair<Double, Double>>)
        : DoubleArray? {
        val picked = ArrayList<FloatArray>()
        var total = 0.0
        for ((a, e) in turns.sortedByDescending { it.second - it.first }) {
            if (e - a < EMBEDDING_MIN_TURN_SEC && picked.isNotEmpty()) break
            val take = minOf(e - a, EMBEDDING_MAX_SEC - total)
            val from = (a * SR).toInt().coerceIn(0, audio.size)
            val to = ((a + take) * SR).toInt().coerceIn(from, audio.size)
            picked.add(audio.copyOfRange(from, to))
            total += take
            if (total >= EMBEDDING_MAX_SEC) break
        }
        if (picked.isEmpty()) return null
        val all = FloatArray(picked.sumOf { it.size })
        var p = 0
        for (x in picked) {
            x.copyInto(all, p)
            p += x.size
        }
        val stream = ex.createStream()
        try {
            stream.acceptWaveform(all, SR)
            stream.inputFinished()
            if (!ex.isReady(stream)) return null
            val v = ex.compute(stream)
            return PieceLinker.normalize(DoubleArray(v.size) { v[it].toDouble() })
        } finally {
            stream.release()
        }
    }

    private fun r3(x: Double): Double = (x * 1000).roundToLong() / 1000.0
}

package io.github.cozycode890.guzz.core.speaker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/** Một lượt của một phần: giây [start, end) trên toàn bài, nhãn [label] (cục bộ trong phần, hoặc nhãn chung sau khi nối). */
data class LabeledTurn(val start: Double, val end: Double, val label: Int)

/**
 * Một phần đã tách giọng: giây [[a], [b]) trên toàn bài, lượt nói (giây toàn bài, nhãn cục bộ), embedding trung bình
 * chuẩn hoá L2 của từng nhãn (`null` khi nhãn không đủ tiếng nói để tính).
 */
class Piece(val a: Double, val b: Double, val turns: List<LabeledTurn>, val emb: Map<Int, DoubleArray?>) {
    val labels: Set<Int> get() = turns.mapTo(LinkedHashSet()) { it.label }
}

/**
 * Tệp `nguoi_noi.<mã>.phan_NN.json` của một phần, cùng định dạng `.pieces.json` của `run_sherpa.py` (một phần tử):
 * `a`, `b`, `turns` = `[[bắt đầu, kết thúc, nhãn]]`, `emb` = `{"nhãn": [số] | null}`. Thêm [numClusters]: số cụm cố định
 * đã dùng (luật min–max, D72), `-1` = phân cụm bằng ngưỡng.
 */
@Serializable
data class PieceJson(
    val a: Double,
    val b: Double,
    val turns: List<List<Double>>,
    val emb: Map<String, List<Double>?>,
    @SerialName("num_clusters") val numClusters: Int = -1,
) {
    fun toPiece(): Piece = Piece(
        a, b, turns.map { LabeledTurn(it[0], it[1], it[2].toInt()) },
        emb.entries.associate { (k, v) -> k.toInt() to v?.toDoubleArray() },
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun from(p: Piece, numClusters: Int = -1) = PieceJson(
            p.a, p.b, p.turns.map { listOf(it.start, it.end, it.label.toDouble()) },
            p.emb.entries.sortedBy { it.key }.associate { (k, v) -> k.toString() to v?.toList() }, numClusters,
        )

        fun read(file: File): PieceJson = json.decodeFromString(serializer(), file.readText(Charsets.UTF_8))

        /** Ghi `.tmp` rồi đổi tên: process `:diarize` bị giết giữa chừng không để lại tệp dở. */
        fun write(file: File, p: PieceJson) {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(serializer(), p), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "Không đổi tên được $tmp → $file" }
            }
        }
    }
}

/**
 * Chia phần và nối người nói giữa các phần (D34 / Stage 1 B8, D72). Port của spikes/diarization/linking.py:
 * `piece_bounds`, `link_pieces`, `merge_to_max` (bản Kotlin đầu tiên ở app spike, khớp Python 136/136 lượt).
 *
 * Chia phần vì phân cụm của sherpa-onnx tốn RAM theo bình phương số embedding: bài 2 giờ chạy nguyên bài bị giết vì hết
 * RAM trên Xiaomi 15T (Stage 1 D4).
 */
object PieceLinker {
    /** Cặp nhãn trùng nhau ít hơn ngần này giây trong vùng chồng lấn thì không coi là cùng người. */
    const val MIN_LINK_OVERLAP_SEC = 5.0

    /**
     * Các phần [a, b) dài [piece] mẫu, phần sau bắt đầu trước khi phần trước hết [overlap] mẫu. Port của
     * `piece_bounds`.
     */
    fun pieceBounds(total: Long, piece: Long, overlap: Long): List<Pair<Long, Long>> {
        if (total <= piece) return listOf(0L to total)
        require(overlap in 0 until piece) { "Chồng lấn phải nhỏ hơn độ dài phần" }
        val step = piece - overlap
        val bounds = ArrayList<Pair<Long, Long>>()
        var a = 0L
        while (true) {
            val b = minOf(a + piece, total)
            bounds += a to b
            if (b >= total) return bounds
            a += step
        }
    }

    /**
     * @property turns lượt toàn bài với nhãn chung, sắp theo bắt đầu.
     * @property log nhật ký nối từng phần (`phan`, `bat_dau`, `ket_thuc`, `noi`: nhãn cục bộ → nhãn chung và cách nối).
     * @property globalEmb embedding của từng nhãn chung, mỗi phần một vector (để tính trung bình khi gộp, luật max).
     */
    class Result(val turns: List<LabeledTurn>, val log: List<JsonObject>, val globalEmb: Map<Int, List<DoubleArray>>)

    /**
     * Nối người nói:
     *  1. trong vùng chồng lấn, ghép nhãn của hai phần theo thời gian trùng nhau (Hungary), chỉ nhận cặp ≥
     *     [MIN_LINK_OVERLAP_SEC];
     *  2. nhãn chưa ghép: khoảng cách cosine tới embedding trung bình của từng người đã có ≤ [threshold] thì gộp, không
     *     thì thêm người mới;
     *  3. vùng chồng lấn: lượt của phần trước tới giữa vùng, của phần sau từ giữa vùng trở đi.
     * Port của `link_pieces`.
     */
    fun link(pieces: List<Piece>, threshold: Double): Result {
        val globalEmb = LinkedHashMap<Int, MutableList<DoubleArray>>()
        val mapped = ArrayList<List<LabeledTurn>>()
        val log = ArrayList<JsonObject>()
        var nextId = 0
        for ((k, p) in pieces.withIndex()) {
            val labels = p.turns.map { it.label }.distinct().sorted()
            val mapping = LinkedHashMap<Int, Int>()
            val how = HashMap<Int, String>()
            if (k > 0) {
                val prev = pieces[k - 1]
                val lo = p.a
                val hi = prev.b
                val prevLabels = mapped[k - 1].map { it.label }.distinct().sorted()
                val m = Array(prevLabels.size) { DoubleArray(labels.size) }
                for (t1 in mapped[k - 1]) {
                    if (t1.end <= lo || t1.start >= hi) continue
                    for (t2 in p.turns) {
                        val ov = minOf(t1.end, t2.end, hi) - maxOf(t1.start, t2.start, lo)
                        if (ov > 0) m[prevLabels.indexOf(t1.label)][labels.indexOf(t2.label)] += ov
                    }
                }
                if (prevLabels.isNotEmpty() && labels.isNotEmpty()) {
                    val neg = Array(m.size) { r -> DoubleArray(labels.size) { c -> -m[r][c] } }
                    for ((r, c) in linearSumAssignment(neg)) {
                        if (m[r][c] >= MIN_LINK_OVERLAP_SEC) {
                            mapping[labels[c]] = prevLabels[r]
                            how[labels[c]] = "chồng lấn %.1fs".format(Locale.ROOT, m[r][c])
                        }
                    }
                }
            }
            for (l in labels) {
                if (l in mapping) continue
                val e = p.emb[l]
                var best: Int? = null
                var bestD: Double? = null
                if (e != null) {
                    val used = mapping.values.toSet()
                    for ((g, es) in globalEmb) {
                        if (g in used) continue
                        val d = 1.0 - dot(e, normalize(mean(es)))
                        if (bestD == null || d < bestD) {
                            best = g
                            bestD = d
                        }
                    }
                }
                if (best != null && bestD!! <= threshold) {
                    mapping[l] = best
                    how[l] = "embedding d=%.3f".format(Locale.ROOT, bestD)
                } else {
                    mapping[l] = nextId++
                    how[l] = "người mới" + (bestD?.let { " (gần nhất d=%.3f)".format(Locale.ROOT, it) } ?: "")
                }
            }
            for ((l, g) in mapping) p.emb[l]?.let { globalEmb.getOrPut(g) { ArrayList() }.add(it) }
            mapped += p.turns.map { LabeledTurn(it.start, it.end, mapping.getValue(it.label)) }
            log += buildJsonObject {
                put("phan", k)
                put("bat_dau", p.a)
                put("ket_thuc", p.b)
                putJsonObject("noi") {
                    for (l in labels) putJsonObject(l.toString()) {
                        put("vao", mapping[l])
                        put("cach", how[l])
                    }
                }
            }
        }

        val out = ArrayList<LabeledTurn>()
        for ((k, turns) in mapped.withIndex()) {
            val lo = if (k > 0) (pieces[k].a + pieces[k - 1].b) / 2 else Double.NEGATIVE_INFINITY
            val hi = if (k + 1 < mapped.size) (pieces[k + 1].a + pieces[k].b) / 2 else Double.POSITIVE_INFINITY
            for (t in turns) {
                val a2 = maxOf(t.start, lo)
                val b2 = minOf(t.end, hi)
                if (b2 - a2 > 1e-3) out += LabeledTurn(a2, b2, t.label)
            }
        }
        out.sortWith(compareBy<LabeledTurn>({ it.start }, { it.end }, { it.label }))
        return Result(mergeAdjacent(out), log, globalEmb)
    }

    /** Nối hai lượt liền nhau (hở < 1 ms) của cùng một người. Port của `merge_adjacent`. */
    fun mergeAdjacent(turns: List<LabeledTurn>): List<LabeledTurn> {
        val merged = ArrayList<LabeledTurn>()
        for (t in turns) {
            val last = merged.lastOrNull()
            if (last != null && last.label == t.label && t.start - last.end < 1e-3) {
                merged[merged.size - 1] = LabeledTurn(last.start, maxOf(last.end, t.end), last.label)
            } else {
                merged += t
            }
        }
        return merged
    }

    /**
     * Luật max của D72 sau khi nối: còn nhiều hơn [maxSpeakers] người thì gộp người nói ít thời gian nhất (hoà: người
     * xuất hiện trước) vào người gần nhất theo cosine của embedding trung bình; không có embedding thì vào người nói lâu
     * nhất. Lặp tới khi còn [maxSpeakers] người; `≤ 0` = tắt. Port của `merge_to_max`.
     */
    fun mergeToMax(turns: List<LabeledTurn>, globalEmb: Map<Int, List<DoubleArray>>, maxSpeakers: Int): List<LabeledTurn> {
        val emb = LinkedHashMap<Int, MutableList<DoubleArray>>()
        for ((g, v) in globalEmb) emb[g] = v.toMutableList()
        var segs = turns
        while (true) {
            val total = LinkedHashMap<Int, Double>()
            for (t in segs) total[t.label] = (total[t.label] ?: 0.0) + (t.end - t.start)
            if (maxSpeakers <= 0 || total.size <= maxSpeakers) return segs
            val victim = total.entries.minBy { it.value }.key
            val others = total.keys.filter { it != victim }
            var target: Int? = null
            val ve = emb[victim]
            if (!ve.isNullOrEmpty()) {
                val v = normalize(mean(ve))
                var bestD: Double? = null
                for (g in others) {
                    val ge = emb[g]
                    if (ge.isNullOrEmpty()) continue
                    val d = 1.0 - dot(v, normalize(mean(ge)))
                    if (bestD == null || d < bestD) {
                        target = g
                        bestD = d
                    }
                }
            }
            val to = target ?: others.maxBy { total.getValue(it) }
            emb.getOrPut(to) { ArrayList() }.addAll(emb.remove(victim).orEmpty())
            segs = mergeAdjacent(segs.map { if (it.label == victim) it.copy(label = to) else it })
        }
    }

    /** Số người sau khi nối. */
    fun speakerCount(turns: List<LabeledTurn>): Int = turns.mapTo(HashSet()) { it.label }.size

    /** Trung bình theo từng chiều, cộng lần lượt như `np.mean(axis=0)` trên mảng 2 chiều. */
    fun mean(es: List<DoubleArray>): DoubleArray {
        val r = DoubleArray(es[0].size)
        for (e in es) for (i in r.indices) r[i] += e[i]
        for (i in r.indices) r[i] /= es.size
        return r
    }

    fun normalize(e: DoubleArray): DoubleArray {
        val n = sqrt(dot(e, e))
        return if (n > 0) DoubleArray(e.size) { e[it] / n } else e
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}

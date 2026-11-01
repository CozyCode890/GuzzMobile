package io.github.cozycode890.guzz.spike.diarize

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** Một phần đã tách giọng: giây [a, b) trên toàn bài, lượt (giây toàn bài, nhãn cục bộ), embedding trung bình theo nhãn. */
class Piece(val a: Double, val b: Double, val turns: List<Seg>, val emb: Map<Int, DoubleArray?>)

/**
 * Nối người nói giữa các phần (D34, B8). Port của spikes/diarization/run_sherpa.py: link_pieces.
 *  1. trong vùng chồng lấn, ghép nhãn hai phần theo thời gian trùng nhau, chỉ nhận cặp ≥ [MIN_LINK_OVERLAP_SEC];
 *  2. nhãn chưa ghép: khoảng cách cosine tới embedding trung bình của từng người đã có ≤ ngưỡng thì gộp, không thì
 *     thêm người mới;
 *  3. vùng chồng lấn: lượt của phần trước tới giữa vùng, của phần sau từ giữa vùng trở đi.
 */
object PieceLinker {
    const val MIN_LINK_OVERLAP_SEC = 5.0

    class Result(val turns: List<Seg>, val log: JSONArray)

    fun link(pieces: List<Piece>, threshold: Double): Result {
        val globalEmb = LinkedHashMap<Int, MutableList<DoubleArray>>()
        val mapped = ArrayList<List<Seg>>()
        val log = JSONArray()
        var nextId = 0
        for ((k, p) in pieces.withIndex()) {
            val labels = p.turns.map { it.spk }.distinct().sorted()
            val mapping = LinkedHashMap<Int, Int>()
            val how = HashMap<Int, String>()
            if (k > 0) {
                val prev = pieces[k - 1]
                val lo = p.a
                val hi = prev.b
                val prevLabels = mapped[k - 1].map { it.spk }.distinct().sorted()
                val m = Array(prevLabels.size) { DoubleArray(labels.size) }
                for (t1 in mapped[k - 1]) {
                    if (t1.end <= lo || t1.start >= hi) continue
                    for (t2 in p.turns) {
                        val ov = minOf(t1.end, t2.end, hi) - maxOf(t1.start, t2.start, lo)
                        if (ov > 0) m[prevLabels.indexOf(t1.spk)][labels.indexOf(t2.spk)] += ov
                    }
                }
                if (prevLabels.isNotEmpty() && labels.isNotEmpty()) {
                    val neg = Array(m.size) { r -> DoubleArray(labels.size) { c -> -m[r][c] } }
                    for ((r, c) in linearSumAssignment(neg)) {
                        if (m[r][c] >= MIN_LINK_OVERLAP_SEC) {
                            mapping[labels[c]] = prevLabels[r]
                            how[labels[c]] = "chồng lấn %.1fs".format(m[r][c])
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
                    how[l] = "embedding d=%.3f".format(bestD)
                } else {
                    mapping[l] = nextId++
                    how[l] = "người mới" + (bestD?.let { " (gần nhất d=%.3f)".format(it) } ?: "")
                }
            }
            for ((l, g) in mapping) p.emb[l]?.let { globalEmb.getOrPut(g) { ArrayList() }.add(it) }
            mapped.add(p.turns.map { Seg(it.start, it.end, mapping.getValue(it.spk)) })
            val noi = JSONObject()
            for (l in labels) noi.put(l.toString(), JSONObject().put("vao", mapping[l]).put("cach", how[l]))
            log.put(JSONObject().put("phan", k).put("bat_dau", p.a).put("ket_thuc", p.b).put("noi", noi))
        }

        val out = ArrayList<Seg>()
        for ((k, turns) in mapped.withIndex()) {
            val lo = if (k > 0) (pieces[k].a + pieces[k - 1].b) / 2 else Double.NEGATIVE_INFINITY
            val hi = if (k + 1 < mapped.size) (pieces[k + 1].a + pieces[k].b) / 2 else Double.POSITIVE_INFINITY
            for (t in turns) {
                val a2 = maxOf(t.start, lo)
                val b2 = minOf(t.end, hi)
                if (b2 - a2 > 1e-3) out.add(Seg(a2, b2, t.spk))
            }
        }
        out.sortWith(compareBy<Seg>({ it.start }, { it.end }, { it.spk }))
        val merged = ArrayList<Seg>()
        for (t in out) {
            val last = merged.lastOrNull()
            if (last != null && last.spk == t.spk && t.start - last.end < 1e-3) {
                merged[merged.size - 1] = Seg(last.start, maxOf(last.end, t.end), last.spk)
            } else {
                merged.add(t)
            }
        }
        return Result(merged, log)
    }

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

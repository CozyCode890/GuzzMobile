package io.github.cozycode890.guzz.core.speaker

/**
 * Lượt nói của sherpa-onnx có thể chồng lấn (hai người cùng nói). Desktop dùng bản "exclusive" của pyannote: mỗi lúc chỉ
 * một người. Các hàm ở đây port từ spikes/diarization/linking.py (bản gốc ở run_sherpa.py của Stage 1).
 */
object Exclusive {
    /**
     * Bỏ chồng lấn: mỗi khoảng sơ cấp thuộc về lượt dài nhất đang phủ nó (hoà: nhãn nhỏ hơn); rồi nối các khoảng liền
     * nhau cùng người. Port của `to_exclusive`.
     */
    fun toExclusive(segs: List<LabeledTurn>): List<LabeledTurn> {
        val bounds = segs.flatMap { listOf(it.start, it.end) }.distinct().sorted()
        val byStart = segs.sortedBy { it.start }
        val out = ArrayList<LabeledTurn>()
        var active = ArrayList<LabeledTurn>()
        var i = 0
        for (k in 0 until bounds.size - 1) {
            val lo = bounds[k]
            val hi = bounds[k + 1]
            while (i < byStart.size && byStart[i].start <= lo) active.add(byStart[i++])
            active = active.filterTo(ArrayList()) { it.end > lo }
            val cover = active.filter { it.start <= lo && it.end >= hi }
            if (cover.isEmpty()) continue
            // Python: max(cover, key=(dài, -nhãn)) lấy phần tử đầu tiên đạt max.
            var best = cover[0]
            for (c in cover) {
                val d = (c.end - c.start).compareTo(best.end - best.start)
                if (d > 0 || (d == 0 && c.label < best.label)) best = c
            }
            val last = out.lastOrNull()
            if (last != null && last.label == best.label && kotlin.math.abs(last.end - lo) < 1e-9) {
                out[out.size - 1] = last.copy(end = hi)
            } else {
                out += LabeledTurn(lo, hi, best.label)
            }
        }
        return out
    }

    /** (thời gian có ≥ 2 người cùng nói, thời gian có ít nhất một người nói). Port của `overlap_seconds`. */
    fun overlapSeconds(segs: List<LabeledTurn>): Pair<Double, Double> {
        val events = (segs.map { it.start to 1 } + segs.map { it.end to -1 })
            .sortedWith(compareBy<Pair<Double, Int>>({ it.first }, { it.second }))
        var active = 0
        var prev: Double? = null
        var speech = 0.0
        var overlap = 0.0
        for ((t, d) in events) {
            if (prev != null && active > 0) {
                speech += t - prev
                if (active > 1) overlap += t - prev
            }
            active += d
            prev = t
        }
        return overlap to speech
    }

    const val EMBEDDING_MAX_SEC = 30.0
    const val EMBEDDING_MIN_TURN_SEC = 1.0

    /**
     * Các khoảng mẫu [từ, tới) để tính embedding trung bình của một người: tối đa 30 s tiếng nói, lượt dài trước; lượt
     * ngắn hơn 1 s chỉ lấy khi chưa có gì. [turns] tính bằng giây trong phần, [size] = số mẫu của phần. Port của
     * `mean_embedding` (phần chọn mẫu; `int()` của Python cắt về 0, lát cắt numpy tự kẹp vào mảng).
     */
    fun embeddingRanges(turns: List<Pair<Double, Double>>, sampleRate: Int, size: Int): List<IntRange> {
        val picked = ArrayList<IntRange>()
        var total = 0.0
        for ((a, b) in turns.sortedByDescending { it.second - it.first }) {
            if (b - a < EMBEDDING_MIN_TURN_SEC && picked.isNotEmpty()) break
            val take = minOf(b - a, EMBEDDING_MAX_SEC - total)
            val from = (a * sampleRate).toInt().coerceIn(0, size)
            val to = ((a + take) * sampleRate).toInt().coerceIn(from, size)
            picked += from until to
            total += take
            if (total >= EMBEDDING_MAX_SEC) break
        }
        return picked
    }
}

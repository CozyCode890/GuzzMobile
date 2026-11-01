package io.github.cozycode890.guzz.spike.diarize

/** Một lượt nói: giây [start, end), người nói [spk] (số, nhãn cục bộ hoặc nhãn chung). */
data class Seg(val start: Double, val end: Double, val spk: Int)

/**
 * Bỏ chồng lấn: mỗi khoảng sơ cấp thuộc về đoạn dài nhất đang phủ nó; rồi nối các khoảng liền nhau cùng người.
 * Port của spikes/diarization/run_sherpa.py: to_exclusive.
 */
fun toExclusive(segs: List<Seg>): List<Seg> {
    val bounds = segs.flatMap { listOf(it.start, it.end) }.distinct().sorted()
    val byStart = segs.sortedBy { it.start }
    val out = ArrayList<DoubleArray>()
    val outSpk = ArrayList<Int>()
    var active = ArrayList<Seg>()
    var i = 0
    for (k in 0 until bounds.size - 1) {
        val lo = bounds[k]
        val hi = bounds[k + 1]
        while (i < byStart.size && byStart[i].start <= lo) active.add(byStart[i++])
        active = active.filterTo(ArrayList()) { it.end > lo }
        val cover = active.filter { it.start <= lo && it.end >= hi }
        if (cover.isEmpty()) continue
        val spk = cover.maxWith(compareBy<Seg>({ it.end - it.start }, { -it.spk })).spk
        if (out.isNotEmpty() && outSpk.last() == spk && kotlin.math.abs(out.last()[1] - lo) < 1e-9) {
            out.last()[1] = hi
        } else {
            out.add(doubleArrayOf(lo, hi))
            outSpk.add(spk)
        }
    }
    return out.indices.map { Seg(out[it][0], out[it][1], outSpk[it]) }
}

/** (thời gian có ≥ 2 người cùng nói, thời gian có ít nhất một người nói). Port của run_sherpa.py: overlap_seconds. */
fun overlapSeconds(segs: List<Seg>): Pair<Double, Double> {
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

/** Các phần [a, b) dài [piece] mẫu, phần sau bắt đầu trước khi phần trước hết [overlap] mẫu. Port của piece_bounds. */
fun pieceBounds(total: Long, piece: Long, overlap: Long): List<Pair<Long, Long>> {
    if (total <= piece) return listOf(0L to total)
    val step = piece - overlap
    val bounds = ArrayList<Pair<Long, Long>>()
    var a = 0L
    while (true) {
        val b = minOf(a + piece, total)
        bounds.add(a to b)
        if (b >= total) return bounds
        a += step
    }
}

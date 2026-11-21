package io.github.cozycode890.guzz.core.common.audio

import io.github.cozycode890.guzz.core.common.pyRound

/**
 * Các đoạn `[bắt đầu, kết thúc]` (giây, trên âm thanh GỐC) được giữ lại sau khi cắt khoảng lặng, theo thứ tự. Âm thanh
 * đã cắt là các đoạn này nối liền nhau. Không cắt gì thì chỉ có một đoạn `[0, duration]`.
 *
 * Port của audio_processing.py: TimeMap. Đoạn có kết thúc ≤ bắt đầu bị bỏ, như desktop.
 *
 * @param origDuration thời lượng tệp gốc; `null` thì lấy kết thúc của đoạn cuối (desktop: `orig_duration=None`).
 */
class TimeMap(segments: List<Pair<Double, Double>>, origDuration: Double? = null) {
    val segments: List<Pair<Double, Double>> = segments.filter { (a, b) -> b > a }
    val origDuration: Double = origDuration ?: this.segments.lastOrNull()?.second ?: 0.0

    /** Mốc bắt đầu của từng đoạn trên âm thanh đã cắt. */
    private val cleanStarts = DoubleArray(this.segments.size)
    private val origStarts = DoubleArray(this.segments.size) { this.segments[it].first }

    /** Tổng thời lượng âm thanh đã cắt. */
    val cleanDuration: Double

    init {
        // Cộng dồn đúng thứ tự như Python (total += b - a) để mốc trùng từng bit.
        var total = 0.0
        for ((i, seg) in this.segments.withIndex()) {
            cleanStarts[i] = total
            total += seg.second - seg.first
        }
        cleanDuration = total
    }

    /** Mốc trên âm thanh đã cắt → mốc trên tệp gốc. Port của `TimeMap.to_original`. */
    fun toOriginal(t: Double): Double {
        if (segments.isEmpty()) return t
        val k = maxOf(0, bisectRight(cleanStarts, t) - 1)
        val (a, b) = segments[k]
        return minOf(b, a + maxOf(0.0, t - cleanStarts[k]))
    }

    /**
     * Mốc trên tệp gốc → mốc trên âm thanh đã cắt. Rơi vào khoảng đã cắt thì lấy đầu đoạn kế tiếp.
     * Port của `TimeMap.to_clean`.
     */
    fun toClean(t: Double): Double {
        if (segments.isEmpty()) return t
        val k = bisectRight(origStarts, t) - 1
        if (k < 0) return 0.0
        val (a, b) = segments[k]
        if (t <= b) return cleanStarts[k] + (t - a)
        return if (k + 1 < segments.size) cleanStarts[k + 1] else cleanDuration
    }

    /** Port của `TimeMap.to_json`: `[[round(a, 3), round(b, 3)], …]`. */
    fun toJson(): List<List<Double>> = segments.map { (a, b) -> listOf(pyRound(a, 3), pyRound(b, 3)) }

    companion object {
        /** Không cắt gì. Port của `TimeMap.untrimmed`. */
        fun untrimmed(duration: Double) = TimeMap(listOf(0.0 to duration))

        /** Như `bisect.bisect_right`: số phần tử ≤ x. */
        private fun bisectRight(sorted: DoubleArray, x: Double): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (x < sorted[mid]) hi = mid else lo = mid + 1
            }
            return lo
        }
    }
}

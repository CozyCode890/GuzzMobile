package io.github.cozycode890.guzz.core.audio

import io.github.cozycode890.guzz.core.audio.pcm.SampleSource
import io.github.cozycode890.guzz.core.audio.pcm.forEachBlock
import io.github.cozycode890.guzz.core.common.pyRoundToLong
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** Khoảng `[startMs, endMs)` tính bằng mili giây. */
data class MsRange(val startMs: Long, val endMs: Long)

/** Tìm các khoảng có tiếng nói. */
object SpeechSegments {
    private const val MAX_AMPLITUDE = 32768.0

    /**
     * Trả về các khoảng `[start_ms, end_ms]` CÓ tiếng nói; toàn bộ là lặng thì trả danh sách rỗng.
     * Port của audio_processing.py: `find_speech_segments` (kèm `_sum_squares_between`).
     *
     * Desktop tính tổng bình phương từng mili giây cho cả bài rồi cộng dồn, cần vài mảng dài bằng số mili giây (bài 2 giờ
     * là 7,2 triệu phần tử mỗi mảng). Ở đây đọc một lượt và chỉ giữ vòng đệm `minSilenceLen + 1` giá trị cộng dồn, nên bộ
     * nhớ không tăng theo độ dài bài. Những chỗ giữ đúng như numpy để khớp từng bit:
     * - `round(len / sr * 1000)` làm tròn nửa về chẵn, `int(...)` cắt về 0;
     * - tổng mỗi mili giây theo `np.add.reduceat`: phần tử đầu cộng với `pairwise_sum` của phần còn lại ([cellSum]);
     * - cộng dồn tuần tự như `np.cumsum`; `floor(sqrt(tổng / số mẫu) × 32768) <= ngưỡng`.
     */
    fun find(source: SampleSource, sr: Int, minSilenceLen: Int = 700, silenceThreshDb: Double = -32.0): List<MsRange> {
        val len = source.size
        val durationMs = pyRoundToLong(len.toDouble() / sr * 1000)
        val w = minSilenceLen
        if (w <= 0 || durationMs < w) return listOf(MsRange(0, durationMs))

        val numMs = (len.toDouble() / sr * 1000).toLong()
        val n = minOf(durationMs - w, numMs - w)
        if (n < 0) return listOf(MsRange(0, durationMs))

        val threshold = 10.0.pow(silenceThreshDb / 20.0) * MAX_AMPLITUDE
        fun timestamp(i: Long): Long {
            val t = i * sr / 1000
            return if (i == numMs) minOf(t, len) else t
        }

        // Vòng đệm giữ cumsum[k - w .. k]; cumsum[0] = 0.
        val ring = DoubleArray(w + 1)
        var cum = 0.0
        ring[0] = 0.0

        val silentRanges = ArrayList<LongArray>()
        var rangeStart = -1L
        var lastSilent = -1L

        // Ô thứ `cell` là mili giây [timestamp(cell), timestamp(cell + 1)); bình phương các mẫu của ô đang gom ở `squares`.
        var cell = 0L
        var cellEnd = timestamp(1)
        var squares = DoubleArray(maxOf(4, sr / 1000 + 2))
        var count = 0

        fun onCumulative(k: Long) {
            // Đã có cumsum[k]: cửa sổ j = k - w kết thúc ở đây.
            val j = k - w
            if (j < 0 || j > n) return
            val windowSum = cum - ring[(j % (w + 1)).toInt()]
            val windowCount = maxOf(1L, timestamp(k) - timestamp(j))
            val rms = floor(sqrt(windowSum / windowCount.toDouble()) * MAX_AMPLITUDE)
            if (rms <= threshold) {
                if (rangeStart < 0) {
                    rangeStart = j
                } else if (j - lastSilent > w) {
                    silentRanges += longArrayOf(rangeStart, lastSilent + w)
                    rangeStart = j
                }
                lastSilent = j
            }
        }

        fun closeCell() {
            cum += cellSum(squares, count)
            cell++
            ring[(cell % (w + 1)).toInt()] = cum
            onCumulative(cell)
            count = 0
            if (cell < numMs) cellEnd = timestamp(cell + 1)
        }

        source.forEachBlock { buf, blockCount, start ->
            var i = 0
            while (i < blockCount && cell < numMs) {
                val idx = start + i
                if (idx >= cellEnd) {
                    closeCell()
                    continue
                }
                if (count == squares.size) squares = squares.copyOf(squares.size * 2)
                val v = buf[i].toDouble()
                squares[count++] = v * v
                i++
            }
        }
        // Các ô cuối chưa đóng (mẫu cuối của ô cuối trùng mẫu cuối của tệp).
        while (cell < numMs) closeCell()

        if (rangeStart < 0) return listOf(MsRange(0, durationMs))
        silentRanges += longArrayOf(rangeStart, lastSilent + w)

        val first = silentRanges[0]
        if (first[0] == 0L && first[1] >= durationMs) return emptyList()

        val voiced = ArrayList<MsRange>()
        var prev = 0L
        for ((a, b) in silentRanges) {
            if (a > prev) voiced += MsRange(prev, a)
            prev = b
        }
        if (prev < durationMs) voiced += MsRange(prev, durationMs)
        return voiced
    }

    /**
     * Tổng một ô như `np.add.reduceat` trên float64: phần tử đầu làm giá trị khởi đầu, phần còn lại cộng bằng
     * `pairwise_sum` của numpy. Đã kiểm trên numpy 2.5.3 của desktop: khớp từng bit với mọi độ dài ô thử (2–300).
     * Ô rỗng (chỉ xảy ra khi tần số < 1 kHz) trả 0.
     */
    internal fun cellSum(values: DoubleArray, count: Int): Double {
        if (count == 0) return 0.0
        return values[0] + pairwiseSum(values, 1, count - 1)
    }

    /** Port của `pairwise_sum` trong numpy (loops_utils.h): khối ≤ 128 dùng 8 tổng riêng, lớn hơn thì chia đôi. */
    internal fun pairwiseSum(a: DoubleArray, from: Int, n: Int): Double {
        if (n < 8) {
            var res = 0.0
            for (i in from until from + n) res += a[i]
            return res
        }
        if (n <= 128) {
            val r = DoubleArray(8) { a[from + it] }
            var i = 8
            while (i < n - (n % 8)) {
                for (j in 0 until 8) r[j] += a[from + i + j]
                i += 8
            }
            var res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]))
            while (i < n) {
                res += a[from + i]
                i++
            }
            return res
        }
        var n2 = n / 2
        n2 -= n2 % 8
        return pairwiseSum(a, from, n2) + pairwiseSum(a, from + n2, n - n2)
    }
}

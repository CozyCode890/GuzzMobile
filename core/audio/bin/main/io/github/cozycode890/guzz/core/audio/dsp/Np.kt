package io.github.cozycode890.guzz.core.audio.dsp

import kotlin.math.floor

/**
 * Vài hàm numpy / scipy.ndimage mà các bước làm sạch dùng, viết lại đúng cách tính của numpy 2.x (thứ tự cộng, công
 * thức nội suy) để kết quả khớp tới sai số làm tròn.
 */
object Np {

    /** `np.percentile(a, q)` cách "linear" (mặc định), [a] không có NaN. */
    fun percentile(a: DoubleArray, q: Double): Double {
        require(a.isNotEmpty()) { "percentile của mảng rỗng" }
        val s = a.sortedArray()
        val virtual = (q / 100.0) * (s.size - 1)
        val prev = floor(virtual).toInt().coerceIn(0, s.size - 1)
        val next = minOf(prev + 1, s.size - 1)
        return lerp(s[prev], s[next], virtual - prev)
    }

    /** `numpy.lib._function_base_impl._lerp`: a + (b−a)·t, riêng t ≥ 0,5 thì b − (b−a)·(1−t). */
    fun lerp(a: Double, b: Double, t: Double): Double {
        val diff = b - a
        return if (t >= 0.5) b - diff * (1 - t) else a + diff * t
    }

    /** Trung vị bỏ NaN (`np.nanmedian`); toàn NaN thì NaN. */
    fun nanMedian(a: DoubleArray): Double {
        val s = a.filter { !it.isNaN() }.sorted()
        if (s.isEmpty()) return Double.NaN
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /**
     * `np.interp(x, xp, fp)` cho một điểm, [xp] tăng dần: ngoài khoảng thì lấy giá trị biên, trong khoảng thì
     * `slope·(x − xp[j]) + fp[j]` như bản C của numpy.
     */
    fun interp(x: Double, xp: DoubleArray, fp: DoubleArray): Double {
        val n = xp.size
        if (n == 1) return fp[0]
        if (x.isNaN()) return x
        if (x > xp[n - 1]) return fp[n - 1]
        if (x < xp[0]) return fp[0]
        if (x == xp[n - 1]) return fp[n - 1]
        // j lớn nhất với xp[j] <= x
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xp[mid] <= x) lo = mid else hi = mid
        }
        return interpAt(x, xp, fp, lo)
    }

    /** Bước nội suy trong đoạn `[xp[j], xp[j+1]]` của `np.interp`. */
    fun interpAt(x: Double, xp: DoubleArray, fp: DoubleArray, j: Int): Double {
        if (j >= xp.size - 1) return fp[xp.size - 1]
        val slope = (fp[j + 1] - fp[j]) / (xp[j + 1] - xp[j])
        var r = slope * (x - xp[j]) + fp[j]
        if (r.isNaN()) {
            r = slope * (x - xp[j + 1]) + fp[j + 1]
            if (r.isNaN() && fp[j] == fp[j + 1]) r = fp[j]
        }
        return r
    }

    /**
     * Port của audio_processing.py: `_moving_average`: trung bình trượt có căn giữa, cùng độ dài, ở biên thì rút ngắn
     * cửa sổ. Tổng cộng dồn tuần tự bằng double như `np.cumsum`.
     */
    fun movingAverage(x: DoubleArray, window: Int): DoubleArray {
        if (window <= 1 || x.isEmpty()) return x
        val cumsum = DoubleArray(x.size + 1)
        for (i in x.indices) cumsum[i + 1] = cumsum[i] + x[i]
        return DoubleArray(x.size) { i ->
            val head = maxOf(0, i - window / 2)
            val tail = minOf(x.size, i + window - window / 2)
            (cumsum[tail] - cumsum[head]) / (tail - head)
        }
    }

    /** Chỉ số phản xạ kiểu `mode="reflect"` của scipy.ndimage (d c b a | a b c d | d c b a). */
    private fun reflect(i: Int, n: Int): Int {
        var k = i
        val period = 2 * n
        k %= period
        if (k < 0) k += period
        return if (k < n) k else period - 1 - k
    }

    /** `scipy.ndimage.maximum_filter1d(x, size)` (mode reflect, origin 0). */
    fun maximumFilter1d(x: DoubleArray, size: Int): DoubleArray {
        val left = size / 2
        return DoubleArray(x.size) { i ->
            var m = Double.NEGATIVE_INFINITY
            for (k in -left until size - left) {
                val v = x[reflect(i + k, x.size)]
                if (v > m) m = v
            }
            m
        }
    }

    /**
     * `scipy.ndimage.uniform_filter1d(x, size)` (mode reflect, origin 0), cùng cách cộng trượt của `NI_UniformFilter1D`:
     * tổng cửa sổ đầu chia size, rồi mỗi bước cộng (vào − ra) / size.
     */
    fun uniformFilter1d(x: DoubleArray, size: Int): DoubleArray {
        val n = x.size
        val left = size / 2
        val out = DoubleArray(n)
        if (n == 0) return out
        fun at(i: Int) = x[reflect(i, n)]
        var tmp = 0.0
        for (k in 0 until size) tmp += at(k - left)
        tmp /= size
        out[0] = tmp
        for (i in 1 until n) {
            tmp += (at(i + size - 1 - left) - at(i - 1 - left)) / size
            out[i] = tmp
        }
        return out
    }
}

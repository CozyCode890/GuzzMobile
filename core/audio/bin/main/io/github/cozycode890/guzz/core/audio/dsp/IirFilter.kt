package io.github.cozycode890.guzz.core.audio.dsp

/**
 * Lọc IIR như scipy 1.18.1: `sosfilt` (có trạng thái `zi`), `sosfilt_zi`, `sosfiltfilt`, `lfilter_zi`, `filtfilt` (cách
 * `pad`). Tính bằng double như scipy khi `sos` là float64 (đầu vào float32 cũng được nâng lên float64).
 */
object IirFilter {

    /**
     * Port của `sosfilt(sos, x, zi=zi)`: dạng trực tiếp II chuyển vị từng tầng, như `_sosfilt` của scipy. Lọc tại chỗ
     * `x[from until to]`; [zi] (n_sections × 2) được cập nhật thành trạng thái cuối, để lọc khối kế tiếp nối liền.
     * Giả định a0 = 1 (butter luôn ra như vậy).
     */
    fun sosfilt(sos: Array<DoubleArray>, x: DoubleArray, zi: Array<DoubleArray>, from: Int = 0, to: Int = x.size) {
        val n = sos.size
        for (i in from until to) {
            var cur = x[i]
            for (s in 0 until n) {
                val c = sos[s]
                val z = zi[s]
                val new = c[0] * cur + z[0]
                z[0] = c[1] * cur - c[4] * new + z[1]
                z[1] = c[2] * cur - c[5] * new
                cur = new
            }
            x[i] = cur
        }
    }

    /** Port của `lfilter_zi(b, a)`: trạng thái đầu ứng với đáp ứng bậc thang đã ổn định. */
    fun lfilterZi(bIn: DoubleArray, aIn: DoubleArray): DoubleArray {
        var b = bIn
        var a = aIn
        if (a[0] != 1.0) {
            b = DoubleArray(b.size) { b[it] / aIn[0] }
            a = DoubleArray(a.size) { a[it] / aIn[0] }
        }
        val sumA = a.sum()
        require(sumA != 0.0) { "bộ lọc không ổn định: sum(a) == 0" }
        val yInf = b.sum() / sumA
        val n = maxOf(a.size, b.size)
        val c = DoubleArray(n) { (if (it < b.size) b[it] else 0.0) - yInf * (if (it < a.size) a[it] else 0.0) }
        // flip(cumsum(flip(c)))[1:]
        val out = DoubleArray(n - 1)
        var acc = 0.0
        for (i in n - 1 downTo 1) {
            acc += c[i]
            out[i - 1] = acc
        }
        return out
    }

    /** Port của `sosfilt_zi`. */
    fun sosfiltZi(sos: Array<DoubleArray>): Array<DoubleArray> {
        var scale = 1.0
        return Array(sos.size) { s ->
            val b = sos[s].copyOfRange(0, 3)
            val a = sos[s].copyOfRange(3, 6)
            val zi = lfilterZi(b, a)
            val row = doubleArrayOf(scale * zi[0], scale * zi[1])
            scale *= b.sum() / a.sum()
            row
        }
    }

    /** `padlen` mặc định của `sosfiltfilt`: 3 × (2 × số tầng + 1 − số cực / không điểm ở gốc). */
    fun sosfiltfiltPadlen(sos: Array<DoubleArray>): Int {
        val zerosB2 = sos.count { it[2] == 0.0 }
        val zerosA2 = sos.count { it[5] == 0.0 }
        return 3 * (2 * sos.size + 1 - minOf(zerosB2, zerosA2))
    }

    /**
     * Port của `sosfiltfilt(sos, x)` (padtype "odd", padlen mặc định): lọc pha 0 bằng lọc xuôi rồi ngược, đệm phản đối
     * xứng hai đầu. Trả về mảng mới cùng độ dài.
     */
    fun sosfiltfilt(sos: Array<DoubleArray>, x: DoubleArray, from: Int = 0, to: Int = x.size): DoubleArray {
        val len = to - from
        val edge = sosfiltfiltPadlen(sos)
        require(len > edge) { "Độ dài tín hiệu phải lớn hơn padlen ($edge): $len" }
        // odd_ext: 2*x[0] - x[edge:0:-1], x, 2*x[-1] - x[-2:-(edge+2):-1]
        val ext = DoubleArray(len + 2 * edge)
        val x0 = x[from]
        val xl = x[to - 1]
        for (i in 0 until edge) ext[i] = 2 * x0 - x[from + edge - i]
        System.arraycopy(x, from, ext, edge, len)
        for (i in 0 until edge) ext[edge + len + i] = 2 * xl - x[to - 2 - i]
        val ziBase = sosfiltZi(sos)
        var zi = Array(sos.size) { s -> DoubleArray(2) { ziBase[s][it] * ext[0] } }
        sosfilt(sos, ext, zi)
        ext.reverse()
        val y0 = ext[0]
        zi = Array(sos.size) { s -> DoubleArray(2) { ziBase[s][it] * y0 } }
        sosfilt(sos, ext, zi)
        ext.reverse()
        return ext.copyOfRange(edge, edge + len)
    }

    /**
     * Bộ lọc bậc một của noisereduce: `filtfilt([b], [1, b - 1], x, padtype=None)` trên dãy `x[offset + k*stride]`,
     * k < [count], tại chỗ. Không đệm (padtype=None) nhưng vẫn khởi đầu bằng `lfilter_zi` × mẫu đầu như scipy.
     */
    fun onePoleFiltfilt(b: Double, x: DoubleArray, offset: Int, stride: Int, count: Int) {
        if (count == 0) return
        // lfilter_zi([b], [1, b-1]) = [1 - b]; y[n] = b·x[n] + z, z ← −(b − 1)·y[n].
        val a1 = b - 1.0
        val zi = lfilterZi(doubleArrayOf(b), doubleArrayOf(1.0, a1))[0]
        var z = zi * x[offset]
        var idx = offset
        for (k in 0 until count) {
            val y = b * x[idx] + z
            z = -a1 * y
            x[idx] = y
            idx += stride
        }
        idx -= stride
        z = zi * x[idx]
        for (k in 0 until count) {
            val y = b * x[idx] + z
            z = -a1 * y
            x[idx] = y
            idx -= stride
        }
    }
}

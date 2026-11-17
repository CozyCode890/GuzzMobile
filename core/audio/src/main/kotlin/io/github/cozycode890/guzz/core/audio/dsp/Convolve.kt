package io.github.cozycode890.guzz.core.audio.dsp

/**
 * Tích chập 2 chiều kiểu `scipy.signal.fftconvolve(a, k, mode="same")`, tính trực tiếp thay vì qua FFT.
 *
 * Vì sao không dùng FFT như D83 dự kiến: nhân làm mượt mặt nạ của noisereduce là tích ngoài của hai vectơ
 * (`_smoothing_filter`), nên chập riêng theo từng trục (33 + 7 phép nhân mỗi ô) vừa nhanh hơn FFT 2 chiều đệm lên
 * luỹ thừa 2 (1 024 × 4 096 ô cho mỗi khối) vừa không cần bộ nhớ đó. Kết quả bằng nhau tới sai số làm tròn (~1e-16).
 */
object Convolve {

    /**
     * `fftconvolve(a, k, mode="same")` với a có [rows] × [cols] (hàng liên tiếp), k có [kRows] × [kCols]. Trả mảng mới.
     * Bản tổng quát, chậm (rows·cols·kRows·kCols), dùng cho test.
     */
    fun same2d(a: DoubleArray, rows: Int, cols: Int, k: DoubleArray, kRows: Int, kCols: Int): DoubleArray {
        val out = DoubleArray(rows * cols)
        val r0 = (kRows - 1) / 2
        val c0 = (kCols - 1) / 2
        for (i in 0 until rows) for (j in 0 until cols) {
            var s = 0.0
            for (p in 0 until kRows) {
                val ai = i + r0 - p
                if (ai !in 0 until rows) continue
                for (q in 0 until kCols) {
                    val aj = j + c0 - q
                    if (aj !in 0 until cols) continue
                    s += a[ai * cols + aj] * k[p * kCols + q]
                }
            }
            out[i * cols + j] = s
        }
        return out
    }

    /**
     * Chập "same" một chiều tại chỗ trên dãy `x[offset + i*stride]`, i < [count], với nhân [k] (đối xứng hay không đều
     * được, theo đúng định nghĩa tích chập). [tmp] ≥ [count] phần tử.
     */
    fun same1d(x: DoubleArray, offset: Int, stride: Int, count: Int, k: DoubleArray, tmp: DoubleArray) {
        val c0 = (k.size - 1) / 2
        for (i in 0 until count) tmp[i] = x[offset + i * stride]
        for (i in 0 until count) {
            var s = 0.0
            val lo = maxOf(0, i + c0 - (count - 1))
            val hi = minOf(k.size - 1, i + c0)
            for (q in lo..hi) s += tmp[i + c0 - q] * k[q]
            x[offset + i * stride] = s
        }
    }
}

package io.github.cozycode890.guzz.core.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * STFT / ISTFT như `scipy.signal.stft` / `istft` 1.18.1 với đúng các tham số noisereduce dùng: cửa sổ `hann_periodic`
 * dài [nperseg], `nfft` = [nperseg], bước [hop], `boundary="zeros"` (đệm nperseg/2 số 0 hai đầu), `padded=False`,
 * `detrend=False`, `scaling="spectrum"`, một phía. Kiểu số: double / phức double như scipy với đầu vào float64 (noisereduce
 * chép khối vào `np.zeros` float64).
 *
 * Phổ lưu theo khung: phần tử (khung t, bin f) ở chỉ số `t * bins + f`.
 */
class Stft(val nperseg: Int = 1024, val hop: Int = 256) {
    private val fft = RealFft(nperseg)
    val bins = fft.bins
    private val pad = nperseg / 2

    /** Port của `get_window("hann_periodic", M)` = `general_cosine(M, [0.5, 0.5], sym=False)`. */
    val window: DoubleArray = run {
        // linspace(-π, π, M + 1)[:-1]: arange * step + start, step = 2π / M.
        val step = (PI - -PI) / nperseg
        DoubleArray(nperseg) { 0.5 * cos(0.0) + 0.5 * cos(it * step + -PI) }
    }

    private val winSum = window.sum()
    private val frame = DoubleArray(nperseg)

    /** Số khung của tín hiệu dài [length] (padded=False): `(length + 2·pad − nperseg) / hop + 1`. */
    fun frames(length: Int): Int = (length + 2 * pad - nperseg) / hop + 1

    /** Độ dài tín hiệu `istft` trả về cho [frames] khung: `(frames − 1) · hop` sau khi cắt pad hai đầu. */
    fun istftLength(frames: Int): Int = nperseg + (frames - 1) * hop - 2 * pad

    /**
     * `stft(x[offset until offset + length])` vào [outRe], [outIm] (mỗi mảng ≥ `frames(length) * bins`).
     */
    fun forward(x: DoubleArray, offset: Int, length: Int, outRe: DoubleArray, outIm: DoubleArray) {
        val nFrames = frames(length)
        // scale = sqrt(1 / win.sum()**2)
        val scale = sqrt(1.0 / (winSum * winSum))
        for (t in 0 until nFrames) {
            val start = t * hop - pad // vị trí đầu khung trên tín hiệu gốc
            for (i in 0 until nperseg) {
                val pos = start + i
                frame[i] = if (pos in 0 until length) window[i] * x[offset + pos] else 0.0
            }
            val o = t * bins
            fft.forward(frame, 0, outRe, outIm, o)
            for (f in o until o + bins) {
                outRe[f] *= scale
                outIm[f] *= scale
            }
        }
    }

    /**
     * `istft` của [frames] khung trong ([re], [im]) ra `out[outOffset until outOffset + istftLength(frames)]`.
     * [norm] là bộ đệm ≥ `istftLength(frames) + 2·pad` phần tử (tái dùng giữa các lần gọi).
     */
    fun inverse(
        re: DoubleArray,
        im: DoubleArray,
        frames: Int,
        out: DoubleArray,
        outOffset: Int,
        work: DoubleArray,
        norm: DoubleArray,
    ) {
        val full = nperseg + (frames - 1) * hop
        require(work.size >= full && norm.size >= full) { "bộ đệm istft quá nhỏ" }
        java.util.Arrays.fill(work, 0, full, 0.0)
        java.util.Arrays.fill(norm, 0, full, 0.0)
        for (t in 0 until frames) {
            fft.inverse(re, im, t * bins, frame, 0)
            val base = t * hop
            for (i in 0 until nperseg) {
                // xsubs *= win.sum(); x += xsubs * win; norm += win**2
                work[base + i] += frame[i] * winSum * window[i]
                norm[base + i] += window[i] * window[i]
            }
        }
        val length = full - 2 * pad
        for (i in 0 until length) {
            val nv = norm[pad + i]
            out[outOffset + i] = work[pad + i] / (if (nv > 1e-10) nv else 1.0)
        }
    }
}

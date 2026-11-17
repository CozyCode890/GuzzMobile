package io.github.cozycode890.guzz.core.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FFT thực độ dài [n] (luỹ thừa 2, ≥ 2), double, tự viết (D83): `rfft` / `irfft` như `numpy.fft` / `scipy.fft` (thuận
 * không chia, nghịch chia n). Tính qua FFT phức radix-2 độ dài n/2 rồi tách phổ, nên một khung 1 024 mẫu của STFT chỉ
 * tốn một FFT phức 512 điểm.
 *
 * Không an toàn khi dùng chung giữa các luồng (bộ đệm dùng lại); mỗi luồng một đối tượng.
 */
class RealFft(val n: Int) {
    init {
        require(n >= 2 && n and (n - 1) == 0) { "n phải là luỹ thừa 2 và ≥ 2: $n" }
    }

    private val half = n / 2

    /** Số hệ số của phổ một phía: n/2 + 1. */
    val bins = half + 1

    // Hệ số xoay của FFT phức n/2 điểm: e^{-2πik/(n/2)}, k < n/4.
    private val cosHalf = DoubleArray(maxOf(1, half / 2)) { cos(2 * PI * it / half) }
    private val sinHalf = DoubleArray(maxOf(1, half / 2)) { -sin(2 * PI * it / half) }

    // Hệ số tách phổ thực: e^{-2πik/n}, k ≤ n/2. Tính thẳng bằng cos/sin (không nhân dồn) để sai số không tích luỹ.
    private val cosFull = DoubleArray(half + 1) { cos(2 * PI * it / n) }
    private val sinFull = DoubleArray(half + 1) { -sin(2 * PI * it / n) }

    private val bitrev = IntArray(half).also { rev ->
        var bits = 0
        while (1 shl bits < half) bits++
        for (i in 0 until half) {
            var r = 0
            var v = i
            repeat(bits) { r = (r shl 1) or (v and 1); v = v shr 1 }
            rev[i] = r
        }
    }

    private val re = DoubleArray(half)
    private val im = DoubleArray(half)

    /**
     * Phổ một phía của `x[offset until offset + n]` vào `outRe`, `outIm` (mỗi mảng ≥ [bins] phần tử, ghi từ
     * [outOffset]).
     */
    fun forward(x: DoubleArray, offset: Int, outRe: DoubleArray, outIm: DoubleArray, outOffset: Int = 0) {
        // Gói cặp mẫu chẵn / lẻ thành số phức: z[k] = x[2k] + i x[2k+1].
        for (k in 0 until half) {
            val j = bitrev[k]
            re[j] = x[offset + 2 * k]
            im[j] = x[offset + 2 * k + 1]
        }
        complexInPlace(inverse = false)
        // X[k] = (Z[k] + conj Z[n/2-k]) / 2 + e^{-2πik/n} (Z[k] − conj Z[n/2-k]) / (2i)
        outRe[outOffset] = re[0] + im[0]
        outIm[outOffset] = 0.0
        outRe[outOffset + half] = re[0] - im[0]
        outIm[outOffset + half] = 0.0
        for (k in 1 until half) {
            val m = half - k
            val evRe = 0.5 * (re[k] + re[m])
            val evIm = 0.5 * (im[k] - im[m])
            val odRe = 0.5 * (im[k] + im[m])
            val odIm = -0.5 * (re[k] - re[m])
            val c = cosFull[k]
            val s = sinFull[k]
            outRe[outOffset + k] = evRe + c * odRe - s * odIm
            outIm[outOffset + k] = evIm + c * odIm + s * odRe
        }
    }

    /**
     * Nghịch của [forward] (chia n như `irfft`), từ phổ một phía `(inRe, inIm)[inOffset until inOffset + bins]` ra
     * `out[outOffset until outOffset + n]`. Như pocketfft, bỏ phần ảo của bin 0 và bin n/2.
     */
    fun inverse(inRe: DoubleArray, inIm: DoubleArray, inOffset: Int, out: DoubleArray, outOffset: Int = 0) {
        // Z[k] = E[k] + i O[k], E[k] = (X[k] + conj X[n/2-k]) / 2, O[k] = e^{2πik/n} (X[k] − conj X[n/2-k]) / 2.
        for (k in 0 until half) {
            val m = half - k
            val aRe = inRe[inOffset + k]
            val aIm = if (k == 0) 0.0 else inIm[inOffset + k]
            val bRe = inRe[inOffset + m]
            val bIm = if (m == half) 0.0 else -inIm[inOffset + m] // conj X[n/2-k]
            val evRe = 0.5 * (aRe + bRe)
            val evIm = 0.5 * (aIm + bIm)
            val dRe = 0.5 * (aRe - bRe)
            val dIm = 0.5 * (aIm - bIm)
            val c = cosFull[k]
            val s = -sinFull[k] // e^{+2πik/n}
            val odRe = c * dRe - s * dIm
            val odIm = c * dIm + s * dRe
            val j = bitrev[k]
            re[j] = evRe - odIm
            im[j] = evIm + odRe
        }
        complexInPlace(inverse = true)
        val scale = 1.0 / n
        for (k in 0 until half) {
            out[outOffset + 2 * k] = re[k] * 2 * scale
            out[outOffset + 2 * k + 1] = im[k] * 2 * scale
        }
    }

    /** FFT phức tại chỗ trên [re], [im] đã xếp theo thứ tự đảo bit; [inverse] đổi dấu mũ, không chia. */
    private fun complexInPlace(inverse: Boolean) {
        var size = 2
        while (size <= half) {
            val h = size / 2
            val step = half / size
            for (start in 0 until half step size) {
                for (j in 0 until h) {
                    val wr = cosHalf[j * step]
                    val wi = if (inverse) -sinHalf[j * step] else sinHalf[j * step]
                    val a = start + j
                    val b = a + h
                    val tr = wr * re[b] - wi * im[b]
                    val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            size *= 2
        }
    }
}

package io.github.cozycode890.guzz.core.audio.dsp

import io.github.cozycode890.guzz.core.audio.pcm.SampleSource
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Khử ồn bằng spectral gating không dừng (non-stationary). Port của noisereduce 3.0.3: `reduce_noise(stationary=False)`
 * = `SpectralGateNonStationary` (`spectral_gating_nonstationary`, `get_time_smoothed_representation`, `sigmoid`,
 * `_smoothing_filter`) và cách chia khối của `SpectralGate.get_traces` / `filter_chunk` / `_read_chunk`, với `n_jobs=1`
 * như desktop (D86).
 *
 * Như noisereduce, mỗi khối [chunkSize] mẫu được đọc kèm [padding] mẫu hai bên (ngoài bài thì là số 0) vào mảng
 * float64, tính hết bằng double / phức double (STFT của scipy chạy complex128 với đầu vào float64), rồi cắt lại đúng
 * khối và ép về float32. Khối cuối vẫn đọc đủ `chunkSize + 2·padding` (phần sau bài là 0) như `_get_filtered_chunk`.
 *
 * Làm mượt mặt nạ: nhân `_smoothing_filter` là tích ngoài của hai vectơ ([smoothingVector]) nên chập lần lượt theo trục
 * tần số rồi trục thời gian ([Convolve.same1d]), bằng `fftconvolve(mode="same")` tới sai số làm tròn (Nhật ký Stage 7).
 *
 * @param timeConstantS `time_constant_s`; desktop truyền `max(0.2, noise_sample_sec)`.
 */
class NoiseReducer(
    private val sampleRate: Int,
    private val timeConstantS: Double,
    private val propDecrease: Double,
    private val nFft: Int = 1024,
    private val hop: Int = nFft / 4,
    freqMaskSmoothHz: Double = 500.0,
    timeMaskSmoothMs: Double = 50.0,
    private val threshNMult: Double = 2.0,
    private val sigmoidSlope: Double = 10.0,
    val chunkSize: Int = 600_000,
    val padding: Int = 30_000,
) {
    private val stft = Stft(nFft, hop)
    private val bins = stft.bins

    // _generate_mask_smoothing_filter
    private val nGradFreq = (freqMaskSmoothHz / (sampleRate / (nFft / 2.0))).toInt()
    private val nGradTime = (timeMaskSmoothMs / ((hop.toDouble() / sampleRate) * 1000)).toInt()

    init {
        require(nGradFreq >= 1) { "freq_mask_smooth_hz needs to be at least ${(sampleRate / (nFft / 2.0)).toInt()}Hz" }
        require(nGradTime >= 1) { "time_mask_smooth_ms needs to be at least ${((hop.toDouble() / sampleRate) * 1000).toInt()}ms" }
    }

    private val smoothMask = !(nGradFreq == 1 && nGradTime == 1)
    private val kFreq = smoothingVector(nGradFreq)
    private val kTime = smoothingVector(nGradTime)

    // get_time_smoothed_representation: hệ số b của bộ lọc IIR bậc một.
    private val b: Double = run {
        val tFrames = timeConstantS * sampleRate / hop.toDouble()
        (sqrt(1 + 4 * tFrames * tFrames) - 1) / (2 * tFrames * tFrames)
    }

    /**
     * Đọc [input], ghi kết quả từng khối liên tiếp qua [sink] (float32, đúng thứ tự, tổng đủ `input.size` mẫu).
     * [onChunk] được gọi trước mỗi khối với (số khối đã xong, tổng số khối), để báo tiến độ và kiểm Dừng.
     */
    fun process(
        input: SampleSource,
        sink: (FloatArray, Int) -> Unit,
        onChunk: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val n = input.size
        if (n == 0L) return
        if (n > chunkSize) {
            // get_traces: khối ich1..ich2, mỗi khối filter_chunk(ich*chunk, (ich+1)*chunk).
            val last = ((n - 1) / chunkSize).toInt()
            for (ich in 0..last) {
                onChunk(ich, last + 1)
                val start = ich.toLong() * chunkSize
                val end = start + chunkSize
                val keep = (minOf(end, n) - start).toInt()
                val out = filterChunk(input, start, end)
                sink(out, keep)
            }
            onChunk(last + 1, last + 1)
        } else {
            onChunk(0, 1)
            sink(filterChunk(input, 0, n), n.toInt())
            onChunk(1, 1)
        }
    }

    // Bộ đệm dùng lại giữa các khối (khoảng 64 MB với khối 660 000 mẫu).
    private var chunk = DoubleArray(0)
    private var re = DoubleArray(0)
    private var im = DoubleArray(0)
    private var mag = DoubleArray(0)
    private var smooth = DoubleArray(0)
    private var work = DoubleArray(0)
    private var norm = DoubleArray(0)
    private var denoised = DoubleArray(0)
    private var readBuf = FloatArray(0)
    private var outBuf = FloatArray(0)

    /** Port của `filter_chunk` + `_read_chunk` + `_do_filter`: trả `[start, end)` đã lọc (float32). */
    private fun filterChunk(input: SampleSource, start: Long, end: Long): FloatArray {
        val i1 = start - padding
        val i2 = end + padding
        val len = (i2 - i1).toInt()
        if (chunk.size < len) chunk = DoubleArray(len) else java.util.Arrays.fill(chunk, 0, len, 0.0)
        // chunk = zeros; chunk[i1b - i1 : i2b - i1] = y[i1b:i2b]
        val i1b = maxOf(0L, i1)
        val i2b = minOf(input.size, i2)
        if (i2b > i1b) {
            val count = (i2b - i1b).toInt()
            if (readBuf.size < count) readBuf = FloatArray(count)
            input.read(i1b, readBuf, 0, count)
            val o = (i1b - i1).toInt()
            for (k in 0 until count) chunk[o + k] = readBuf[k].toDouble()
        }
        val filteredLen = spectralGating(len)
        // filtered_padded_chunk[:, start - i1 : end - i1]; phần sau len(denoised_signal) là 0 (denoised_channels = zeros).
        val outLen = (end - start).toInt()
        if (outBuf.size < outLen) outBuf = FloatArray(outLen)
        for (k in 0 until outLen) {
            val idx = padding + k
            outBuf[k] = if (idx < filteredLen) denoised[idx].toFloat() else 0f
        }
        return outBuf
    }

    /** Port của `spectral_gating_nonstationary` trên `chunk[0 until len]`; kết quả ở [denoised], trả độ dài của nó. */
    private fun spectralGating(len: Int): Int {
        val frames = stft.frames(len)
        val cells = frames * bins
        if (re.size < cells) {
            re = DoubleArray(cells)
            im = DoubleArray(cells)
            mag = DoubleArray(cells)
            smooth = DoubleArray(cells)
        }
        stft.forward(chunk, 0, len, re, im)
        for (k in 0 until cells) {
            val a = hypot(re[k], im[k])
            mag[k] = a
            smooth[k] = a
        }
        // get_time_smoothed_representation: filtfilt theo trục thời gian của từng bin.
        for (f in 0 until bins) IirFilter.onePoleFiltfilt(b, smooth, f, bins, frames)

        // sig_mult_above_thresh → sigmoid; ghi mặt nạ đè lên mag.
        for (k in 0 until cells) {
            val s = smooth[k]
            val x = (mag[k] - s) / s
            mag[k] = 1.0 / (1.0 + exp(-(x + -threshNMult) * sigmoidSlope))
        }
        if (smoothMask) {
            val tmp = smooth // dùng lại làm bộ đệm
            for (t in 0 until frames) Convolve.same1d(mag, t * bins, 1, bins, kFreq, tmp)
            for (f in 0 until bins) Convolve.same1d(mag, f, bins, frames, kTime, tmp)
        }
        val keep = 1.0 - propDecrease
        for (k in 0 until cells) {
            val m = mag[k] * propDecrease + 1.0 * keep
            re[k] *= m
            im[k] *= m
        }
        val outLen = stft.istftLength(frames)
        val full = outLen + nFft
        if (work.size < full) {
            work = DoubleArray(full)
            norm = DoubleArray(full)
        }
        if (denoised.size < outLen) denoised = DoubleArray(outLen)
        stft.inverse(re, im, frames, denoised, 0, work, norm)
        return outLen
    }

    companion object {
        /**
         * Một trục của `_smoothing_filter(n_grad_freq, n_grad_time)`, đã chuẩn hoá tổng = 1:
         * `concatenate([linspace(0, 1, n+1, endpoint=False), linspace(1, 0, n+2)])[1:-1]`.
         */
        fun smoothingVector(n: Int): DoubleArray {
            val up = DoubleArray(n + 1) { it * (1.0 / (n + 1)) + 0.0 }
            val step = (0.0 - 1.0) / (n + 1)
            val down = DoubleArray(n + 2) { if (it == n + 1) 0.0 else it * step + 1.0 }
            val v = (up + down).copyOfRange(1, up.size + down.size - 1)
            val s = v.sum()
            return DoubleArray(v.size) { v[it] / s }
        }
    }
}

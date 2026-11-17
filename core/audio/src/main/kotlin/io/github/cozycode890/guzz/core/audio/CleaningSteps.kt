package io.github.cozycode890.guzz.core.audio

import io.github.cozycode890.guzz.core.audio.dsp.Butterworth
import io.github.cozycode890.guzz.core.audio.dsp.IirFilter
import io.github.cozycode890.guzz.core.audio.dsp.Np
import io.github.cozycode890.guzz.core.audio.pcm.DEFAULT_BLOCK_SAMPLES
import io.github.cozycode890.guzz.core.audio.pcm.SampleSource
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Nơi một bước làm sạch ghi kết quả, lần lượt từ mẫu đầu tới mẫu cuối (tệp `tam_NN.f32`, hay mảng trong test). */
fun interface SampleSink {
    fun write(buf: FloatArray, count: Int)
}

/** Báo tiến độ (0..1) của một bước và kiểm Dừng; gọi sau mỗi khối. */
fun interface StepProgress {
    fun update(fraction: Double)
}

/** Số mẫu mỗi khối khi lọc / ghìm, như `_FILTER_BLOCK` của desktop (~4 MB float32). */
internal const val FILTER_BLOCK = 1 shl 20

/** Chép nguyên nguồn sang [sink] (bước bị bỏ qua). */
internal fun SampleSource.copyTo(sink: SampleSink, progress: StepProgress) {
    val buf = FloatArray(DEFAULT_BLOCK_SAMPLES)
    var pos = 0L
    while (pos < size) {
        val n = minOf(buf.size.toLong(), size - pos).toInt()
        read(pos, buf, 0, n)
        sink.write(buf, n)
        pos += n
        progress.update(pos.toDouble() / size)
    }
}

/**
 * Lọc dải tần giọng người. Port của audio_processing.py: `bandpass_filter` (Butterworth bậc [order] dạng SOS, `sosfilt`
 * có trạng thái `zi` qua các khối, kết quả float64 gán lại float32 như desktop).
 *
 * @return `false` khi desktop trả nguyên tín hiệu (`low <= 0` hoặc `low >= high` sau khi kẹp `high` dưới Nyquist): khi đó
 * không ghi gì ra [sink], người gọi dùng thẳng nguồn.
 */
object Bandpass {
    fun applies(sampleRate: Int, low: Int, high: Int): Boolean {
        val nyq = 0.5 * sampleRate
        val h = minOf(high.toDouble(), nyq - 1)
        return !(low <= 0 || low >= h)
    }

    fun filter(
        input: SampleSource,
        sampleRate: Int,
        low: Int,
        high: Int,
        sink: SampleSink,
        progress: StepProgress = StepProgress { },
        order: Int = 5,
    ): Boolean {
        if (!applies(sampleRate, low, high)) return false
        val nyq = 0.5 * sampleRate
        val h = minOf(high.toDouble(), nyq - 1)
        val sos = Butterworth.bandpass(order, low / nyq, h / nyq)
        val zi = Array(sos.size) { DoubleArray(2) }
        val buf = FloatArray(DEFAULT_BLOCK_SAMPLES)
        val x = DoubleArray(DEFAULT_BLOCK_SAMPLES)
        var pos = 0L
        val n = input.size
        while (pos < n) {
            val count = minOf(buf.size.toLong(), n - pos).toInt()
            input.read(pos, buf, 0, count)
            for (i in 0 until count) x[i] = buf[i].toDouble()
            IirFilter.sosfilt(sos, x, zi, 0, count)
            for (i in 0 until count) buf[i] = x[i].toFloat()
            sink.write(buf, count)
            pos += count
            progress.update(pos.toDouble() / n)
        }
        return true
    }
}

/**
 * Cân bằng âm lượng theo thời gian khi giảng viên đi lại. Port của audio_processing.py: `level_loudness`.
 *
 * Hai lượt đọc nguồn: (1) năng lượng từng khối 50 ms → đường gain (mảng nhỏ: 72 000 số cho 1 giờ); (2) nhân gain nội
 * suy theo từng mẫu (float32 như desktop) rồi ghi ra.
 */
object LevelLoudness {
    private const val LEVEL_BLOCK_MS = 50
    private const val MAX_BOOST_DB = 12.0
    private const val MAX_CUT_DB = 6.0
    private const val GAIN_SMOOTH_SEC = 0.5
    private const val SILENCE_BELOW_PEAK_DB = 25.0

    /**
     * Đường gain (float32) của từng khối, `null` khi desktop trả nguyên tín hiệu (cửa sổ ≤ 0, dưới 4 khối, không khối
     * nào có tiếng).
     */
    fun gains(input: SampleSource, sampleRate: Int, windowSec: Double, progress: StepProgress = StepProgress { }): FloatArray? {
        if (windowSec <= 0) return null
        val block = maxOf(1, (sampleRate * LEVEL_BLOCK_MS / 1000.0).toInt())
        val numBlocks = (input.size / block).toInt()
        if (numBlocks < 4) return null

        // np.mean(np.square(segments, dtype=float32), axis=1): kết quả float32. Cộng bằng double rồi làm tròn về float32
        // (numpy cộng cặp đôi bằng float32: lệch cỡ 1e-7 tương đối, gain lệch cỡ 1e-6 dB).
        val energy = DoubleArray(numBlocks)
        val buf = FloatArray(block * maxOf(1, DEFAULT_BLOCK_SAMPLES / block))
        var k = 0
        while (k < numBlocks) {
            val m = minOf(numBlocks, k + buf.size / block)
            val count = (m - k) * block
            input.read(k.toLong() * block, buf, 0, count)
            for (b in 0 until m - k) {
                var s = 0.0
                for (i in b * block until (b + 1) * block) {
                    val sq = buf[i] * buf[i]
                    s += sq.toDouble()
                }
                energy[k + b] = (s / block).toFloat().toDouble()
            }
            k = m
            progress.update(0.5 * k / numBlocks)
        }

        val envelope = Np.movingAverage(energy, maxOf(1, (windowSec * 1000 / LEVEL_BLOCK_MS).toInt()))
        val envelopeDb = DoubleArray(numBlocks) { 10.0 * log10(maxOf(envelope[it], 1e-12)) }
        val threshold = Np.percentile(envelopeDb, 95.0) - SILENCE_BELOW_PEAK_DB
        val voicedIdx = (0 until numBlocks).filter { envelopeDb[it] > threshold }
        if (voicedIdx.isEmpty()) return null
        val common = Np.percentile(DoubleArray(voicedIdx.size) { envelopeDb[voicedIdx[it]] }, 75.0)

        val xp = DoubleArray(voicedIdx.size) { voicedIdx[it].toDouble() }
        val fp = DoubleArray(voicedIdx.size) { (common - envelopeDb[voicedIdx[it]]).coerceIn(-MAX_CUT_DB, MAX_BOOST_DB) }
        var gainDb = DoubleArray(numBlocks) { Np.interp(it.toDouble(), xp, fp) }
        gainDb = Np.movingAverage(gainDb, maxOf(1, (GAIN_SMOOTH_SEC * 1000 / LEVEL_BLOCK_MS).toInt()))
        return FloatArray(numBlocks) { 10.0.pow(gainDb[it] / 20.0).toFloat() }
    }

    /** Port của `level_loudness`; trả `false` (không ghi gì) khi desktop trả nguyên tín hiệu. */
    fun apply(
        input: SampleSource,
        sampleRate: Int,
        windowSec: Double,
        sink: SampleSink,
        progress: StepProgress = StepProgress { },
    ): Boolean {
        val gain = gains(input, sampleRate, windowSec, progress) ?: return false
        val block = maxOf(1, (sampleRate * LEVEL_BLOCK_MS / 1000.0).toInt())
        val centers = DoubleArray(gain.size) { it * block + block / 2.0 }
        val fp = DoubleArray(gain.size) { gain[it].toDouble() }
        val buf = FloatArray(DEFAULT_BLOCK_SAMPLES)
        val n = input.size
        var pos = 0L
        var j = 0 // đoạn nội suy hiện tại: centers[j] <= vị trí < centers[j+1]
        while (pos < n) {
            val count = minOf(buf.size.toLong(), n - pos).toInt()
            input.read(pos, buf, 0, count)
            for (i in 0 until count) {
                val x = (pos + i).toDouble()
                val g = when {
                    x <= centers[0] -> fp[0]
                    x >= centers[centers.size - 1] -> fp[fp.size - 1]
                    else -> {
                        while (centers[j + 1] <= x) j++
                        Np.interpAt(x, centers, fp, j)
                    }
                }
                buf[i] = buf[i] * g.toFloat()
            }
            sink.write(buf, count)
            pos += count
            progress.update(0.5 + 0.5 * pos / n)
        }
        return true
    }
}

/** Một vùng đã ghìm: (bắt đầu, kết thúc) giây, mức ghìm sâu nhất (dB), lúc ghìm sâu nhất (giây). */
data class ClampRegion(val start: Double, val end: Double, val depthDb: Double, val peakAt: Double)

/**
 * Ghìm tiếng động đột ngột (gõ bàn, đập, rơi đồ, ho) xuống "mức thường + [thresholdDb]". Port của audio_processing.py:
 * `suppress_transients`, `_low_band`, `_rolling_percentile`, `_clamp_levels`.
 *
 * Hai lượt đọc nguồn như desktop: (1) năng lượng khối 10 ms của dải dưới 1 kHz và phần còn lại → mức ghìm từng khối
 * (mảng nhỏ); (2) chỉ ở những lát có ghìm thì lọc dải dưới lại và ghi `g_high·y + (g_low − g_high)·dải_dưới`, lát khác
 * chép nguyên. Lát dài `step` mẫu, đọc thêm 1 024 mẫu hai bên cho `sosfiltfilt` như desktop.
 */
class TransientSuppressor(private val sampleRate: Int, private val thresholdDb: Double) {
    private val block = maxOf(1, (sampleRate * TRANSIENT_BLOCK_MS / 1000.0).toInt())
    private val sos = Butterworth.lowpass(4, minOf(TRANSIENT_SPLIT_HZ, 0.4 * sampleRate) / (0.5 * sampleRate))
    private val step = maxOf(1, FILTER_BLOCK / block) * block

    /** Mức ghìm (dB) từng khối: [0] dải dưới (= tổng), [1] dải trên; để test so với desktop. */
    var cut: Array<DoubleArray>? = null
        private set

    /**
     * Ghìm [input] ghi ra [sink]; trả các vùng đã ghìm. Không có gì để ghìm thì trả danh sách rỗng và KHÔNG ghi gì (nguồn
     * giữ nguyên, người gọi dùng thẳng nguồn) — như desktop trả `y, []`.
     */
    fun apply(input: SampleSource, sink: SampleSink, progress: StepProgress = StepProgress { }): List<ClampRegion>? {
        cut = null
        if (thresholdDb <= 0) return null
        val n = input.size
        val numBlocks = (n / block).toInt()
        val numCells = numBlocks / TRANSIENT_CELL
        if (numCells < 3) return null

        // Lượt 1: năng lượng từng khối 10 ms của dải dưới và dải trên.
        val powerLow = DoubleArray(numBlocks)
        val powerHigh = DoubleArray(numBlocks)
        val usable = numBlocks.toLong() * block
        var i = 0L
        while (i < usable) {
            val j = minOf(usable, i + step)
            val slice = Slice(input, i, j)
            val low = slice.lowBand()
            for (b in 0 until ((j - i) / block).toInt()) {
                var sl = 0.0
                var sh = 0.0
                for (k in b * block until (b + 1) * block) {
                    val lo = low[k]
                    val hi = slice.y(k) - lo
                    sl += lo * lo
                    sh += hi * hi
                }
                val idx = (i / block).toInt() + b
                powerLow[idx] = sl / block
                powerHigh[idx] = sh / block
            }
            i = j
            progress.update(0.5 * i / n)
        }

        // Ghìm dải dưới trước, rồi cả dải trên tín hiệu đã ghìm dải dưới.
        val cutLow = clampLevels(powerLow, numCells, thresholdDb)
        val combined = DoubleArray(numBlocks) { powerLow[it] * 10.0.pow(-cutLow[it] / 10.0) + powerHigh[it] }
        val cutAll = clampLevels(combined, numCells, thresholdDb)
        val total = DoubleArray(numBlocks) { cutAll[it] + cutLow[it] }
        cut = arrayOf(total, cutAll)

        val flagged = BooleanArray(numBlocks) { total[it] > TRANSIENT_REPORT_DB }
        if (flagged.none { it }) return emptyList()
        val regions = ArrayList<ClampRegion>()
        var a = 0
        while (a < numBlocks) {
            if (!flagged[a]) { a++; continue }
            var b = a
            while (b < numBlocks && flagged[b]) b++
            // Giữ đỉnh ±40 ms tạo một đoạn phẳng quanh đỉnh thật: lấy giữa đoạn phẳng.
            var max = Double.NEGATIVE_INFINITY
            for (k in a until b) if (total[k] > max) max = total[k]
            val plateau = (a until b).filter { total[it] >= max - 1e-9 }
            val peak = plateau[plateau.size / 2]
            regions += ClampRegion(
                a.toDouble() * block / sampleRate, b.toDouble() * block / sampleRate, total[peak],
                (peak + 0.5) * block / sampleRate,
            )
            a = b
        }

        // Lượt 2: y = g_high·y + (g_low − g_high)·dải_dưới ở những lát có ghìm.
        val factorLow = DoubleArray(numBlocks) { 10.0.pow(-total[it] / 20.0) }
        val factorHigh = DoubleArray(numBlocks) { 10.0.pow(-cutAll[it] / 20.0) }
        val centers = DoubleArray(numBlocks) { (it + 0.5) * block }
        val out = FloatArray(step)
        var start = 0L
        while (start < n) {
            val end = minOf(n, start + step)
            val from = maxOf(0, (start / block).toInt() - 1)
            val to = minOf(numBlocks, (end / block).toInt() + 2)
            var any = false
            for (k in from until to) if (total[k] > 0) { any = true; break }
            val count = (end - start).toInt()
            if (any) {
                val slice = Slice(input, start, end)
                val low = slice.lowBand()
                var jj = 0
                for (k in 0 until count) {
                    val x = (start + k).toDouble()
                    val gl: Double
                    val gh: Double
                    when {
                        x <= centers[0] -> { gl = factorLow[0]; gh = factorHigh[0] }
                        x >= centers[numBlocks - 1] -> { gl = factorLow[numBlocks - 1]; gh = factorHigh[numBlocks - 1] }
                        else -> {
                            while (centers[jj + 1] <= x) jj++
                            gl = Np.interpAt(x, centers, factorLow, jj)
                            gh = Np.interpAt(x, centers, factorHigh, jj)
                        }
                    }
                    out[k] = (gh * slice.y(k) + (gl - gh) * low[k]).toFloat()
                }
            } else {
                input.read(start, out, 0, count)
            }
            sink.write(out, count)
            start = end
            progress.update(0.5 + 0.5 * start / n)
        }
        return regions
    }

    /** Lát `[i, j)` của nguồn, đọc kèm đệm [TRANSIENT_PAD] mẫu hai bên. */
    private inner class Slice(input: SampleSource, private val i: Long, j: Long) {
        private val a = maxOf(0L, i - TRANSIENT_PAD)
        private val b = minOf(input.size, j + TRANSIENT_PAD)
        private val data: DoubleArray
        private val len = (j - i).toInt()

        init {
            val f = FloatArray((b - a).toInt())
            input.read(a, f, 0, f.size)
            data = DoubleArray(f.size) { f[it].toDouble() }
        }

        fun y(k: Int) = data[(i - a).toInt() + k]

        /** Port của `_low_band`: dải dưới của lát, lọc pha 0 trên lát có đệm. */
        fun lowBand(): DoubleArray {
            val o = (i - a).toInt()
            return IirFilter.sosfiltfilt(sos, data).copyOfRange(o, o + len)
        }
    }

    companion object {
        const val TRANSIENT_BLOCK_MS = 10
        const val TRANSIENT_CELL = 10
        const val TRANSIENT_SPLIT_HZ = 1000.0
        const val TRANSIENT_WINDOW_SEC = 10.0
        const val TRANSIENT_PERCENTILE = 75.0
        const val TRANSIENT_DIGITAL_SILENCE_DB = -100.0
        const val TRANSIENT_BELOW_PEAK_DB = 50.0
        const val TRANSIENT_MAX_CUT_DB = 30.0
        const val TRANSIENT_HOLD_BLOCKS = 9
        const val TRANSIENT_SMOOTH_BLOCKS = 5
        const val TRANSIENT_REPORT_DB = 1.0
        const val TRANSIENT_PAD = 1024

        /**
         * Port của `_rolling_percentile`: phân vị trượt có căn giữa, bỏ NaN (cửa sổ toàn NaN → NaN), nội suy tuyến tính
         * như desktop (công thức riêng của desktop, không phải `_lerp` của numpy).
         */
        fun rollingPercentile(x: DoubleArray, halfWindow: Int, percentile: Double): DoubleArray {
            val w = DoubleArray(2 * halfWindow + 1)
            return DoubleArray(x.size) { c ->
                var n = 0
                for (k in c - halfWindow..c + halfWindow) {
                    if (k in x.indices && !x[k].isNaN()) w[n++] = x[k]
                }
                if (n == 0) return@DoubleArray Double.NaN
                java.util.Arrays.sort(w, 0, n)
                val pos = maxOf(n - 1, 0) * (percentile / 100.0)
                val lower = kotlin.math.floor(pos).toInt()
                val upper = minOf(lower + 1, maxOf(n - 1, 0))
                w[lower] + (pos - lower) * (w[upper] - w[lower])
            }
        }

        /** Port của `_clamp_levels`: số dB cần ghìm cho từng khối 10 ms, đã giữ đỉnh và vuốt mượt. */
        fun clampLevels(power: DoubleArray, numCells: Int, thresholdDb: Double): DoubleArray {
            val size = power.size
            val zero = DoubleArray(size)
            val avg = Np.movingAverage(power, 3)
            val level = DoubleArray(size) { 10.0 * log10(avg[it] + 1e-12) }
            val alive = BooleanArray(size) { level[it] > TRANSIENT_DIGITAL_SILENCE_DB }
            if (alive.none { it }) return zero
            val aliveLevels = level.filterIndexed { i, _ -> alive[i] }.toDoubleArray()
            val loud = Np.percentile(aliveLevels, 99.0) - TRANSIENT_BELOW_PEAK_DB
            val voiced = BooleanArray(size) { alive[it] && level[it] > loud }
            val cellLevel = DoubleArray(numCells) { c ->
                val s = pairwiseSum10(power, c * TRANSIENT_CELL)
                val v = (0 until TRANSIENT_CELL).count { voiced[c * TRANSIENT_CELL + it] }
                if (v.toDouble() / TRANSIENT_CELL < 0.5) Double.NaN else 10.0 * log10(s / TRANSIENT_CELL + 1e-12)
            }
            val halfWindow = (TRANSIENT_WINDOW_SEC * 1000 / (TRANSIENT_CELL * TRANSIENT_BLOCK_MS)).roundToInt()
            var usual = rollingPercentile(cellLevel, halfWindow, TRANSIENT_PERCENTILE)
            if (usual.all { it.isNaN() }) return zero
            // Khoảng lặng dài hơn cả cửa sổ thì không có mức thường; ở đó cũng không có gì để ghìm.
            val median = Np.nanMedian(usual)
            usual = DoubleArray(numCells) { if (usual[it].isNaN()) median else usual[it] }
            val cellCenters = DoubleArray(numCells) { it * TRANSIENT_CELL + (TRANSIENT_CELL - 1) / 2.0 }
            val g = DoubleArray(size) { k ->
                if (!voiced[k]) {
                    0.0
                } else {
                    val ceiling = Np.interp(k.toDouble(), cellCenters, usual) + thresholdDb
                    (level[k] - ceiling).coerceIn(0.0, TRANSIENT_MAX_CUT_DB)
                }
            }
            if (g.none { it > 0 }) return zero
            return Np.uniformFilter1d(Np.maximumFilter1d(g, TRANSIENT_HOLD_BLOCKS), TRANSIENT_SMOOTH_BLOCKS)
        }

        /**
         * Tổng 10 phần tử liền nhau như `mean(axis=1)` của numpy (`pairwise_sum` với n = 10: 8 bộ cộng rồi ghép cặp, cộng
         * nốt 2 phần tử cuối).
         */
        private fun pairwiseSum10(a: DoubleArray, o: Int): Double {
            var res = ((a[o] + a[o + 1]) + (a[o + 2] + a[o + 3])) + ((a[o + 4] + a[o + 5]) + (a[o + 6] + a[o + 7]))
            res += a[o + 8]
            res += a[o + 9]
            return res
        }

        /**
         * Port của `_merge_regions`: gộp các vùng ghìm của nhiều lượt (chỉ để báo cáo). Vùng chồng nhau thì gộp; mức
         * ghìm sâu nhất = tổng mức sâu nhất của từng lượt.
         */
        fun mergeRegions(vararg passes: List<ClampRegion>): List<ClampRegion> {
            data class Item(val r: ClampRegion, val pass: Int)
            val items = passes.withIndex().flatMap { (k, list) -> list.map { Item(it, k) } }
                .sortedWith(compareBy<Item>({ it.r.start }, { it.r.end }, { it.r.depthDb }, { it.r.peakAt }, { it.pass }))
            class M(var a: Double, var b: Double, val depth: MutableMap<Int, Double>, var g: Double, var at: Double)
            val merged = ArrayList<M>()
            for ((r, pass) in items) {
                val last = merged.lastOrNull()
                if (last != null && r.start <= last.b) {
                    last.b = maxOf(last.b, r.end)
                    last.depth[pass] = maxOf(last.depth[pass] ?: 0.0, r.depthDb)
                    if (r.depthDb > last.g) {
                        last.g = r.depthDb
                        last.at = r.peakAt
                    }
                } else {
                    merged += M(r.start, r.end, mutableMapOf(pass to r.depthDb), r.depthDb, r.peakAt)
                }
            }
            return merged.map { ClampRegion(it.a, it.b, it.depth.values.sum(), it.at) }
        }

        /** Port của `_format_timestamp`: `m:ss`, từ 1 giờ thì `h:mm:ss`. */
        fun formatTimestamp(sec: Double): String {
            val s = sec.toInt()
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}

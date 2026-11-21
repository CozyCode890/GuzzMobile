package io.github.cozycode890.guzz.core.audio

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Đo tốc độ [SincResampler] so với bản spike Stage 1 trên PC (bước B7). Chỉ chạy khi đặt `GUZZ_BENCH=1`:
 *
 *     $env:GUZZ_BENCH = '1'; .\gradlew :core:audio:test --tests "*SincResamplerBenchmark" --rerun -i
 */
@EnabledIfEnvironmentVariable(named = "GUZZ_BENCH", matches = "1")
class SincResamplerBenchmark {

    /** Một khối tín hiệu dựng sẵn, nạp lặp lại: thời gian đo chỉ còn là của resampler. */
    private val block = Random(1).let { r ->
        FloatArray(1 shl 16) { (0.3 * sin(2 * PI * 440 * it / 48_000)).toFloat() + (r.nextFloat() - 0.5f) * 0.01f }
    }

    private fun feed(seconds: Int, rate: Int, consume: (FloatArray, Int) -> Unit) {
        var n = 0L
        val total = seconds.toLong() * rate
        while (n < total) {
            val k = minOf(block.size.toLong(), total - n).toInt()
            consume(block, k)
            n += k
        }
    }

    @Test
    fun `2 giờ mono 48 kHz sang 16 kHz`() {
        val seconds = 2 * 3600
        // Làm nóng JIT.
        run {
            val r = SincResampler(48_000, 16_000)
            val out = FloatArray(r.maxOutput(1 shl 16))
            feed(60, 48_000) { b, k -> r.process(b, 0, k, out) }
        }
        val r = SincResampler(48_000, 16_000)
        val out = FloatArray(r.maxOutput(1 shl 16))
        var produced = 0L
        val t0 = System.nanoTime()
        feed(seconds, 48_000) { b, k -> produced += r.process(b, 0, k, out) }
        produced += r.flush(FloatArray(r.maxFlushOutput()))
        val newSec = (System.nanoTime() - t0) / 1e9

        // Bản spike: đo 20 phút rồi nhân 6.
        val spikeMinutes = 20
        val s = SpikeResampler(48_000, 16_000)
        feed(30, 48_000) { b, k -> s.process(b, k) }
        val s2 = SpikeResampler(48_000, 16_000)
        val t1 = System.nanoTime()
        feed(spikeMinutes * 60, 48_000) { b, k -> s2.process(b, k) }
        val spikeSec = (System.nanoTime() - t1) / 1e9 * (seconds / (spikeMinutes * 60.0))

        println(
            "SincResampler: 2 giờ 48k→16k mất %.1f s (×%.0f thời gian thực), ra %d mẫu; bản spike ước %.1f s; nhanh hơn %.1f lần"
                .format(newSec, seconds / newSec, produced, spikeSec, spikeSec / newSec),
        )
    }

    /** Chép nguyên bản spike Stage 1 (một tổng, cấp phát mảng ra mỗi khối) để so tốc độ. */
    private class SpikeResampler(val inRate: Int, val outRate: Int) {
        private val zeroCrossings = 32
        private val rolloff = 0.94
        private val beta = 8.6
        private val i0Beta = bessel0(beta)
        private val up: Int
        private val down: Int
        private val half: Int
        private val taps: Array<FloatArray>
        private var buf = FloatArray(1 shl 16)
        private var bufLen = 0
        private var bufStart = 0L
        private var produced = 0L

        init {
            val g = gcd(inRate, outRate)
            up = outRate / g
            down = inRate / g
            val rho = min(1.0, outRate.toDouble() / inRate) * rolloff
            val halfWidth = zeroCrossings / rho
            half = ceil(halfWidth).toInt()
            taps = Array(up) { p ->
                val frac = p.toDouble() / up
                val row = DoubleArray(2 * half) { m ->
                    val t = frac + (half - 1) - m
                    val x = t / halfWidth
                    if (x <= -1.0 || x >= 1.0) 0.0 else rho * sinc(rho * t) * kaiser(x)
                }
                val s = row.sum()
                FloatArray(row.size) { (row[it] / s).toFloat() }
            }
            bufLen = half - 1
            bufStart = -(half - 1).toLong()
        }

        fun process(input: FloatArray, n: Int): FloatArray {
            if (bufLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + n))
            input.copyInto(buf, bufLen, 0, n)
            bufLen += n
            val out = FloatArray(maxOf(0, ((bufStart + bufLen) * up / down - produced + 2).toInt()))
            var k = 0
            while (true) {
                val pos = produced * down
                val i = pos / up
                val p = (pos % up).toInt()
                if (i + half > bufStart + bufLen - 1) break
                val row = taps[p]
                val base = (i - half + 1 - bufStart).toInt()
                var acc = 0f
                for (m in row.indices) acc += row[m] * buf[base + m]
                if (k == out.size) break
                out[k++] = acc
                produced++
            }
            val drop = ((produced * down) / up - half + 1 - bufStart).toInt()
            if (drop > 0 && drop >= buf.size / 2) {
                buf.copyInto(buf, 0, drop, bufLen)
                bufLen -= drop
                bufStart += drop
            }
            return if (k == out.size) out else out.copyOf(k)
        }

        private fun kaiser(x: Double): Double = bessel0(beta * sqrt(1 - x * x)) / i0Beta

        private companion object {
            fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
            fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            fun bessel0(x: Double): Double {
                var sum = 1.0
                var term = 1.0
                val q = x * x / 4
                var k = 1
                while (term > 1e-12 * sum) {
                    term *= q / (k.toDouble() * k)
                    sum += term
                    k++
                }
                return sum
            }
        }
    }
}

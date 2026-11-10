package io.github.cozycode890.guzz.core.audio

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SincResamplerTest {

    private fun sine(freq: Double, rate: Int, seconds: Double, amp: Double = 0.5) =
        FloatArray((seconds * rate).toInt()) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun resampleAll(input: FloatArray, inRate: Int, outRate: Int = 16_000): FloatArray {
        val r = SincResampler(inRate, outRate)
        return r.process(input) + r.flush()
    }

    /** Biên độ và pha của thành phần sin [freq] trong `y[from until to]`, bình phương tối thiểu trên sin/cos. */
    private fun fit(y: FloatArray, rate: Int, freq: Double, from: Int, to: Int): Pair<Double, Double> {
        var ss = 0.0
        var sc = 0.0
        var cc = 0.0
        var ys = 0.0
        var yc = 0.0
        for (n in from until to) {
            val s = sin(2 * PI * freq * n / rate)
            val c = cos(2 * PI * freq * n / rate)
            ss += s * s; sc += s * c; cc += c * c
            ys += y[n] * s; yc += y[n] * c
        }
        val det = ss * cc - sc * sc
        val a = (ys * cc - yc * sc) / det
        val b = (yc * ss - ys * sc) / det
        return sqrt(a * a + b * b) to atan2(b, a)
    }

    private fun rms(y: FloatArray, from: Int, to: Int): Double =
        sqrt((from until to).sumOf { y[it].toDouble() * y[it] } / (to - from))

    @Test
    fun `sin 1 kHz từ 48 kHz giữ đúng biên độ và pha`() {
        val out = resampleAll(sine(1000.0, 48_000, 2.0), 48_000)
        val (amp, phase) = fit(out, 16_000, 1000.0, 1_600, out.size - 1_600)
        val db = 20 * log10(amp / 0.5)
        assertTrue(abs(db) <= 0.01, "biên độ lệch $db dB")
        assertTrue(abs(phase) <= 1e-3, "pha lệch $phase rad (độ trễ chưa bù)")
    }

    @ParameterizedTest(name = "{0} Hz")
    @ValueSource(ints = [10_000, 15_000])
    fun `tần số trên Nyquist 8 kHz bị chặn tới dưới -80 dB`(freq: Int) {
        val out = resampleAll(sine(freq.toDouble(), 48_000, 1.0), 48_000)
        val level = 20 * log10(rms(out, 1_600, out.size - 1_600) / (0.5 / sqrt(2.0)))
        assertTrue(level <= -80.0, "$freq Hz còn $level dB")
    }

    @ParameterizedTest(name = "{0} Hz")
    @ValueSource(ints = [8_000, 11_025, 16_000, 22_050, 32_000, 44_100, 48_000, 96_000])
    fun `mọi tần số vào ra đúng số mẫu và đúng biên độ`(rate: Int) {
        val input = sine(1000.0, rate, 1.5)
        val out = resampleAll(input, rate)
        val expected = (input.size.toLong() * 16_000 + rate - 1) / rate
        assertTrue(abs(out.size - expected) <= 1, "$rate: ra ${out.size} mẫu, cần $expected")
        val (amp, _) = fit(out, 16_000, 1000.0, 1_600, out.size - 1_600)
        assertTrue(abs(20 * log10(amp / 0.5)) <= 0.01, "$rate: biên độ $amp")
    }

    @Test
    fun `nạp theo khối bất kỳ cho cùng kết quả với nạp một lần`() {
        val r = Random(3)
        val input = FloatArray(100_000) { r.nextFloat() * 2 - 1 }
        for (rate in listOf(44_100, 48_000)) {
            val once = resampleAll(input, rate)
            val res = SincResampler(rate, 16_000)
            val out = ArrayList<Float>()
            var i = 0
            while (i < input.size) {
                val n = minOf(input.size - i, 1 + r.nextInt(5_000))
                val buf = FloatArray(res.maxOutput(n))
                val k = res.process(input, i, n, buf)
                for (j in 0 until k) out += buf[j]
                i += n
            }
            out += res.flush().toList()
            assertContentEquals(once.toList(), out, "$rate")
        }
    }

    @Test
    fun `tần số lạ không dựng bảng vẫn đúng`() {
        val res = SincResampler(47_999, 16_000)
        assertFalse(res.usesPhaseTable)
        val input = sine(1000.0, 47_999, 0.4)
        val out = res.process(input) + res.flush()
        val (amp, phase) = fit(out, 16_000, 1000.0, 1_600, out.size - 1_600)
        assertTrue(abs(20 * log10(amp / 0.5)) <= 0.01, "biên độ $amp")
        assertTrue(abs(phase) <= 1e-3, "pha $phase")
    }

    @Test
    fun `cùng tần số thì chép nguyên`() {
        val input = sine(440.0, 16_000, 0.1)
        val res = SincResampler(16_000, 16_000)
        assertTrue(res.isPassthrough)
        assertContentEquals(input, res.process(input))
        assertEquals(0, res.flush().size)
    }
}

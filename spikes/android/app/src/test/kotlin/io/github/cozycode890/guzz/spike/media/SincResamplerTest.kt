package io.github.cozycode890.guzz.spike.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SincResamplerTest {
    private fun tone(rate: Int, freq: Double, seconds: Double, amp: Double = 0.5) =
        FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    /** Chạy theo khối lẻ cỡ để thử cả đường ghép khối. */
    private fun resample(r: SincResampler, x: FloatArray, block: Int = 4093): FloatArray {
        val parts = ArrayList<FloatArray>()
        var i = 0
        while (i < x.size) {
            val n = minOf(block, x.size - i)
            parts.add(r.process(x.copyOfRange(i, i + n)))
            i += n
        }
        parts.add(r.flush())
        return parts.flatMap { it.asList() }.toFloatArray()
    }

    private fun assertSameTone(inRate: Int) {
        val x = tone(inRate, 1000.0, 1.0)
        val y = resample(SincResampler(inRate, 16000), x)
        assertEquals("số mẫu ra", (x.size.toLong() * 16000 + inRate - 1) / inRate, y.size.toLong())
        // Không có độ trễ: mẫu n ứng với thời điểm n / 16000. Bỏ hai đầu, nơi lọc thấy số 0 đệm.
        var maxErr = 0.0
        for (n in 300 until y.size - 300) {
            val want = 0.5 * sin(2 * PI * 1000.0 * n / 16000)
            maxErr = maxOf(maxErr, abs(y[n] - want))
        }
        assertTrue("sai số lớn nhất $maxErr", maxErr < 1e-3)
    }

    @Test
    fun sine1kHzFrom48k() = assertSameTone(48000)

    @Test
    fun sine1kHzFrom44k1() = assertSameTone(44100)

    @Test
    fun aboveNyquistIsRemoved() {
        for (f in listOf(10000.0, 15000.0)) {
            val y = resample(SincResampler(48000, 16000), tone(48000, f, 1.0))
            val mid = y.copyOfRange(300, y.size - 300)
            val rms = sqrt(mid.sumOf { (it * it).toDouble() } / mid.size)
            // Biên độ 0,5 → RMS 0,354; đòi giảm ≥ 60 dB.
            assertTrue("$f Hz còn RMS $rms", rms < 0.354e-3)
        }
    }

    @Test
    fun sameRateIsIdentity() {
        val x = tone(16000, 440.0, 0.5)
        val y = resample(SincResampler(16000, 16000), x)
        assertEquals(x.size, y.size)
        for (i in x.indices) assertEquals(x[i], y[i], 1e-6f)
    }
}

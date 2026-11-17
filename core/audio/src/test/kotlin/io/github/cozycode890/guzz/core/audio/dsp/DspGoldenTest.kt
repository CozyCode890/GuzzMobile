package io.github.cozycode890.guzz.core.audio.dsp

import io.github.cozycode890.guzz.core.common.testing.Golden
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden B1–B4 của Stage 7: FFT, Butterworth, sosfilt / sosfiltfilt / filtfilt bậc một, STFT / ISTFT, chập "same" so
 * với numpy / scipy / noisereduce của desktop (`testdata/golden/dsp/dsp.json`, do `tools/golden/gen_dsp.py` ghi).
 */
class DspGoldenTest {

    private val g: JsonObject = Golden.readJson("dsp/dsp.json")

    private fun JsonElement.obj(key: String) = jsonObject.getValue(key)
    private fun JsonElement.f64(key: String): DoubleArray = decode(obj(key).jsonPrimitive.content)
    private fun JsonElement.int(key: String) = obj(key).jsonPrimitive.int

    private fun decode(b64: String): DoubleArray {
        val buf = ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer()
        return DoubleArray(buf.remaining()).also { buf.get(it) }
    }

    private fun sosOf(e: JsonElement): Array<DoubleArray> =
        e.jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray() }.toTypedArray()

    /** Sai lệch lớn nhất chia cho biên độ lớn nhất của kết quả mong đợi. */
    private fun assertClose(expected: DoubleArray, actual: DoubleArray, relTol: Double, what: String) {
        assertEquals(expected.size, actual.size, "$what: độ dài")
        var scale = 1e-300
        var err = 0.0
        for (i in expected.indices) {
            scale = max(scale, abs(expected[i]))
            err = max(err, abs(expected[i] - actual[i]))
        }
        assertTrue(err <= relTol * scale, "$what: lệch ${err / scale} (tương đối) > $relTol")
    }

    @Test
    fun `B1 rfft và irfft trùng numpy`() {
        for (c in g.obj("fft").obj("forward").jsonArray) {
            val n = c.int("n")
            val x = c.f64("x")
            val fft = RealFft(n)
            val re = DoubleArray(n / 2 + 1)
            val im = DoubleArray(n / 2 + 1)
            fft.forward(x, 0, re, im)
            val y = c.obj("y")
            assertClose(y.f64("re"), re, 1e-12, "rfft re n=$n")
            assertClose(y.f64("im"), im, 1e-12, "rfft im n=$n")
        }
        for (c in g.obj("fft").obj("inverse").jsonArray) {
            val n = c.int("n")
            val x = c.obj("x")
            val out = DoubleArray(n)
            RealFft(n).inverse(x.f64("re"), x.f64("im"), 0, out)
            assertClose(c.f64("y"), out, 1e-12, "irfft n=$n")
        }
    }

    @Test
    fun `B2 hệ số butter trùng scipy`() {
        for (c in g.obj("butter").jsonArray) {
            val order = c.int("order")
            val wn = c.obj("wn").jsonArray.map { it.jsonPrimitive.double }
            val type = c.obj("btype").jsonPrimitive.content
            val sos = if (type == "band") Butterworth.bandpass(order, wn[0], wn[1]) else Butterworth.lowpass(order, wn[0])
            val expected = sosOf(c.obj("sos"))
            assertEquals(expected.size, sos.size, "$type $order $wn: số tầng")
            for (s in expected.indices) for (j in 0 until 6) {
                val e = expected[s][j]
                assertTrue(
                    abs(e - sos[s][j]) <= 1e-10 * max(1.0, abs(e)),
                    "$type $order $wn: sos[$s][$j] = ${sos[s][j]}, scipy $e",
                )
            }
        }
    }

    @Test
    fun `B3 sosfilt theo khối có zi, sosfiltfilt, filtfilt bậc một trùng scipy`() {
        val f = g.obj("filters")
        val sf = f.obj("sosfilt")
        val sos = sosOf(sf.obj("sos"))
        val x = sf.f64("x")
        // Cả bài một lượt (float64).
        val y64 = x.copyOf()
        IirFilter.sosfilt(sos, y64, Array(sos.size) { DoubleArray(2) })
        assertClose(sf.f64("y64"), y64, 1e-9, "sosfilt")
        // Theo khối có zi, kết quả gán về float32 như bandpass_filter.
        val block = sf.int("block")
        val zi = Array(sos.size) { DoubleArray(2) }
        val y = x.copyOf()
        var i = 0
        while (i < y.size) {
            val j = minOf(y.size, i + block)
            IirFilter.sosfilt(sos, y, zi, i, j)
            for (k in i until j) y[k] = y[k].toFloat().toDouble()
            i = j
        }
        val expected = sf.f64("y")
        for (k in y.indices) {
            assertTrue(abs(y[k] - expected[k]) <= Math.ulp(expected[k].toFloat()), "sosfilt theo khối, mẫu $k")
        }

        val ff = f.obj("sosfiltfilt")
        val low = sosOf(ff.obj("sos"))
        assertEquals(15, IirFilter.sosfiltfiltPadlen(low))
        for ((n, c) in ff.obj("cases").jsonArray.withIndex()) {
            assertClose(c.f64("y"), IirFilter.sosfiltfilt(low, c.f64("x")), 1e-9, "sosfiltfilt ca $n")
        }
        assertTrue(ff.obj("short_15_raises").jsonPrimitive.boolean)
        assertFailsWith<IllegalArgumentException> { IirFilter.sosfiltfilt(low, DoubleArray(15)) }

        val op = f.obj("one_pole")
        val b = op.obj("b").jsonPrimitive.double
        assertClose(
            op.obj("lfilter_zi").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray(),
            IirFilter.lfilterZi(doubleArrayOf(b), doubleArrayOf(1.0, b - 1)), 1e-15, "lfilter_zi",
        )
        val rows = op.int("rows")
        val cols = op.int("cols")
        // Lưu theo cột (khung) như bản chạy thật: phần tử (hàng f, cột t) ở t * rows + f.
        val src = op.f64("x")
        val byFrame = DoubleArray(rows * cols) { val t = it / rows; val r = it % rows; src[r * cols + t] }
        for (r in 0 until rows) IirFilter.onePoleFiltfilt(b, byFrame, r, rows, cols)
        val back = DoubleArray(rows * cols) { val r = it / cols; val t = it % cols; byFrame[t * rows + r] }
        assertClose(op.f64("y"), back, 1e-12, "filtfilt bậc một")
    }

    @Test
    fun `B4 stft và istft trùng scipy, kể cả khối 660 000 mẫu`() {
        val stft = Stft()
        val st = g.obj("stft")
        for ((n, c) in st.obj("short").jsonArray.withIndex()) {
            val x = c.f64("x")
            val frames = c.int("frames")
            assertEquals(frames, stft.frames(x.size))
            val re = DoubleArray(frames * stft.bins)
            val im = DoubleArray(frames * stft.bins)
            stft.forward(x, 0, x.size, re, im)
            assertClose(c.obj("z").f64("re"), re, 1e-12, "stft re ca $n")
            assertClose(c.obj("z").f64("im"), im, 1e-12, "stft im ca $n")
            val mask = c.f64("mask")
            for (k in re.indices) {
                re[k] *= mask[k]
                im[k] *= mask[k]
            }
            val len = stft.istftLength(frames)
            val out = DoubleArray(len)
            val work = DoubleArray(len + stft.nperseg)
            stft.inverse(re, im, frames, out, 0, work, DoubleArray(work.size))
            assertClose(c.f64("back"), out, 1e-12, "istft ca $n")
        }

        val lo = st.obj("long")
        val n = lo.int("n")
        val x = DoubleArray(n) { longSignal(it) }
        val frames = lo.int("frames")
        assertEquals(frames, stft.frames(n))
        val re = DoubleArray(frames * stft.bins)
        val im = DoubleArray(frames * stft.bins)
        stft.forward(x, 0, n, re, im)
        val picked = lo.obj("picked").jsonArray.map { it.jsonPrimitive.int }
        for ((k, t) in picked.withIndex()) {
            val z = lo.obj("z").jsonArray[k]
            assertClose(z.f64("re"), re.copyOfRange(t * stft.bins, (t + 1) * stft.bins), 1e-11, "stft dài, khung $t (re)")
            assertClose(z.f64("im"), im.copyOfRange(t * stft.bins, (t + 1) * stft.bins), 1e-11, "stft dài, khung $t (im)")
        }
        val len = stft.istftLength(frames)
        assertEquals(lo.int("back_len"), len)
        val out = DoubleArray(len)
        val work = DoubleArray(len + stft.nperseg)
        stft.inverse(re, im, frames, out, 0, work, DoubleArray(work.size))
        for ((k, r) in lo.obj("back_ranges").jsonArray.withIndex()) {
            val a = r.jsonArray[0].jsonPrimitive.int
            val b = r.jsonArray[1].jsonPrimitive.int
            assertClose(decode(lo.obj("back").jsonArray[k].jsonPrimitive.content), out.copyOfRange(a, b), 1e-11, "istft dài [$a, $b)")
        }
        assertEquals("complex128", lo.obj("dtype_stft").jsonPrimitive.content)
        assertEquals("float64", lo.obj("dtype_istft").jsonPrimitive.content)
    }

    @Test
    fun `B4 chập same như fftconvolve, bản tách trục bằng bản 2 chiều`() {
        val c = g.obj("convolve")
        val (kr, kc) = c.obj("kernel_shape").jsonArray.map { it.jsonPrimitive.int }
        val (ar, ac) = c.obj("a_shape").jsonArray.map { it.jsonPrimitive.int }
        assertEquals(33 to 7, kr to kc)
        val kernel = c.f64("kernel")
        val a = c.f64("a")
        val y = Convolve.same2d(a, ar, ac, kernel, kr, kc)
        assertClose(c.f64("y"), y, 1e-12, "fftconvolve same")
        // Nhân là tích ngoài: tổng theo hàng / theo cột là hai vectơ của nó, chập lần lượt theo từng trục cho cùng kết
        // quả (cách NoiseReducer làm). Vectơ của NoiseReducer phải trùng hai vectơ này.
        val kf = DoubleArray(kr) { p -> (0 until kc).sumOf { kernel[p * kc + it] } }
        val kt = DoubleArray(kc) { q -> (0 until kr).sumOf { kernel[it * kc + q] } }
        assertClose(kf, NoiseReducer.smoothingVector(c.int("n_grad_freq")), 1e-14, "vectơ tần số")
        assertClose(kt, NoiseReducer.smoothingVector(c.int("n_grad_time")), 1e-14, "vectơ thời gian")
        val sep = a.copyOf()
        val tmp = DoubleArray(max(ar, ac))
        for (i in 0 until ar) Convolve.same1d(sep, i * ac, 1, ac, kt, tmp)
        for (j in 0 until ac) Convolve.same1d(sep, j, ac, ar, kf, tmp)
        assertClose(c.f64("y"), sep, 1e-12, "chập tách trục")
    }

    companion object {
        /** Cùng công thức với `long_signal` của gen_dsp.py. */
        fun longSignal(i: Int): Double {
            val t = i.toDouble()
            return 0.3 * sin(0.01 * t) + 0.2 * sin(0.37 * t + 0.5 * sin(0.001 * t)) + 0.05 * sin(2.9 * t)
        }
    }
}

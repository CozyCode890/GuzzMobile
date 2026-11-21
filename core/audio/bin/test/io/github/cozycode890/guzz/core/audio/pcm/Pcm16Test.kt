package io.github.cozycode890.guzz.core.audio.pcm

import io.github.cozycode890.guzz.core.common.testing.Golden
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Pcm16Test {

    @TempDir
    lateinit var dir: File

    private fun randomShorts(n: Int, seed: Int = 1) =
        Random(seed).let { r -> ShortArray(n) { r.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE + 1).toShort() } }

    @Test
    fun `ghi rồi đọc lại trùng từng mẫu, vắt qua ranh giới khối`() {
        // Lớn hơn bộ đệm 32 Ki mẫu của writer, ghi thành nhiều lần với cỡ lẻ.
        val data = randomShorts(100_003)
        val file = File(dir, "a.pcm")
        Pcm16Writer(file).use { w ->
            var i = 0
            for (step in listOf(1, 7, 40_000, 33_333, 26_662)) {
                w.write(data, i, step)
                i += step
            }
            assertEquals(data.size, i)
            assertEquals(data.size.toLong(), w.samplesWritten)
        }
        assertTrue(file.isFile)
        assertFalse(File(file.path + ".part").exists())

        Pcm16File(file).use { pcm ->
            assertEquals(data.size.toLong(), pcm.size)
            val shorts = ShortArray(data.size)
            pcm.readShorts(0, shorts, count = data.size)
            assertContentEquals(data, shorts)

            // Đọc float theo các khoảng lệch nhau, so với int16 / 32768.
            val floats = FloatArray(70_001)
            pcm.read(29_999, floats, 0, floats.size)
            for (k in floats.indices) assertEquals(data[29_999 + k] / 32768f, floats[k])
        }
    }

    @Test
    fun `forEachBlock đi hết nguồn đúng thứ tự`() {
        val data = FloatArray(10_007) { it.toFloat() }
        val seen = ArrayList<Float>()
        FloatArraySource(data).forEachBlock(1_000) { buf, n, start ->
            assertEquals(seen.size.toLong(), start)
            for (i in 0 until n) seen += buf[i]
        }
        assertContentEquals(data.toList(), seen)
    }

    @Test
    fun `giá trị float trùng Golden_readWav của Stage 0`() {
        val wav = Golden.readWav("wav/known_samples.wav")
        val shorts = ShortArray(wav.samples.size) { Math.rint(wav.samples[it] * 32768.0).toInt().toShort() }
        val file = File(dir, "k.pcm")
        Pcm16Writer(file).use { it.write(shorts) }
        Pcm16File(file).use { pcm ->
            val got = FloatArray(wav.samples.size)
            pcm.read(0, got, 0, got.size)
            assertContentEquals(wav.samples, got)
        }
    }

    @Test
    fun `abort xoá tệp part, không tạo tệp thật`() {
        val file = File(dir, "b.pcm")
        val w = Pcm16Writer(file)
        w.write(shortArrayOf(1, 2, 3))
        w.abort()
        assertFalse(file.exists())
        assertFalse(w.partFile.exists())
    }

    @Test
    fun `đổi float sang int16 kiểu desktop và kiểu ffmpeg`() {
        // Kiểu desktop (_to_pcm16): kẹp, × 32767, cắt về 0.
        assertEquals(32767, Pcm16.fromFloatDesktop(1.5f).toInt())
        assertEquals(-32767, Pcm16.fromFloatDesktop(-2f).toInt())
        assertEquals(0, Pcm16.fromFloatDesktop(0.00003f).toInt())
        assertEquals(-1, Pcm16.fromFloatDesktop(-0.00004f).toInt())
        // Kiểu ffmpeg: × 32768, làm tròn nửa về chẵn, kẹp.
        assertEquals(32767, Pcm16.fromFloatRounded(1f).toInt())
        assertEquals(-32768, Pcm16.fromFloatRounded(-1f).toInt())
        assertEquals(2, Pcm16.fromFloatRounded(2.5f / 32768f).toInt())
        assertEquals(4, Pcm16.fromFloatRounded(3.5f / 32768f).toInt())
    }

    @Test
    fun `GainSource nhân float32 rồi kẹp`() {
        val src = GainSource(FloatArraySource(floatArrayOf(0.1f, -0.6f, 0.6f, 0f)), 2f)
        val out = FloatArray(4)
        src.read(0, out, 0, 4)
        assertContentEquals(floatArrayOf(0.1f * 2f, -1f, 1f, 0f), out)
    }
}

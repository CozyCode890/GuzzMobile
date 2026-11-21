package io.github.cozycode890.guzz.core.audio

import io.github.cozycode890.guzz.core.audio.dsp.NoiseReducer
import io.github.cozycode890.guzz.core.audio.pcm.FloatArraySource
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.audio.pcm.SampleSource
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
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden C1–C5 của Stage 7: từng bước làm sạch và cả chuỗi `clean_audio` so với chính hàm của desktop
 * (`testdata/golden/cleaning/`, do `tools/golden/gen_cleaning.py` ghi). Mỗi bước chạy trên kết quả Python của bước
 * trước, nên lệch ở bước nào lộ ra ở đúng bước đó. Ngưỡng: SNR ≥ 60 dB (D85).
 */
class CleaningGoldenTest {

    @TempDir
    lateinit var dir: File

    private val g: JsonObject = Golden.readJson("cleaning/cleaning.json")
    private val sr = g.int("sr")
    private val input: FloatArray = Golden.readWav("cleaning/${g.str("wav")}").samples

    private fun JsonElement.obj(key: String) = jsonObject.getValue(key)
    private fun JsonElement.int(key: String) = obj(key).jsonPrimitive.int
    private fun JsonElement.dbl(key: String) = obj(key).jsonPrimitive.double
    private fun JsonElement.str(key: String) = obj(key).jsonPrimitive.content
    private fun JsonElement.doubles(key: String) = obj(key).jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()

    private fun f32(name: String): FloatArray {
        val buf = ByteBuffer.wrap(Golden.file("cleaning/$name").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }

    /** Gom kết quả một bước vào mảng. */
    private class Collect : SampleSink {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set

        override fun write(buf: FloatArray, count: Int) {
            if (size + count > data.size) data = data.copyOf(maxOf(data.size * 2, size + count))
            buf.copyInto(data, size, 0, count)
            size += count
        }

        fun result() = data.copyOf(size)
    }

    private fun assertSnr(expected: FloatArray, actual: FloatArray, minDb: Double, what: String): Double {
        assertEquals(expected.size, actual.size, "$what: số mẫu")
        var sig = 0.0
        var err = 0.0
        for (i in expected.indices) {
            sig += expected[i].toDouble() * expected[i]
            val d = expected[i].toDouble() - actual[i]
            err += d * d
        }
        val snr = if (err == 0.0) Double.POSITIVE_INFINITY else 10 * log10(sig / err)
        println("$what: SNR $snr dB")
        assertTrue(snr >= minDb, "$what: SNR $snr dB < $minDb dB")
        return snr
    }

    @Test
    fun `C1 bandpass_filter`() {
        val b = g.obj("bandpass")
        val out = Collect()
        assertTrue(Bandpass.filter(FloatArraySource(input), sr, b.int("low"), b.int("high"), out))
        assertSnr(f32(b.str("file")), out.result(), 60.0, "bandpass")
        // Các nhánh bỏ qua của desktop: low <= 0, low >= high, high trên Nyquist thì kẹp về Nyquist − 1.
        assertFalse(Bandpass.applies(sr, 0, 7000))
        assertFalse(Bandpass.applies(sr, 7000, 7000))
        assertFalse(Bandpass.applies(sr, 8000, 9000))
        assertTrue(Bandpass.applies(sr, 7000, 9000))
    }

    @Test
    fun `C3 suppress_transients mức ghìm, vùng ghìm và tín hiệu ra`() {
        val t = g.obj("transient")
        val src = f32(g.obj("bandpass").str("file"))
        val suppressor = TransientSuppressor(sr, t.dbl("threshold_db"))
        val out = Collect()
        val regions = suppressor.apply(FloatArraySource(src), out)!!
        val cut = suppressor.cut!!
        for ((k, name) in listOf(0 to "cut_low", 1 to "cut_all")) {
            val exp = t.doubles(name)
            // cut_low của desktop chỉ là dải dưới; tổng dải dưới = cut_all + cut_low.
            val act = if (k == 0) DoubleArray(exp.size) { cut[0][it] - cut[1][it] } else cut[1]
            assertEquals(exp.size, act.size)
            for (i in exp.indices) assertTrue(abs(exp[i] - act[i]) <= 1e-6, "$name[$i]: ${act[i]} ≠ ${exp[i]}")
        }
        val expRegions = t.obj("regions").jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.double } }
        assertEquals(expRegions.size, regions.size, "số vùng ghìm")
        for ((e, a) in expRegions.zip(regions)) {
            assertEquals(e[0], a.start, 1e-9)
            assertEquals(e[1], a.end, 1e-9)
            assertEquals(e[2], a.depthDb, 1e-6)
            assertEquals(e[3], a.peakAt, 1e-9)
        }
        val merged = TransientSuppressor.mergeRegions(regions, regions.take(1))
        val expMerged = t.obj("merged").jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.double } }
        assertEquals(expMerged.size, merged.size)
        for ((e, a) in expMerged.zip(merged)) assertEquals(e[2], a.depthDb, 1e-6)
        assertSnr(f32(t.str("file")), out.result(), 60.0, "suppress_transients")
    }

    @Test
    fun `C3 không có gì để ghìm thì không ghi gì, ngưỡng 0 thì tắt`() {
        val quiet = FloatArray(sr * 5) { (Math.sin(it * 0.05) * 0.1).toFloat() }
        val out = Collect()
        assertEquals(emptyList(), TransientSuppressor(sr, 15.0).apply(FloatArraySource(quiet), out))
        assertEquals(0, out.size)
        assertEquals(null, TransientSuppressor(sr, 0.0).apply(FloatArraySource(quiet), out))
        // Dưới 3 ô 100 ms: desktop trả nguyên.
        assertEquals(null, TransientSuppressor(sr, 15.0).apply(FloatArraySource(FloatArray(sr / 4)), out))
        assertEquals("1:05", TransientSuppressor.formatTimestamp(65.9))
        assertEquals("1:00:05", TransientSuppressor.formatTimestamp(3605.0))
    }

    @Test
    fun `C2 reduce_noise nhiều khối`() {
        val d = g.obj("denoise")
        val src = f32(g.obj("transient").str("file"))
        val reducer = NoiseReducer(
            sr, maxOf(0.2, d.dbl("time_constant_s")), d.dbl("prop_decrease"),
            chunkSize = d.int("chunk_size"), padding = d.int("padding"),
        )
        val out = Collect()
        reducer.process(FloatArraySource(src), { buf, n -> out.write(buf, n) })
        assertSnr(f32(d.str("file")), out.result(), 60.0, "reduce_noise")
    }

    @Test
    fun `C4 level_loudness`() {
        val l = g.obj("level")
        val src = f32(g.obj("denoise").str("file"))
        val out = Collect()
        assertTrue(LevelLoudness.apply(FloatArraySource(src), sr, l.dbl("window_sec"), out))
        assertSnr(f32(l.str("file")), out.result(), 60.0, "level_loudness")
        // Nhánh bỏ qua: cửa sổ 0, dưới 4 khối 50 ms.
        assertFalse(LevelLoudness.apply(FloatArraySource(src), sr, 0.0, out))
        assertFalse(LevelLoudness.apply(FloatArraySource(src.copyOf(3 * 800 + 10)), sr, 3.0, out))
    }

    private fun chainSettings(p: JsonElement) = Pair(
        ChunkSettings(
            targetDbfs = p.dbl("target_dbfs"), trimSilence = p.obj("silence_trimming").jsonPrimitive.boolean,
            minSilenceLen = p.int("min_silence_len"), silenceThreshOffset = p.int("silence_thresh_offset"),
            keepSilence = p.int("keep_silence"), chunkMinutes = g.obj("split").dbl("chunk_minutes"),
            searchSeconds = g.obj("split").dbl("search_seconds"),
            minLastChunkSeconds = g.obj("split").dbl("min_last_chunk_seconds"),
        ),
        CleanSettings(
            denoise = p.obj("denoise").jsonPrimitive.boolean, noiseSampleSec = p.dbl("noise_sample_sec"),
            bandpassLow = p.int("bandpass_low"), bandpassHigh = p.int("bandpass_high"),
            propDecrease = p.dbl("prop_decrease"), levelWindowSec = p.dbl("level_window_sec"),
            transientClampDb = p.dbl("transient_clamp_db"),
        ),
    )

    private val nullEncoder = ChunkEncoder { _, _, _, out -> out.writeBytes(ByteArray(0)) }

    @Test
    fun `C5 chuỗi clean_audio đủ, ba bộ cài đặt`() {
        for ((name, c) in g.obj("chains").jsonObject) {
            val (chunk, clean) = chainSettings(c.obj("params"))
            val folder = File(dir, name)
            val logs = ArrayList<String>()
            val result = ChunkPreparer(chunk, nullEncoder).prepare(
                FloatArraySource(input), sr, "lecture.wav", folder, clean = clean, log = { logs += it },
            )
            // Không còn tệp tạm của các bước làm sạch.
            assertFalse(folder.listFiles()!!.any { it.name.startsWith("tam_") }, "$name: còn tam_*.f32")

            val expPcm = Golden.file("cleaning/chain_$name.pcm").readBytes()
            val shorts = ByteBuffer.wrap(expPcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val expected = FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
            val actual = Pcm16File(File(folder, ChunkPreparer.CLEAN_PCM_FILE)).use { pcm ->
                FloatArray(pcm.size.toInt()).also { pcm.read(0, it, 0, it.size) }
            }
            // Mốc cắt lặng lệch ≤ 1 khối (D85): so bản đồ thời gian theo từng khoảng, 50 ms.
            val expMap = c.obj("time_map").jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.double } }
            assertEquals(expMap.size, result.timeMap.size, "$name: số khoảng giữ lại")
            for ((e, a) in expMap.zip(result.timeMap)) {
                assertTrue(abs(e[0] - a[0]) <= 0.05 && abs(e[1] - a[1]) <= 0.05, "$name: khoảng $a ≠ $e")
            }
            assertEquals(c.dbl("orig_duration"), result.originalDuration)
            if (expected.size == actual.size) {
                assertSnr(expected, actual, 60.0, "chuỗi $name")
            } else {
                // Khoảng giữ lại lệch vài mẫu thì so phần chung đầu bài.
                val n = minOf(expected.size, actual.size)
                assertTrue(abs(expected.size - actual.size) <= sr / 20, "$name: số mẫu ${actual.size} ≠ ${expected.size}")
                assertSnr(expected.copyOf(n / 2), actual.copyOf(n / 2), 60.0, "chuỗi $name (nửa đầu)")
            }
            val expPoints = c.obj("points").jsonArray.map { it.jsonPrimitive.int.toLong() }
            val points = listOf(0L) + result.chunks.map { (it.endSeconds * sr).toLong() }
            assertEquals(expPoints.size, points.size, "$name: số điểm cắt")
            for ((e, a) in expPoints.zip(points)) assertTrue(abs(e - a) <= sr / 20, "$name: điểm cắt $a ≠ $e")
            // Nhật ký: cùng các bước như desktop (đếm dòng, số tiếng động đã ghìm).
            val expLogs = c.obj("logs").jsonArray.map { it.jsonPrimitive.content }
            val steps = expLogs.filter { it.startsWith("[2/6]") || it.startsWith("[3/6]") || it.startsWith("[4/6]") }
            assertEquals(steps.size, logs.count { it.startsWith("[2/6]") || it.startsWith("[3/6]") || it.startsWith("[4/6]") })
            expLogs.firstOrNull { it.contains("Da ghim") }?.let { line ->
                val count = Regex("Da ghim (\\d+)").find(line)!!.groupValues[1]
                assertTrue(logs.any { it.contains("Đã ghìm $count tiếng động") }, "$name: $logs")
            }
        }
    }

    @Test
    fun `dừng ở từng bước làm sạch thì không còn tệp tạm`() {
        val clean = CleanSettings(transientClampDb = 15.0)
        for (stopAt in listOf(PrepareStep.BANDPASS, PrepareStep.TRANSIENTS, PrepareStep.DENOISE, PrepareStep.LEVEL)) {
            val folder = File(dir, "stop_$stopAt")
            val listener = object : PrepareListener {
                var current: PrepareStep? = null
                override fun onProgress(step: PrepareStep, fraction: Double) {
                    current = step
                }

                override fun checkCancelled() {
                    if (current == stopAt) throw CancellationException("dừng ở $stopAt")
                }
            }
            assertFailsWith<CancellationException> {
                ChunkPreparer(ChunkSettings(), nullEncoder).prepare(
                    FloatArraySource(input), sr, "x", folder, listener, clean = clean,
                )
            }
            val left = folder.listFiles()!!.map { it.name }
            assertFalse(left.any { it.startsWith("tam_") || it.endsWith(".part") }, "$stopAt: còn $left")
            assertFalse(File(folder, ChunkPreparer.CHUNK_LIST_FILE).exists())
        }
    }

    @Suppress("unused")
    private fun SampleSource.toArray() = FloatArray(size.toInt()).also { read(0, it, 0, it.size) }
}

package io.github.cozycode890.guzz.core.audio

import io.github.cozycode890.guzz.core.audio.pcm.FloatArraySource
import io.github.cozycode890.guzz.core.audio.pcm.GainSource
import io.github.cozycode890.guzz.core.audio.pcm.SampleSource
import io.github.cozycode890.guzz.core.common.testing.Golden
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * So [Loudness], [SpeechSegments], [SilenceTrimmer] với `measure_dbfs`, `boost_and_normalize`, `find_speech_segments`,
 * `trim_silence` của audio_processing.py.
 *
 * Dữ liệu: `testdata/golden/audio_processing/`, do `tools/golden/gen_audio_processing.py` tạo. Hệ số khuếch đại được so
 * theo dung sai (desktop đo dBFS bằng np.dot float32, lệch 1–2 ulp); từ bước khuếch đại trở đi dùng đúng hệ số của
 * desktop nên phải khớp tuyệt đối.
 */
class AudioProcessingGoldenTest {

    @Serializable
    private class GoldenCase(
        val name: String,
        val wav: String,
        val sr: Int,
        @SerialName("target_dbfs") val targetDbfs: Double,
        @SerialName("silence_thresh_offset") val silenceThreshOffset: Int,
        @SerialName("min_silence_len") val minSilenceLen: Int,
        @SerialName("keep_silence") val keepSilence: Int,
        val dbfs: Double?,
        val gain: Double?,
        @SerialName("gain_exact") val gainExact: Double?,
        @SerialName("boosted_sha256") val boostedSha256: String,
        @SerialName("threshold_db") val thresholdDb: Double?,
        @SerialName("speech_segments") val speechSegments: List<List<Long>>,
        val kept: List<List<Long>>,
        @SerialName("time_map") val timeMap: List<List<Double>>,
        @SerialName("first_ms_sums") val firstMsSums: List<Double>? = null,
    )

    @Serializable
    private class GoldenFile(val cases: List<GoldenCase>)

    private val cases = Golden.readJson<GoldenFile>("audio_processing/audio_processing.json").cases

    private fun source(case: GoldenCase): FloatArraySource {
        val wav = Golden.readWav("audio_processing/${case.wav}")
        assertEquals(case.sr, wav.sampleRate, "tần số lấy mẫu của ${case.wav}")
        return FloatArraySource(wav.samples)
    }

    /** Tín hiệu đã khuếch đại bằng đúng hệ số của desktop. */
    private fun boosted(case: GoldenCase): SampleSource {
        val src = source(case)
        return if (case.gain == null) src else GainSource(src, case.gain.toFloat())
    }

    @TestFactory
    fun `measure_dbfs và hệ số khuếch đại`(): List<DynamicTest> = cases.map { case ->
        dynamicTest(case.name) {
            val src = source(case)
            val dbfs = Loudness.measureDbfs(src)
            if (case.dbfs == null) {
                assertEquals(Double.NEGATIVE_INFINITY, dbfs)
                assertNull(Loudness.gainFor(dbfs, case.targetDbfs))
                return@dynamicTest
            }
            assertTrue(abs(dbfs - case.dbfs) <= 1e-4, "dBFS $dbfs, desktop ${case.dbfs}")
            val gain = Loudness.gainFor(dbfs, case.targetDbfs)!!
            // Bản Kotlin cộng double nên gần hệ số tính từ tổng chính xác; với desktop chỉ lệch vài ulp.
            assertEquals(case.gainExact!!.toFloat(), gain, "hệ số so với tổng chính xác")
            assertTrue(abs(gain - case.gain!!.toFloat()) <= 4 * Math.ulp(gain), "hệ số $gain, desktop ${case.gain}")
        }
    }

    @TestFactory
    fun `boost_and_normalize trùng từng bit`(): List<DynamicTest> = cases.map { case ->
        dynamicTest(case.name) {
            val b = boosted(case)
            val values = FloatArray(b.size.toInt())
            b.read(0, values, 0, values.size)
            val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(values)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            assertEquals(case.boostedSha256, sha)
        }
    }

    @TestFactory
    fun `find_speech_segments`(): List<DynamicTest> = cases.map { case ->
        dynamicTest(case.name) {
            val threshold = case.thresholdDb ?: Double.NEGATIVE_INFINITY
            val got = SpeechSegments.find(boosted(case), case.sr, case.minSilenceLen, threshold)
            assertEquals(case.speechSegments, got.map { listOf(it.startMs, it.endMs) })
        }
    }

    @TestFactory
    fun `trim_silence và TimeMap`(): List<DynamicTest> = cases.map { case ->
        dynamicTest(case.name) {
            val result = SilenceTrimmer.trim(
                boosted(case), case.sr, case.silenceThreshOffset, case.minSilenceLen, case.keepSilence,
            )
            assertEquals(case.kept, result.kept.map { listOf(it.start, it.end) })
            assertEquals(case.timeMap, result.timeMap.toJson())
        }
    }

    @TestFactory
    fun `tổng từng mili giây như np_add_reduceat`(): List<DynamicTest> = cases.filter { it.firstMsSums != null }.map { case ->
        dynamicTest(case.name) {
            val b = boosted(case)
            val perMs = case.sr / 1000
            val buf = FloatArray(perMs)
            val squares = DoubleArray(perMs)
            for ((ms, expected) in case.firstMsSums!!.withIndex()) {
                b.read(ms.toLong() * perMs, buf, 0, perMs)
                for (i in 0 until perMs) squares[i] = buf[i].toDouble() * buf[i].toDouble()
                assertEquals(expected, SpeechSegments.cellSum(squares, perMs), "ms $ms")
            }
        }
    }
}

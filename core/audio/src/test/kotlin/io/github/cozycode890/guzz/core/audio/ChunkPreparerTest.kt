package io.github.cozycode890.guzz.core.audio

import io.github.cozycode890.guzz.core.audio.pcm.FloatArraySource
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16Writer
import io.github.cozycode890.guzz.core.common.testing.Golden
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ChunkPreparer] so với chuỗi boost_and_normalize → trim_silence → find_split_points → write_chunks của desktop
 * (`testdata/golden/prepare/`, do `tools/golden/gen_prepare.py` tạo), cộng các ca dừng giữa chừng và nguồn rỗng.
 */
class ChunkPreparerTest {

    @TempDir
    lateinit var dir: File

    @Serializable
    private class Settings(
        @SerialName("target_dbfs") val targetDbfs: Double,
        @SerialName("trim_silence") val trimSilence: Boolean,
        @SerialName("min_silence_len") val minSilenceLen: Int,
        @SerialName("silence_thresh_offset") val silenceThreshOffset: Int,
        @SerialName("keep_silence") val keepSilence: Int,
        @SerialName("chunk_minutes") val chunkMinutes: Double,
        @SerialName("search_seconds") val searchSeconds: Double,
        @SerialName("min_last_chunk_seconds") val minLastChunkSeconds: Double,
    )

    @Serializable
    private class Chunk(val file: String, val start: Double, val end: Double)

    @Serializable
    private class ChunkPcm(val sha256: String, val samples: Long)

    @Serializable
    private class GoldenPrepare(
        val wav: String,
        val sr: Int,
        val settings: Settings,
        val points: List<Long>,
        @SerialName("time_map") val timeMap: List<List<Double>>,
        @SerialName("orig_duration") val origDuration: Double,
        val chunks: List<Chunk>,
        @SerialName("chunk_pcm") val chunkPcm: List<ChunkPcm>,
    )

    /** "Mã hoá" thử: chép thẳng int16 ra tệp và ghi lại SHA-256 + số mẫu của từng đoạn. */
    private class RecordingEncoder : ChunkEncoder {
        val written = ArrayList<Pair<String, ChunkPcm>>()

        override fun encode(pcm: Pcm16File, range: SampleRange, sampleRate: Int, out: File) {
            val shorts = ShortArray(range.length.toInt())
            pcm.readShorts(range.start, shorts, count = shorts.size)
            val bytes = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(shorts)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            Pcm16Writer(out).use { it.write(shorts) }
            written += out.name to ChunkPcm(sha, shorts.size.toLong())
        }
    }

    @Test
    fun `khớp desktop về bản đồ thời gian, mốc đoạn và mẫu int16 từng đoạn`() {
        val g = Golden.readJson<GoldenPrepare>("prepare/prepare.json")
        val wav = Golden.readWav("prepare/${g.wav}")
        val s = g.settings
        val settings = ChunkSettings(
            s.targetDbfs, s.trimSilence, s.minSilenceLen, s.silenceThreshOffset, s.keepSilence,
            s.chunkMinutes, s.searchSeconds, s.minLastChunkSeconds,
        )
        val encoder = RecordingEncoder()
        val result = ChunkPreparer(settings, encoder).prepare(FloatArraySource(wav.samples), g.sr, "bai.wav", dir)

        assertEquals(g.origDuration, result.originalDuration)
        assertEquals(g.timeMap, result.timeMap)
        assertEquals(g.chunks.map { listOf(it.file, it.start, it.end) }, result.chunks.map {
            listOf(it.file, it.startSeconds, it.endSeconds)
        })
        assertEquals(g.chunkPcm.map { it.sha256 to it.samples }, encoder.written.map { it.second.sha256 to it.second.samples })
        // Tổng số mẫu sạch = điểm cắt cuối.
        assertEquals(g.points.last(), Pcm16File(File(dir, ChunkPreparer.CLEAN_PCM_FILE)).use { it.size })

        val reread = assertNotNull(ChunkPreparer.readChunkList(dir))
        assertEquals(result, reread)
        assertEquals("bai.wav", reread.source)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") || it.name.endsWith(".tmp") })
    }

    @Test
    fun `dừng giữa chừng thì không có danh_sach_doan_json và không còn tệp part`() {
        val wav = Golden.readWav("prepare/lecture.wav")
        val settings = ChunkSettings(minSilenceLen = 700, silenceThreshOffset = 16, keepSilence = 300, chunkMinutes = 0.25,
            searchSeconds = 3.0, minLastChunkSeconds = 4.0)
        for (stopAt in PrepareStep.entries) {
            val folder = File(dir, stopAt.name)
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
                ChunkPreparer(settings, RecordingEncoder()).prepare(FloatArraySource(wav.samples), 1000, "x", folder, listener)
            }
            assertFalse(File(folder, ChunkPreparer.CHUNK_LIST_FILE).exists(), "$stopAt")
            assertFalse(folder.listFiles()!!.any { it.name.endsWith(".part") }, "$stopAt: còn tệp .part")
        }
    }

    @Test
    fun `trim_silence tắt thì giữ cả bài`() {
        val wav = Golden.readWav("prepare/lecture.wav")
        val result = ChunkPreparer(ChunkSettings(trimSilence = false, chunkMinutes = 0.5), RecordingEncoder())
            .prepare(FloatArraySource(wav.samples), 1000, "x", dir)
        assertEquals(listOf(listOf(0.0, 90.0)), result.timeMap)
        assertEquals(90.0, result.chunks.last().endSeconds)
    }

    @Test
    fun `nguồn rỗng báo lỗi như desktop`() {
        val e = assertFailsWith<NoSpeechException> {
            ChunkPreparer(ChunkSettings(), RecordingEncoder()).prepare(FloatArraySource(FloatArray(0)), 16_000, "x", dir)
        }
        assertTrue(e.message!!.contains("không còn đoạn nào có tiếng"))
    }
}

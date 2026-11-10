package io.github.cozycode890.guzz.core.media

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cozycode890.guzz.core.audio.ChunkPreparer
import io.github.cozycode890.guzz.core.audio.ChunkSettings
import io.github.cozycode890.guzz.core.audio.SampleRange
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16Writer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Test trên máy của `:core:media` (bước C4) với tệp thử tổng hợp ở `testdata/media/` (`tools/golden/gen_media.py`):
 * giải mã từng định dạng, chọn luồng của video 2 luồng, video câm, tệp hỏng, FLAC mã hoá rồi giải mã lại.
 *
 *     .\gradlew :core:media:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MediaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "media-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Chép asset của APK test ra tệp thật (MediaExtractor đọc theo đường dẫn như khi chạy thật). */
    private fun asset(name: String): File {
        val out = File(dir, name)
        instrumentation.context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun decode(name: String, track: String = "auto"): Pair<FloatArray, DecodeResult> {
        val out = File(dir, "$name.pcm")
        val result = MediaInput.Path(asset(name)).open(context).use { media ->
            val tracks = AudioTracks.list(media.extractor)
            AudioDecoder().decode(media.extractor, AudioTracks.select(tracks, track)!!, out)
        }
        val samples = Pcm16File(out).use { pcm -> FloatArray(pcm.size.toInt()).also { pcm.read(0, it, 0, it.size) } }
        return samples to result
    }

    /** Tần số trội đếm bằng số lần đổi dấu trong 1 giây giữa bài. */
    private fun dominantFrequency(y: FloatArray, rate: Int = 16_000): Double {
        val from = y.size / 2 - rate / 2
        var crossings = 0
        for (i in from + 1 until from + rate) if ((y[i - 1] < 0f) != (y[i] < 0f)) crossings++
        return crossings / 2.0
    }

    private fun assertTone(name: String, expectedHz: Double, track: String = "auto") {
        val (y, r) = decode(name, track)
        assertEquals(16_000, r.sampleRate)
        val seconds = y.size / 16_000.0
        assertTrue("$name: dài $seconds s (${r.codecName}, ${r.inputSampleRate} Hz × ${r.inputChannels})", abs(seconds - 3.0) <= 0.020)
        val f = dominantFrequency(y)
        assertTrue("$name: tần số trội $f Hz, cần $expectedHz", abs(f - expectedHz) <= 2.0)
    }

    @Test
    fun aacStereo44k() = assertTone("tone_aac_stereo_44k.m4a", 1000.0)

    @Test
    fun mp3Mono48k() = assertTone("tone_mp3_48k.mp3", 1000.0)

    @Test
    fun opusOgg() = assertTone("tone_opus.ogg", 1000.0)

    @Test
    fun wav22k() = assertTone("tone_wav_22k.wav", 1000.0)

    @Test
    fun videoHaiLuongChonDungLuong() {
        val tracks = MediaInput.Path(asset("two_tracks.mp4")).open(context).use { AudioTracks.list(it.extractor) }
        assertEquals(2, tracks.size)
        assertEquals(listOf(0, 1), tracks.map { it.index })
        assertTone("two_tracks.mp4", 440.0, "auto")
        assertTone("two_tracks.mp4", 880.0, "1")
        assertTone("two_tracks.mp4", 440.0, "7")
    }

    @Test
    fun videoCamBaoNoAudio() {
        assertThrows(NoAudioException::class.java) {
            AudioPreparation(context).run(MediaInput.Path(asset("silent_video.mp4")), "auto", ChunkSettings(), File(dir, "out"))
        }
    }

    @Test
    fun tepHongBaoMediaRead() {
        assertThrows(MediaReadException::class.java) {
            AudioPreparation(context).run(MediaInput.Path(asset("broken.m4a")), "auto", ChunkSettings(), File(dir, "out"))
        }
        assertThrows(MediaReadException::class.java) { MediaInput.Path(File(dir, "khong-co.m4a")).open(context) }
    }

    @Test
    fun flacMaHoaRoiGiaiMaLaiTrungTungMau() {
        // 2,5 giây tín hiệu có đủ biên độ, cả giá trị biên.
        val n = 40_000
        val shorts = ShortArray(n) { (20_000 * sin(2 * PI * 437 * it / 16_000) + (it % 7) * 300).toInt().coerceIn(-32768, 32767).toShort() }
        shorts[100] = Short.MAX_VALUE
        shorts[101] = Short.MIN_VALUE
        val pcmFile = File(dir, "in.pcm")
        Pcm16Writer(pcmFile).use { it.write(shorts) }
        val flac = File(dir, "doan_001.flac")
        Pcm16File(pcmFile).use { FlacEncoder().encode(it, SampleRange(0, n.toLong()), 16_000, flac) }
        assertTrue(flac.length() > 0)
        assertFalse(File(flac.path + ".part").exists())

        val head = ByteArray(FlacHeader.HEAD_BYTES).also { b -> flac.inputStream().use { it.read(b) } }
        assertEquals(n.toLong(), FlacHeader.totalSamples(head))

        val back = File(dir, "back.pcm")
        MediaInput.Path(flac).open(context).use { media ->
            AudioDecoder().decode(media.extractor, AudioTracks.list(media.extractor).single(), back)
        }
        val decoded = Pcm16File(back).use { pcm -> ShortArray(pcm.size.toInt()).also { pcm.readShorts(0, it, 0, it.size) } }
        assertArrayEquals(shorts, decoded)
    }

    @Test
    fun caChuoiRaDoanFlacVaDanhSachDoan() {
        val out = File(dir, "out")
        val result = AudioPreparation(context).run(
            MediaInput.Path(asset("tone_aac_stereo_44k.m4a")), "auto", ChunkSettings(chunkMinutes = 0.02, searchSeconds = 0.2, minLastChunkSeconds = 0.3), out,
        )
        assertTrue(result.chunks.chunks.size >= 2)
        val list = ChunkPreparer.readChunkList(out)
        assertNotNull(list)
        list!!
        assertEquals(result.chunks, list)
        for (c in list.chunks) assertTrue(c.file, File(out, c.file).length() > 0)
        assertTrue(File(out, AudioPreparation.RAW_PCM_FILE).isFile)
        assertTrue(File(out, ChunkPreparer.CLEAN_PCM_FILE).isFile)
    }
}

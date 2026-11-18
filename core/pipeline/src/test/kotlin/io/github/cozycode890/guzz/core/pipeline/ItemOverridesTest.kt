package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.common.settings.AudioPresets
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Stage 6 A4 (D95): ghi đè cài đặt cho riêng một tệp. */
class ItemOverridesTest {
    private val base = testConfig()

    @Test
    fun `không ghi đè thì giữ nguyên cấu hình`() {
        assertSame(base, base.withOverrides(null))
        assertSame(base, base.withOverrides(ItemOverrides()))
    }

    @Test
    fun `speakers off tắt người nói, cách khác bật đúng cách, num_speakers đổi số người`() {
        val on = base.copy(gemini = base.gemini.copy(diarizationEnabled = true, diarizationMethod = "gemini"))
        assertFalse(on.withOverrides(ItemOverrides(speakers = "off")).gemini.diarizationEnabled)
        val hybrid = base.withOverrides(ItemOverrides(speakers = "hybrid", numSpeakers = 3))
        assertTrue(hybrid.gemini.diarizationEnabled)
        assertEquals("hybrid", hybrid.gemini.diarizationMethod)
        assertEquals(3, hybrid.speakers.numSpeakers)
        // Trường khác giữ nguyên.
        assertEquals(base.gemini.model, hybrid.gemini.model)
        assertEquals(base.chunk, hybrid.chunk)
        assertEquals(base.clean, hybrid.clean)
        assertFailsWith<IllegalArgumentException> { base.withOverrides(ItemOverrides(speakers = "abc")) }
    }

    @Test
    fun `preset áp đủ mười trường như trang Âm thanh, bật cắt lặng và khử ồn, denoise áp sau preset`() {
        val p = AudioPresets.all.getValue(AudioPresets.SPEAKERS_AROUND)
        val off = base.copy(chunk = base.chunk.copy(trimSilence = false))
        val cfg = off.withOverrides(ItemOverrides(preset = AudioPresets.SPEAKERS_AROUND))
        assertEquals(p.targetDbfs, cfg.chunk.targetDbfs)
        assertEquals(p.silenceThreshOffset, cfg.chunk.silenceThreshOffset)
        assertEquals(p.minSilenceLen, cfg.chunk.minSilenceLen)
        assertEquals(p.keepSilence, cfg.chunk.keepSilence)
        assertEquals(p.noiseSampleSec, cfg.clean.noiseSampleSec)
        assertEquals(p.bandpassLow, cfg.clean.bandpassLow)
        assertEquals(p.bandpassHigh, cfg.clean.bandpassHigh)
        assertEquals(p.propDecrease, cfg.clean.propDecrease)
        assertEquals(p.levelWindowSec, cfg.clean.levelWindowSec)
        assertEquals(p.transientClampDb, cfg.clean.transientClampDb)
        assertTrue(cfg.chunk.trimSilence)
        assertTrue(cfg.clean.denoise)
        // Độ dài đoạn không thuộc preset.
        assertEquals(base.chunk.chunkMinutes, cfg.chunk.chunkMinutes)
        val noDenoise = base.withOverrides(ItemOverrides(preset = AudioPresets.NEAR_LECTURER, denoise = false))
        assertFalse(noDenoise.clean.denoise)
        assertFailsWith<IllegalArgumentException> { base.withOverrides(ItemOverrides(preset = "abc")) }
    }

    @Test
    fun `đổi preset bằng ghi đè thì mã cache âm thanh đổi, ghi đè người nói thì không`() {
        fun hash(c: PipelineConfig) = CacheKeys.audioHash("fp", PipelineConfig.SAMPLE_RATE, c.chunk, c.clean, c.chunkFormat)
        assertNotEquals(hash(base), hash(base.withOverrides(ItemOverrides(preset = AudioPresets.SPEAKERS_AROUND))))
        assertEquals(hash(base), hash(base.withOverrides(ItemOverrides(speakers = "off"))))
    }

    @Test
    fun `fake_gemini chỉ ghi vào cấu hình, output_dir không đổi thư mục SAF`() {
        val cfg = base.copy(outputDir = "content://tree").withOverrides(ItemOverrides(fakeGemini = false, outputDir = "source"))
        assertEquals(false, cfg.debugFakeGemini)
        assertEquals("content://tree", cfg.outputDir)
        assertEquals("output_dir=source, fake_gemini=false", ItemOverrides(fakeGemini = false, outputDir = "source").describe())
    }
}

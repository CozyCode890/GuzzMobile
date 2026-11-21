package io.github.cozycode890.guzz.core.common.testing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Test của chính công cụ đọc golden: WAV do Python (`gen_wav_io.py`) ghi, Kotlin đọc phải ra đúng mẫu. */
class GoldenTest {

    @Serializable
    private class KnownSamples(
        val sr: Int,
        @SerialName("samples_int16") val samples: List<Int>,
    )

    @Test
    fun `đọc WAV 16-bit mono do Python ghi`() {
        val expected = Golden.readJson<KnownSamples>("wav/known_samples.json")
        val wav = Golden.readWav("wav/known_samples.wav")

        assertEquals(8000, wav.sampleRate)
        assertEquals(expected.sr, wav.sampleRate)
        assertEquals(11, wav.samples.size)
        assertEquals(0f, wav.samples[0])
        assertEquals(32767 / 32768f, wav.samples[3])
        assertEquals(-1f, wav.samples[4])
        assertEquals(0.5f, wav.samples[5])
        assertContentEquals(expected.samples.map { it / 32768f }.toFloatArray(), wav.samples)
    }

    @Test
    fun `tệp thiếu thì báo lỗi rõ ràng`() {
        val error = assertFailsWith<IllegalStateException> { Golden.file("khong/co/tep.json") }
        assertEquals(true, error.message?.contains("regenerate.ps1"))
    }

    @Test
    fun `WAV không phải 16-bit mono thì từ chối`() {
        val stereo = Golden.file("wav/known_samples.wav").readBytes().copyOf()
        stereo[22] = 2 // số kênh trong chunk fmt
        assertFailsWith<IllegalArgumentException> { Golden.parseWav(stereo, "stereo.wav") }
    }
}

package io.github.cozycode890.guzz.core.audio.pcm

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Chuyển từ MediaPureTest của `:core:media` cùng với [Downmix] (Stage 11 B1). */
class DownmixTest {
    @Test
    fun `trộn stereo 16-bit về mono bằng trung bình`() {
        val b = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
        for (v in shortArrayOf(1000, 3000, -32768, -32768, 32767, -32767, 0, 1)) b.putShort(v)
        val out = FloatArray(4)
        assertEquals(4, Downmix.pcm16(b, 0, 16, 2, out))
        assertContentEquals(floatArrayOf(2000f / 32768f, -1f, 0f, 0.5f / 32768f), out)
    }

    @Test
    fun `trộn float nhiều kênh, có offset`() {
        val b = ByteBuffer.allocate(4 + 24).order(ByteOrder.nativeOrder())
        b.putFloat(9f)
        for (v in floatArrayOf(0.3f, 0.6f, -0.3f, 1f, 1f, 1f)) b.putFloat(v)
        val out = FloatArray(2)
        assertEquals(2, Downmix.pcmFloat(b, 4, 24, 3, out))
        assertEquals(0.2f, out[0], 1e-7f)
        assertEquals(1f, out[1], 0f)
    }
}

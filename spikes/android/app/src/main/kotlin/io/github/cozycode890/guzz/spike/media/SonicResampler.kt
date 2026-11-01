package io.github.cozycode890.guzz.spike.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Đổi tần số lấy mẫu bằng `SonicAudioProcessor` của Media3 1.11.1 (D38). Sonic chỉ nhận PCM 16-bit. */
@OptIn(UnstableApi::class)
class SonicResampler(inRate: Int, outRate: Int) {
    private val sonic = SonicAudioProcessor().apply { setOutputSampleRateHz(outRate) }
    private var inBuf = ByteBuffer.allocateDirect(1 shl 16).order(ByteOrder.nativeOrder())

    init {
        sonic.configure(AudioProcessor.AudioFormat(inRate, 1, C.ENCODING_PCM_16BIT))
        // Media3 1.11: flush() không tham số chỉ là mặc định gọi sang bản có StreamMetadata, Sonic chỉ cài bản này.
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    /** Thêm [n] mẫu mono 16-bit; trả về mẫu ra (float [-1, 1)). */
    fun process(pcm: ShortArray, n: Int): FloatArray {
        if (inBuf.capacity() < n * 2) inBuf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
        inBuf.clear()
        inBuf.asShortBuffer().put(pcm, 0, n)
        inBuf.limit(n * 2)
        val out = ArrayList<FloatArray>()
        while (inBuf.hasRemaining()) {
            sonic.queueInput(inBuf)
            out.add(drain())
        }
        return concat(out)
    }

    fun flush(): FloatArray {
        sonic.queueEndOfStream()
        val out = ArrayList<FloatArray>()
        while (!sonic.isEnded) {
            val chunk = drain()
            if (chunk.isEmpty() && !sonic.isEnded) break
            out.add(chunk)
        }
        return concat(out)
    }

    private fun drain(): FloatArray {
        val o = sonic.output.order(ByteOrder.nativeOrder())
        val s = o.asShortBuffer()
        val r = FloatArray(s.remaining()) { s.get() / 32768f }
        o.position(o.limit())
        return r
    }

    private fun concat(parts: List<FloatArray>): FloatArray {
        val r = FloatArray(parts.sumOf { it.size })
        var p = 0
        for (x in parts) {
            x.copyInto(r, p)
            p += x.size
        }
        return r
    }
}

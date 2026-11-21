package io.github.cozycode890.guzz.core.audio.pcm

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Trộn PCM xen kênh từ bộ giải mã về mono float [-1, 1]: trung bình các kênh (như `-ac 1` của ffmpeg với stereo, mỗi
  * kênh × 0,5). Đọc thẳng từ ByteBuffer theo chỉ số tuyệt đối, không tạo ShortBuffer / FloatBuffer view cho từng mẫu.
 *
 * JVM thuần (Stage 11 B1): bộ giải mã MediaCodec của điện thoại và ffmpeg trên server (`:server`) dùng chung.
 */
object Downmix {

    /** PCM 16-bit native-endian (`ENCODING_PCM_16BIT`). Trả về số khung đã ghi vào [out]. */
    fun pcm16(src: ByteBuffer, offset: Int, size: Int, channels: Int, out: FloatArray): Int {
        val frames = size / (2 * channels)
        val b = src.duplicate().order(ByteOrder.nativeOrder())
        val scale = 1f / (32768f * channels)
        var p = offset
        for (i in 0 until frames) {
            var s = 0
            for (c in 0 until channels) {
                s += b.getShort(p)
                p += 2
            }
            out[i] = s * scale
        }
        return frames
    }

    /** PCM float native-endian (`ENCODING_PCM_FLOAT`). Trả về số khung đã ghi vào [out]. */
    fun pcmFloat(src: ByteBuffer, offset: Int, size: Int, channels: Int, out: FloatArray): Int {
        val frames = size / (4 * channels)
        val b = src.duplicate().order(ByteOrder.nativeOrder())
        var p = offset
        for (i in 0 until frames) {
            var s = 0f
            for (c in 0 until channels) {
                s += b.getFloat(p)
                p += 4
            }
            out[i] = s / channels
        }
        return frames
    }
}

package io.github.cozycode890.guzz.spike.diarize

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Tệp PCM f32le 16 kHz mono (`.f32`, do prepare_data.py tạo). Đọc theo khối 1 MB, không cấp buffer bằng cả tệp. */
object F32File {
    const val SAMPLE_RATE = 16000

    fun length(file: File): Long = file.length() / 4

    /** Mẫu [start, end) vào một FloatArray mới. Cả bài 2 giờ là 460 MB, sát heap 512 MB (D4). */
    fun read(file: File, start: Long = 0, end: Long = length(file)): FloatArray {
        val n = (end - start).toInt()
        val out = FloatArray(n)
        RandomAccessFile(file, "r").use { raf ->
            val ch = raf.channel
            ch.position(start * 4)
            val buf = ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)
            var filled = 0
            while (filled < n) {
                buf.clear()
                val want = minOf(buf.capacity(), (n - filled) * 4)
                buf.limit(want)
                while (buf.hasRemaining()) if (ch.read(buf) < 0) break
                buf.flip()
                val floats = buf.remaining() / 4
                if (floats == 0) break
                buf.asFloatBuffer().get(out, filled, floats)
                filled += floats
            }
        }
        return out
    }
}

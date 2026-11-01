package io.github.cozycode890.guzz.spike.media

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Ghi mẫu float ra tệp f32le theo khối 256 KB, không giữ cả bài trong RAM. */
class F32Writer(file: File) : Closeable {
    private val ch = FileOutputStream(file).channel
    private val buf = ByteBuffer.allocateDirect(1 shl 18).order(ByteOrder.LITTLE_ENDIAN)
    var samples = 0L
        private set

    fun write(x: FloatArray, n: Int = x.size) {
        for (i in 0 until n) {
            if (buf.remaining() < 4) drain()
            buf.putFloat(x[i])
        }
        samples += n
    }

    private fun drain() {
        buf.flip()
        while (buf.hasRemaining()) ch.write(buf)
        buf.clear()
    }

    override fun close() {
        drain()
        ch.close()
    }
}

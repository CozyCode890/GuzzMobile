package io.github.cozycode890.guzz.core.audio.pcm

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Tệp tạm float32 little-endian mono, không header (`tam_NN.f32`, Stage 7). Các bước làm sạch đầy đủ ghi kết quả ra đây
 * thay vì giữ cả bài trong RAM (D43); float32 như mảng `y` của desktop, nên chép qua lại không mất gì.
 */
class F32File(val file: File) : SampleSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel
    private var bytes: ByteBuffer = ByteBuffer.allocateDirect(0)

    override val size: Long = channel.size() / BYTES_PER_SAMPLE

    override fun read(start: Long, dst: FloatArray, offset: Int, count: Int) {
        require(start >= 0 && count >= 0 && start + count <= size) { "${file.name}: đọc [$start, ${start + count}) ngoài [0, $size)" }
        val need = count * BYTES_PER_SAMPLE
        if (bytes.capacity() < need) bytes = ByteBuffer.allocateDirect(need)
        bytes.clear().limit(need)
        var pos = start * BYTES_PER_SAMPLE
        while (bytes.hasRemaining()) {
            val n = channel.read(bytes, pos)
            if (n < 0) throw IOException("${file.name}: hết tệp sớm ở byte $pos")
            pos += n
        }
        bytes.flip()
        bytes.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(dst, offset, count)
    }

    override fun close() = raf.close()

    companion object {
        const val BYTES_PER_SAMPLE = 4
    }
}

/**
 * Ghi float32 little-endian theo khối, vào `<tệp>.part` rồi [close] mới đổi tên (như [Pcm16Writer]); [abort] xoá `.part`.
 */
class F32Writer(val file: File) : Closeable {
    val partFile = File(file.path + Pcm16Writer.PART_SUFFIX)
    private val raf: RandomAccessFile
    private val channel: FileChannel
    private val bytes = ByteBuffer.allocateDirect(BUFFER_SAMPLES * F32File.BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
    private var closed = false

    var samplesWritten = 0L
        private set

    init {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(partFile, "rw")
        raf.setLength(0)
        channel = raf.channel
    }

    fun write(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset) {
        check(!closed) { "${file.name}: đã đóng" }
        var i = offset
        val end = offset + count
        while (i < end) {
            if (!bytes.hasRemaining()) flushBuffer()
            val n = minOf(end - i, bytes.remaining() / F32File.BYTES_PER_SAMPLE)
            bytes.asFloatBuffer().put(samples, i, n)
            bytes.position(bytes.position() + n * F32File.BYTES_PER_SAMPLE)
            i += n
        }
        samplesWritten += count
    }

    private fun flushBuffer() {
        bytes.flip()
        while (bytes.hasRemaining()) channel.write(bytes)
        bytes.clear()
    }

    override fun close() {
        if (closed) return
        flushBuffer()
        raf.close()
        closed = true
        Pcm16Writer.moveReplacing(partFile, file)
    }

    fun abort() {
        if (!closed) {
            closed = true
            runCatching { raf.close() }
        }
        partFile.delete()
    }

    companion object {
        private const val BUFFER_SAMPLES = 1 shl 15
    }
}

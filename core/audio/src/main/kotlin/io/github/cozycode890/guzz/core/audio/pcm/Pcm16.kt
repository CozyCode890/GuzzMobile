package io.github.cozycode890.guzz.core.audio.pcm

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Đổi mẫu float ↔ int16. */
object Pcm16 {
    const val BYTES_PER_SAMPLE = 2

    /** int16 → float, đúng như `samples.astype(np.float32) / 32768.0` của `read_audio` (chia cho luỹ thừa 2 nên chính xác). */
    fun toFloat(s: Short): Float = s / 32768f

    /**
     * Float → int16 kiểu desktop khi ghi tệp: kẹp [-1, 1], nhân 32767, cắt phần lẻ về 0.
     * Port của audio_io.py: `_to_pcm16`. Đoạn gửi Gemini phải ghi đúng cách này để mẫu int16 trùng desktop.
     */
    fun fromFloatDesktop(x: Float): Short {
        val c = if (x > 1f) 1f else if (x < -1f) -1f else x
        // numpy nhân mảng float32 với số Python 32767.0 ra float32 (NEP 50), rồi astype(int16) cắt về 0.
        return (c * 32767f).toInt().toShort()
    }

    /**
     * Float → int16 kiểu ffmpeg khi giải mã ra `s16le`: nhân 32768, làm tròn tới số gần nhất (nửa về số chẵn như
     * `lrintf`), kẹp vào khoảng int16. Dùng khi ghi `goc.pcm` để PCM giải mã trên máy gần với ffmpeg của desktop nhất.
     */
    fun fromFloatRounded(x: Float): Short {
        val r = Math.rint(x * 32768.0)
        return when {
            r > Short.MAX_VALUE -> Short.MAX_VALUE
            r < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> r.toInt().toShort()
        }
    }
}

/**
 * Tệp PCM 16-bit little-endian mono, không header, đọc theo khoảng (D43). Đọc như `SampleSource` (float) hoặc lấy thẳng
 * int16 ([readShorts]) để chép ra đoạn mà không đổi qua float.
 */
class Pcm16File(val file: File) : SampleSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel
    private var bytes: ByteBuffer = ByteBuffer.allocateDirect(0)

    override val size: Long = channel.size() / Pcm16.BYTES_PER_SAMPLE

    override fun read(start: Long, dst: FloatArray, offset: Int, count: Int) {
        val shorts = fill(start, count).asShortBuffer()
        for (i in 0 until count) dst[offset + i] = Pcm16.toFloat(shorts.get(i))
    }

    fun readShorts(start: Long, dst: ShortArray, offset: Int = 0, count: Int) {
        fill(start, count).asShortBuffer().get(dst, offset, count)
    }

    private fun fill(start: Long, count: Int): ByteBuffer {
        require(start >= 0 && count >= 0 && start + count <= size) { "${file.name}: đọc [$start, ${start + count}) ngoài [0, $size)" }
        val need = count * Pcm16.BYTES_PER_SAMPLE
        if (bytes.capacity() < need) bytes = ByteBuffer.allocateDirect(need)
        bytes.clear().limit(need)
        var pos = start * Pcm16.BYTES_PER_SAMPLE
        while (bytes.hasRemaining()) {
            val n = channel.read(bytes, pos)
            if (n < 0) throw IOException("${file.name}: hết tệp sớm ở byte $pos")
            pos += n
        }
        bytes.flip()
        return bytes.order(ByteOrder.LITTLE_ENDIAN)
    }

    override fun close() = raf.close()
}

/**
 * Ghi PCM 16-bit little-endian mono theo khối. Ghi vào `<tệp>.part`, [close] mới đổi tên thành [file]: tắt máy giữa
 * chừng không để lại tệp hỏng mang tên thật (như `write_audio` của desktop). [abort] xoá tệp `.part`.
 */
class Pcm16Writer(val file: File) : Closeable {
    val partFile = File(file.path + PART_SUFFIX)
    private val raf: RandomAccessFile
    private val channel: FileChannel
    private val bytes = ByteBuffer.allocateDirect(BUFFER_SAMPLES * Pcm16.BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
    private var closed = false

    /** Số mẫu đã ghi. */
    var samplesWritten = 0L
        private set

    init {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(partFile, "rw")
        raf.setLength(0)
        channel = raf.channel
    }

    fun write(samples: ShortArray, offset: Int = 0, count: Int = samples.size - offset) {
        check(!closed) { "${file.name}: đã đóng" }
        var i = offset
        val end = offset + count
        while (i < end) {
            if (!bytes.hasRemaining()) flushBuffer()
            val n = minOf(end - i, bytes.remaining() / Pcm16.BYTES_PER_SAMPLE)
            bytes.asShortBuffer().put(samples, i, n)
            bytes.position(bytes.position() + n * Pcm16.BYTES_PER_SAMPLE)
            i += n
        }
        samplesWritten += count
    }

    private fun flushBuffer() {
        bytes.flip()
        while (bytes.hasRemaining()) channel.write(bytes)
        bytes.clear()
    }

    /** Ghi nốt, đóng, rồi đổi `.part` thành tên thật. */
    override fun close() {
        if (closed) return
        flushBuffer()
        channel.force(false)
        raf.close()
        closed = true
        moveReplacing(partFile, file)
    }

    /** Bỏ dở: đóng và xoá `.part`, không đụng tới [file]. */
    fun abort() {
        if (!closed) {
            closed = true
            runCatching { raf.close() }
        }
        partFile.delete()
    }

    companion object {
        const val PART_SUFFIX = ".part"
        private const val BUFFER_SAMPLES = 1 shl 15

        /** Đổi tên đè tệp đích, như `os.replace`. */
        fun moveReplacing(from: File, to: File) {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

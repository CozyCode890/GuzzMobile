package io.github.cozycode890.guzz.server

import io.github.cozycode890.guzz.core.audio.ChunkEncoder
import io.github.cozycode890.guzz.core.audio.SampleRange
import io.github.cozycode890.guzz.core.audio.SincResampler
import io.github.cozycode890.guzz.core.audio.pcm.Downmix
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16Writer
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Tệp không có luồng âm thanh (hoặc không có luồng thứ N). Như `NoAudioException` của `:core:media`. */
class NoAudioStreamException(message: String) : RuntimeException(message)

/** Đường dẫn ffmpeg: biến môi trường `GUZZ_FFMPEG`, không có thì `ffmpeg` trên PATH (image của Modal). */
object Ffmpeg {
    val path: String get() = System.getenv("GUZZ_FFMPEG")?.takeIf { it.isNotBlank() } ?: "ffmpeg"

    fun version(): String {
        val p = ProcessBuilder(path, "-hide_banner", "-version").redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return out.lineSequence().firstOrNull().orEmpty()
    }

    /** Đọc stderr của [p] vào bộ nhớ (giới hạn [limit] ký tự) trên một luồng riêng, gọi [onLine] cho từng dòng. */
    internal fun drainStderr(p: Process, limit: Int, onLine: (String) -> Unit = {}): Pair<Thread, StringBuilder> {
        val err = StringBuilder()
        val t = thread(name = "ffmpeg-stderr", isDaemon = true) {
            p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                synchronized(err) { if (err.length < limit) err.appendLine(line) }
                onLine(line)
            }
        }
        return t to err
    }

    internal fun tail(err: StringBuilder, lines: Int = 3): String =
        synchronized(err) { err.toString() }.trim().lines().takeLast(lines).joinToString(" | ")
}

/**
 * Giải mã một luồng âm thanh bằng ffmpeg ra `goc.pcm` (int16, mono, [outRate] Hz), cùng đường với `AudioDecoder` của
 * điện thoại sau khi có PCM: ffmpeg chỉ giải mã (giữ tần số mẫu và số kênh gốc, ra float), rồi [Downmix.pcmFloat] →
 * [SincResampler] → [Pcm16.fromFloatRounded]. Nhờ vậy khác biệt với điện thoại chỉ còn ở bộ giải mã (Rủi ro 3).
 *
 * ffmpeg ghi WAV ra stdout (tiêu đề cho biết tần số và số kênh, không cần ffprobe); độ dài để báo tiến độ lấy từ dòng
 * `Duration:` ở stderr.
 */
class FfmpegDecoder(private val outRate: Int = 16_000) {

    /** @return số mẫu đã ghi vào [out]. */
    fun decode(
        input: File,
        streamIndex: Int,
        out: File,
        onProgress: (Double) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): Long {
        val cmd = listOf(
            Ffmpeg.path, "-nostdin", "-hide_banner", "-nostats", "-i", input.absolutePath,
            "-map", "0:a:$streamIndex", "-vn", "-sn", "-dn", "-c:a", "pcm_f32le", "-f", "wav", "-",
        )
        val p = ProcessBuilder(cmd).start()
        val durationMs = AtomicLong(0)
        val (errThread, err) = Ffmpeg.drainStderr(p, 20_000) { line ->
            DURATION.find(line)?.let { m ->
                val (h, mi, s) = m.destructured
                durationMs.set(((h.toInt() * 3600 + mi.toInt() * 60 + s.toDouble()) * 1000).toLong())
            }
        }
        val writer = Pcm16Writer(out)
        try {
            DataInputStream(p.inputStream.buffered(1 shl 16)).use { stream ->
                val header = try {
                    readWavHeader(stream)
                } catch (e: EOFException) {
                    null
                }
                if (header == null) {
                    p.waitFor()
                    errThread.join(2000)
                    val text = synchronized(err) { err.toString() }
                    if ("matches no streams" in text || "does not contain any stream" in text) {
                        throw NoAudioStreamException("${input.name} không có luồng âm thanh nào.")
                    }
                    throw IOException("ffmpeg không giải mã được ${input.name}: ${Ffmpeg.tail(err)}")
                }
                pump(stream, header, writer, { durationMs.get() / 1000.0 }, onProgress, checkCancelled)
            }
            val code = p.waitFor()
            errThread.join(2000)
            if (code != 0) throw IOException("ffmpeg thoát mã $code: ${Ffmpeg.tail(err)}")
            writer.close()
            return writer.samplesWritten
        } catch (e: Throwable) {
            p.destroyForcibly()
            writer.abort()
            throw e
        }
    }

    private class WavHeader(val rate: Int, val channels: Int)

    /** Đọc tiêu đề RIFF/WAVE tới đầu chunk `data` (ffmpeg ghi cỡ 0xFFFFFFFF khi ra pipe). */
    private fun readWavHeader(input: DataInputStream): WavHeader {
        val riff = ByteArray(12)
        input.readFully(riff)
        require(String(riff, 0, 4, Charsets.US_ASCII) == "RIFF" && String(riff, 8, 4, Charsets.US_ASCII) == "WAVE") {
            "ffmpeg không ra WAV"
        }
        var rate = 0
        var channels = 0
        val head = ByteArray(8)
        while (true) {
            input.readFully(head)
            val id = String(head, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (id == "data") break
            val body = ByteArray(size.toInt())
            input.readFully(body)
            if (size % 2 == 1L) input.skipNBytes(1)
            if (id == "fmt ") {
                val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                channels = b.getShort(2).toInt()
                rate = b.getInt(4)
                val bits = b.getShort(14).toInt()
                require(bits == 32) { "ffmpeg ra $bits bit, cần float 32" }
            }
        }
        require(rate > 0 && channels > 0) { "WAV của ffmpeg thiếu fmt" }
        return WavHeader(rate, channels)
    }

    private fun pump(
        input: InputStream,
        h: WavHeader,
        writer: Pcm16Writer,
        duration: () -> Double,
        onProgress: (Double) -> Unit,
        checkCancelled: () -> Unit,
    ) {
        val frameBytes = 4 * h.channels
        val buf = ByteArray(frameBytes * BLOCK)
        val bb = ByteBuffer.wrap(buf)
        val mono = FloatArray(BLOCK)
        val r = SincResampler(h.rate, outRate)
        val resampled = FloatArray(r.maxOutput(BLOCK))
        var shorts = ShortArray(resampled.size)
        fun write(values: FloatArray, n: Int) {
            if (shorts.size < n) shorts = ShortArray(n)
            for (i in 0 until n) shorts[i] = Pcm16.fromFloatRounded(values[i])
            writer.write(shorts, 0, n)
        }
        var carry = 0
        var frames = 0L
        while (true) {
            checkCancelled()
            val got = input.read(buf, carry, buf.size - carry)
            if (got < 0) break
            val total = carry + got
            val whole = total / frameBytes * frameBytes
            // Downmix đọc theo thứ tự byte của máy: x86-64 và ARM đều little-endian như WAV của ffmpeg.
            val n = Downmix.pcmFloat(bb, 0, whole, h.channels, mono)
            write(resampled, r.process(mono, 0, n, resampled))
            frames += n
            carry = total - whole
            if (carry > 0) System.arraycopy(buf, whole, buf, 0, carry)
            val d = duration()
            if (d > 0) onProgress((frames.toDouble() / h.rate / d).coerceAtMost(1.0))
        }
        val tail = FloatArray(r.maxFlushOutput())
        write(tail, r.flush(tail))
    }

    private companion object {
        const val BLOCK = 8192
        val DURATION = Regex("""Duration: (\d+):(\d+):(\d+(?:\.\d+)?)""")
    }
}

/**
 * Ghi một đoạn PCM ra FLAC bằng ffmpeg (`-c:a flac`, như audio_io.py: `write_audio` của desktop). Khác byte với
 * `FlacEncoder` (MediaCodec) của điện thoại nhưng giải mã ra đúng từng mẫu, vì FLAC không mất dữ liệu.
 */
class FfmpegFlacEncoder : ChunkEncoder {
    override fun encode(pcm: Pcm16File, range: SampleRange, sampleRate: Int, out: File) {
        val part = File(out.path + Pcm16Writer.PART_SUFFIX)
        val p = ProcessBuilder(
            Ffmpeg.path, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "s16le", "-ar", sampleRate.toString(), "-ac", "1", "-i", "-",
            "-c:a", "flac", "-f", "flac", part.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val (errThread, err) = Ffmpeg.drainStderr(p, 10_000)
        try {
            BufferedOutputStream(p.outputStream, 1 shl 16).use { os ->
                val shorts = ShortArray(1 shl 15)
                val bytes = ByteArray(shorts.size * 2)
                var pos = range.start
                while (pos < range.end) {
                    val n = minOf(shorts.size.toLong(), range.end - pos).toInt()
                    pcm.readShorts(pos, shorts, 0, n)
                    for (i in 0 until n) {
                        val v = shorts[i].toInt()
                        bytes[2 * i] = v.toByte()
                        bytes[2 * i + 1] = (v shr 8).toByte()
                    }
                    os.write(bytes, 0, 2 * n)
                    pos += n
                }
            }
            val code = p.waitFor()
            errThread.join(2000)
            if (code != 0) throw IOException("ffmpeg (flac) thoát mã $code: ${Ffmpeg.tail(err)}")
            Pcm16Writer.moveReplacing(part, out)
        } catch (e: Throwable) {
            p.destroyForcibly()
            part.delete()
            throw e
        }
    }
}

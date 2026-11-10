package io.github.cozycode890.guzz.core.media

import android.media.MediaCodec
import android.media.MediaFormat
import io.github.cozycode890.guzz.core.audio.ChunkEncoder
import io.github.cozycode890.guzz.core.audio.SampleRange
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16Writer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Ghi một đoạn PCM 16-bit mono ra tệp `.flac` bằng bộ mã hoá FLAC của Android (D41, D48). Thay cho audio_io.py:
 * `write_audio(..., "flac")` (ffmpeg `-c:a flac`).
 *
 * MediaMuxer không ghi được tệp `.flac` (chỉ FLAC trong MP4, mà Gemini / `_MIME` của desktop nhận `.flac`), nên tự ghi:
 * buffer codec-config (bắt đầu bằng `fLaC` + STREAMINFO) rồi các khung. Bộ mã hoá để trống tổng số mẫu và MD5 trong
 * STREAMINFO (Stage 1, F2), nên điền lại sau khi ghi xong ([FlacHeader]). Port từ `spikes/android/.../EncodeBench.kt`.
 * Vòng sync: nạp mọi buffer vào đang trống không chờ, chỉ chờ ngắn phía ra (Stage 1: chờ cả hai phía làm 10 phút mất 85 s
 * thay vì 4–6 s).
 */
class FlacEncoder : ChunkEncoder {

    override fun encode(pcm: Pcm16File, range: SampleRange, sampleRate: Int, out: File) {
        val part = File(out.path + Pcm16Writer.PART_SUFFIX)
        val md5 = MessageDigest.getInstance("MD5")
        try {
            BufferedOutputStream(FileOutputStream(part), 1 shl 16).use { os ->
                runCodec(pcm, range, sampleRate, md5, onConfig = { bytes ->
                    if (!(bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "fLaC")) os.write(FlacHeader.MAGIC)
                    os.write(bytes)
                }, onFrame = { bytes, n -> os.write(bytes, 0, n) })
            }
            RandomAccessFile(part, "rw").use { raf ->
                val head = ByteArray(FlacHeader.HEAD_BYTES)
                raf.readFully(head)
                FlacHeader.patchStreamInfo(head, range.length, md5.digest())
                raf.seek(0)
                raf.write(head)
            }
            Pcm16Writer.moveReplacing(part, out)
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    private fun runCodec(
        pcm: Pcm16File,
        range: SampleRange,
        sampleRate: Int,
        md5: MessageDigest,
        onConfig: (ByteArray) -> Unit,
        onFrame: (ByteArray, Int) -> Unit,
    ) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        val shorts = ShortArray(FRAME_SAMPLES)
        val le = ByteBuffer.allocate(FRAME_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
        var outBytes = ByteArray(1 shl 16)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pos = range.start
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                while (!inputDone) {
                    val ii = codec.dequeueInputBuffer(0)
                    if (ii < 0) break
                    val ib = codec.getInputBuffer(ii)!!.order(ByteOrder.nativeOrder())
                    val n = minOf(FRAME_SAMPLES.toLong(), range.end - pos, (ib.capacity() / 2).toLong()).toInt()
                    val pts = (pos - range.start) * 1_000_000L / sampleRate
                    if (n <= 0) {
                        codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        pcm.readShorts(pos, shorts, 0, n)
                        ib.asShortBuffer().put(shorts, 0, n)
                        codec.queueInputBuffer(ii, 0, n * 2, pts, 0)
                        // MD5 của STREAMINFO tính trên mẫu little-endian, như libFLAC.
                        le.clear()
                        le.asShortBuffer().put(shorts, 0, n)
                        md5.update(le.array(), 0, n * 2)
                        pos += n
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.size > 0) {
                        if (outBytes.size < info.size) outBytes = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.get(outBytes, 0, info.size)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            onConfig(outBytes.copyOf(info.size))
                        } else {
                            onFrame(outBytes, info.size)
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private companion object {
        /** 1 giây ở 16 kHz mỗi lần nạp (Stage 1: khung lớn nhanh hơn khung 1 024 mẫu nhiều). */
        const val FRAME_SAMPLES = 16_000
        const val OUTPUT_TIMEOUT_US = 1_000L
    }
}

/**
 * Sửa STREAMINFO ở đầu tệp FLAC: `fLaC` (4 byte) + header khối metadata (1 byte cờ/kiểu, 3 byte độ dài) + STREAMINFO
 * 34 byte. Tổng số mẫu 36 bit ở byte 13–17 của STREAMINFO, MD5 ở byte 18–33.
 */
object FlacHeader {
    val MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)
    const val HEAD_BYTES = 42
    private const val SI = 8

    /** Điền [totalSamples] và [md5] nếu bộ mã hoá để trống (0); ô đã có giá trị khác 0 thì phải trùng, không thì lỗi. */
    fun patchStreamInfo(head: ByteArray, totalSamples: Long, md5: ByteArray) {
        require(head.size >= HEAD_BYTES) { "header FLAC ngắn" }
        check(String(head, 0, 4, Charsets.US_ASCII) == "fLaC") { "tệp FLAC không bắt đầu bằng fLaC" }
        check(head[4].toInt() and 0x7F == 0) { "khối metadata đầu tiên không phải STREAMINFO" }
        check(totalSamples in 0..0xFFFFFFFFFL) { "tổng số mẫu vượt 36 bit" }
        val current = totalSamples(head)
        if (current == 0L) {
            head[SI + 13] = ((head[SI + 13].toInt() and 0xF0) or ((totalSamples shr 32).toInt() and 0x0F)).toByte()
            ByteBuffer.wrap(head, SI + 14, 4).order(ByteOrder.BIG_ENDIAN).putInt((totalSamples and 0xFFFFFFFFL).toInt())
        } else {
            check(current == totalSamples) { "STREAMINFO ghi $current mẫu, thật ra $totalSamples" }
        }
        if ((SI + 18 until SI + 34).all { head[it].toInt() == 0 }) md5.copyInto(head, SI + 18)
    }

    fun totalSamples(head: ByteArray): Long =
        ((head[SI + 13].toLong() and 0x0F) shl 32) or
            (ByteBuffer.wrap(head, SI + 14, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL)

    fun md5(head: ByteArray): ByteArray = head.copyOfRange(SI + 18, SI + 34)
}

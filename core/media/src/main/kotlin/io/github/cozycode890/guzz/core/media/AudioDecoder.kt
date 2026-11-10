package io.github.cozycode890.guzz.core.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import io.github.cozycode890.guzz.core.audio.SincResampler
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16Writer
import java.io.File
import java.util.concurrent.CountDownLatch

/** Kết quả giải mã ra `goc.pcm`. */
data class DecodeResult(
    val samples: Long,
    val sampleRate: Int,
    val inputSampleRate: Int,
    val inputChannels: Int,
    val codecName: String,
    val mime: String,
)

/** Tiến độ (0..1 theo thời điểm mẫu / thời lượng) và điểm dừng; [checkCancelled] ném lỗi để dừng. */
interface DecodeListener {
    fun onProgress(fraction: Double) {}
    fun checkCancelled() {}
}

/**
 * Giải mã một luồng âm thanh ra PCM 16-bit mono 16 kHz (`goc.pcm`), không giữ cả bài trong RAM (D43).
 * Thay cho audio_io.py: `read_audio` / `transcode` (ffmpeg `-vn -ac 1 -ar 16000 -f s16le`).
 *
 * MediaExtractor → MediaCodec **chế độ async** → trộn mono ([Downmix]) → [SincResampler] → int16 làm tròn như ffmpeg →
 * [Pcm16Writer]. Stage 1 đo: chế độ sync lúc màn hình tắt chỉ 3,6–8 lần thời gian thực, async ổn định khoảng 16 lần.
 * Mọi xử lý PCM chạy trên luồng callback của codec, nên các mẫu đến đúng thứ tự mà không cần khoá.
 */
class AudioDecoder(private val outRate: Int = 16_000) {

    fun decode(
        extractor: MediaExtractor,
        track: AudioTrackInfo,
        out: File,
        listener: DecodeListener = object : DecodeListener {},
    ): DecodeResult {
        extractor.selectTrack(track.trackIndex)
        val inFormat = extractor.getTrackFormat(track.trackIndex)
        val codec = try {
            MediaCodec.createDecoderByType(track.mime)
        } catch (e: Exception) {
            throw MediaReadException("Máy không có bộ giải mã ${track.mime}", e)
        }
        val codecName = codec.name
        val durationUs = track.durationUs
        val sink = Sink(Pcm16Writer(out), outRate, track.sampleRate, track.channelCount)
        val thread = HandlerThread("guzz-decode").apply { start() }
        val done = CountDownLatch(1)
        var error: Throwable? = null
        var inputDone = false

        fun fail(e: Throwable) {
            if (error == null) error = e
            done.countDown()
        }

        try {
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                    if (inputDone || error != null) return
                    try {
                        val n = extractor.readSampleData(c.getInputBuffer(index)!!, 0)
                        if (n < 0) {
                            c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(index, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    } catch (e: Throwable) {
                        fail(e)
                    }
                }

                override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (error != null) return
                    try {
                        listener.checkCancelled()
                        if (info.size > 0) sink.onPcm(c.getOutputBuffer(index)!!, info.offset, info.size)
                        c.releaseOutputBuffer(index, false)
                        if (durationUs > 0) listener.onProgress(minOf(1.0, info.presentationTimeUs.toDouble() / durationUs))
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done.countDown()
                    } catch (e: Throwable) {
                        fail(e)
                    }
                }

                override fun onError(c: MediaCodec, e: MediaCodec.CodecException) =
                    fail(MediaReadException("Lỗi giải mã ${track.mime}: ${e.diagnosticInfo}", e))

                override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
                    try {
                        sink.onFormat(format)
                    } catch (e: Throwable) {
                        fail(e)
                    }
                }
            }, Handler(thread.looper))
            codec.configure(inFormat, null, null, 0)
            codec.start()
            done.await()
            error?.let { throw it }
            sink.finish()
            listener.onProgress(1.0)
        } catch (e: Throwable) {
            sink.abort()
            throw e
        } finally {
            runCatching { codec.stop() }
            codec.release()
            thread.quitSafely()
        }
        if (sink.samplesOut == 0L) {
            out.delete()
            throw MediaReadException("Tệp không có dữ liệu âm thanh: ${track.describe()}")
        }
        return DecodeResult(sink.samplesOut, outRate, sink.rate, sink.channels, codecName, track.mime)
    }

    /** PCM từ codec → mono → resample → int16 → tệp. Chỉ chạy trên luồng callback của codec. */
    private class Sink(val writer: Pcm16Writer, val outRate: Int, var rate: Int, var channels: Int) {
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var resampler: SincResampler? = null
        private var mono = FloatArray(8192)
        private var resampled = FloatArray(0)
        private var shorts = ShortArray(0)
        private var started = false

        val samplesOut: Long get() = writer.samplesWritten

        fun onFormat(f: MediaFormat) {
            val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val newChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                "Bộ giải mã ra PCM kiểu $encoding, chưa hỗ trợ"
            }
            // Đổi tần số giữa chừng (hiếm, vd luồng ghép nối): xả phần còn lại của resampler cũ rồi dựng cái mới.
            if (started && newRate != rate) drainResampler()
            rate = newRate
            channels = newChannels
        }

        fun onPcm(buf: java.nio.ByteBuffer, offset: Int, size: Int) {
            started = true
            val bytesPerFrame = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val frames = size / bytesPerFrame
            if (mono.size < frames) mono = FloatArray(frames)
            val n = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                Downmix.pcmFloat(buf, offset, size, channels, mono)
            } else {
                Downmix.pcm16(buf, offset, size, channels, mono)
            }
            val r = resampler ?: SincResampler(rate, outRate).also { resampler = it }
            val cap = r.maxOutput(n)
            if (resampled.size < cap) resampled = FloatArray(cap)
            write(resampled, r.process(mono, 0, n, resampled))
        }

        private fun drainResampler() {
            val r = resampler ?: return
            val tail = FloatArray(r.maxFlushOutput())
            write(tail, r.flush(tail))
            resampler = null
        }

        private fun write(values: FloatArray, n: Int) {
            if (shorts.size < n) shorts = ShortArray(n)
            for (i in 0 until n) shorts[i] = Pcm16.fromFloatRounded(values[i])
            writer.write(shorts, 0, n)
        }

        fun finish() {
            drainResampler()
            writer.close()
        }

        fun abort() = writer.abort()
    }
}

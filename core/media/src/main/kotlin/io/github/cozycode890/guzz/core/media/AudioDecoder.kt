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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Kết quả giải mã ra `goc.pcm`. */
data class DecodeResult(
    val samples: Long,
    val sampleRate: Int,
    val inputSampleRate: Int,
    val inputChannels: Int,
    val codecName: String,
    val mime: String,
    /** Thời gian bận của từng luồng (ms), để biết bước giải mã chậm ở đâu. Các luồng chạy song song nên không cộng lại. */
    val feedMs: Long = 0,
    val downmixMs: Long = 0,
    val resampleMs: Long = 0,
    val writeMs: Long = 0,
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
 *
 * Ba luồng chạy song song (D50), nối bằng hàng đợi có giới hạn:
 * - **đọc**: MediaExtractor đọc từng mẫu nén ra bộ đệm riêng;
 * - **callback của codec**: chỉ chép mẫu nén vào buffer vào của codec, chép PCM ra khỏi buffer ra rồi trả buffer ngay;
 * - **xử lý**: trộn kênh, resample, ghi tệp, theo đúng thứ tự PCM ra.
 *
 * Bản đầu chạy cả đọc lẫn xử lý trên luồng callback: lúc tắt màn hình, bài 2 giờ mất 16,6 phút, riêng đọc / nạp chiếm
 * 57% thời gian bước giải mã, và codec phải chờ luồng callback rảnh mới nhận mẫu mới.
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
        val run = Run(extractor, codec, track, Sink(Pcm16Writer(out), outRate, track.sampleRate, track.channelCount), listener)
        try {
            run.start(inFormat)
            run.await()
            listener.onProgress(1.0)
        } catch (e: Throwable) {
            // Dừng hẳn các luồng trước, để luồng xử lý không còn ghi vào tệp đang bị xoá.
            run.shutdown()
            run.sink.abort()
            throw e
        } finally {
            run.shutdown()
            runCatching { codec.stop() }
            codec.release()
        }
        val sink = run.sink
        if (sink.samplesOut == 0L) {
            out.delete()
            throw MediaReadException("Tệp không có dữ liệu âm thanh: ${track.describe()}")
        }
        return DecodeResult(
            sink.samplesOut, outRate, sink.rate, sink.channels, codecName, track.mime,
            run.feedNs / 1_000_000, sink.downmixNs / 1_000_000, sink.resampleNs / 1_000_000, sink.writeNs / 1_000_000,
        )
    }

    /** Một mẫu nén đọc sẵn; `size < 0` là hết luồng. */
    private class Packet(var data: ByteArray, var size: Int = 0, var ptsUs: Long = 0)

    /** Việc cho luồng xử lý, giữ đúng thứ tự codec trả ra. */
    private sealed class Work {
        class Pcm(val data: ByteArray, val size: Int, val ptsUs: Long) : Work()
        class Format(val format: MediaFormat) : Work()
        object End : Work()
    }

    private class Run(
        val extractor: MediaExtractor,
        val codec: MediaCodec,
        val track: AudioTrackInfo,
        val sink: Sink,
        val listener: DecodeListener,
    ) {
        private val packets = ArrayBlockingQueue<Packet>(QUEUE)
        private val freePackets = ArrayBlockingQueue<Packet>(QUEUE + 4)
        private val work = ArrayBlockingQueue<Work>(QUEUE)
        private val freePcm = ArrayBlockingQueue<ByteArray>(QUEUE + 4)
        private val callbackThread = HandlerThread("guzz-decode-cb").apply { start() }
        private val handler = Handler(callbackThread.looper)
        private val done = CountDownLatch(1)

        @Volatile private var error: Throwable? = null
        @Volatile private var stopped = false
        @Volatile var feedNs = 0L
            private set

        // Chỉ dùng trên luồng callback.
        private val freeInputs = ArrayDeque<Int>()
        private var inputDone = false

        private var reader: Thread? = null
        private var processor: Thread? = null

        fun fail(e: Throwable) {
            if (error == null) error = e
            stopped = true
            done.countDown()
        }

        fun start(inFormat: MediaFormat) {
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                    freeInputs.add(index)
                    feedInputs()
                }

                override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (stopped) return
                    try {
                        if (info.size > 0) {
                            // Chép ra rồi trả buffer ngay, để codec không phải chờ luồng xử lý. Mảng lấy lại từ
                            // [freePcm]: bài 2 giờ có khoảng 340 000 buffer ra, cấp mới mỗi lần thì GC phải dọn hơn 1 GB.
                            val bytes = freePcm.poll()?.takeIf { it.size >= info.size } ?: ByteArray(info.size)
                            val ob = c.getOutputBuffer(index)!!
                            ob.position(info.offset)
                            ob.get(bytes, 0, info.size)
                            c.releaseOutputBuffer(index, false)
                            put(work, Work.Pcm(bytes, info.size, info.presentationTimeUs))
                        } else {
                            c.releaseOutputBuffer(index, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) put(work, Work.End)
                    } catch (e: Throwable) {
                        fail(e)
                    }
                }

                override fun onError(c: MediaCodec, e: MediaCodec.CodecException) =
                    fail(MediaReadException("Lỗi giải mã ${track.mime}: ${e.diagnosticInfo}", e))

                override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
                    try {
                        put(work, Work.Format(format))
                    } catch (e: Throwable) {
                        fail(e)
                    }
                }
            }, handler)
            codec.configure(inFormat, null, null, 0)
            reader = thread(name = "guzz-decode-read") { readLoop(inFormat) }
            processor = thread(name = "guzz-decode-pcm") { processLoop() }
            codec.start()
        }

        /** Luồng đọc: MediaExtractor → [packets]. Mỗi khi có mẫu mới thì nhờ luồng callback nạp vào codec. */
        private fun readLoop(inFormat: MediaFormat) {
            try {
                val maxSize = if (inFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    inFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    64 * 1024
                }
                var buffer = ByteBuffer.allocateDirect(maxOf(maxSize, 4096))
                while (!stopped) {
                    val t = System.nanoTime()
                    var n = extractor.readSampleData(buffer, 0)
                    if (n == buffer.capacity()) {
                        // Có thể bị cắt cụt: đọc lại với bộ đệm lớn hơn.
                        buffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                        n = extractor.readSampleData(buffer, 0)
                    }
                    val p = freePackets.poll() ?: Packet(ByteArray(maxOf(n, 0)))
                    if (n < 0) {
                        p.size = -1
                    } else {
                        if (p.data.size < n) p.data = ByteArray(n)
                        buffer.position(0).limit(n)
                        buffer.get(p.data, 0, n)
                        buffer.clear()
                        p.size = n
                        p.ptsUs = extractor.sampleTime
                        extractor.advance()
                    }
                    feedNs += System.nanoTime() - t
                    put(packets, p)
                    handler.post { feedInputs() }
                    if (n < 0) break
                }
            } catch (e: Throwable) {
                fail(e)
            }
        }

        /** Trên luồng callback: ghép buffer vào đang trống với mẫu đã đọc sẵn. */
        private fun feedInputs() {
            if (stopped || inputDone) return
            try {
                while (freeInputs.isNotEmpty()) {
                    val p = packets.poll() ?: return
                    val index = freeInputs.poll()!!
                    if (p.size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                        return
                    }
                    val ib = codec.getInputBuffer(index)!!
                    ib.clear()
                    ib.put(p.data, 0, p.size)
                    codec.queueInputBuffer(index, 0, p.size, p.ptsUs, 0)
                    freePackets.offer(p)
                }
            } catch (e: Throwable) {
                fail(e)
            }
        }

        /** Luồng xử lý: PCM theo đúng thứ tự → [sink]. */
        private fun processLoop() {
            val durationUs = track.durationUs
            try {
                while (!stopped) {
                    when (val w = work.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue) {
                        is Work.Format -> sink.onFormat(w.format)
                        is Work.Pcm -> {
                            listener.checkCancelled()
                            sink.onPcm(ByteBuffer.wrap(w.data), 0, w.size)
                            freePcm.offer(w.data)
                            if (durationUs > 0) listener.onProgress(minOf(1.0, w.ptsUs.toDouble() / durationUs))
                        }
                        Work.End -> {
                            sink.finish()
                            done.countDown()
                            return
                        }
                    }
                }
            } catch (e: Throwable) {
                fail(e)
            }
        }

        /** Đặt vào hàng đợi; đầy thì chờ (đây là cách hãm luồng nhanh hơn), dừng thì bỏ. */
        private fun <T> put(queue: ArrayBlockingQueue<T>, item: T) {
            while (!stopped) {
                if (queue.offer(item, POLL_MS, TimeUnit.MILLISECONDS)) return
            }
        }

        fun await() {
            done.await()
            error?.let { throw it }
        }

        fun shutdown() {
            stopped = true
            reader?.join(2_000)
            processor?.join(2_000)
            callbackThread.quitSafely()
        }

        private companion object {
            const val QUEUE = 64
            const val POLL_MS = 50L
        }
    }

    /** PCM từ codec → mono → resample → int16 → tệp. Chỉ chạy trên luồng xử lý. */
    private class Sink(val writer: Pcm16Writer, val outRate: Int, var rate: Int, var channels: Int) {
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var resampler: SincResampler? = null
        private var mono = FloatArray(8192)
        private var resampled = FloatArray(0)
        private var shorts = ShortArray(0)
        private var started = false
        var downmixNs = 0L
        var resampleNs = 0L
        var writeNs = 0L

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

        fun onPcm(buf: ByteBuffer, offset: Int, size: Int) {
            started = true
            val bytesPerFrame = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val frames = size / bytesPerFrame
            if (mono.size < frames) mono = FloatArray(frames)
            val t0 = System.nanoTime()
            val nativeBuf = buf.order(ByteOrder.nativeOrder())
            val n = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                Downmix.pcmFloat(nativeBuf, offset, size, channels, mono)
            } else {
                Downmix.pcm16(nativeBuf, offset, size, channels, mono)
            }
            val r = resampler ?: SincResampler(rate, outRate).also { resampler = it }
            val cap = r.maxOutput(n)
            if (resampled.size < cap) resampled = FloatArray(cap)
            val t1 = System.nanoTime()
            val k = r.process(mono, 0, n, resampled)
            val t2 = System.nanoTime()
            write(resampled, k)
            downmixNs += t1 - t0
            resampleNs += t2 - t1
            writeNs += System.nanoTime() - t2
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

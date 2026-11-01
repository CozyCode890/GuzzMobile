package io.github.cozycode890.guzz.spike.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.cozycode890.guzz.spike.Paths
import io.github.cozycode890.guzz.spike.bench.Bench
import io.github.cozycode890.guzz.spike.bench.BenchContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bench `decode` (E3): MediaExtractor chọn luồng âm thanh đầu tiên → MediaCodec → PCM → trộn mono → resample về 16 kHz
 * → ghi f32 theo từng khối (`results/decode/<tệp kèm đuôi>_<resampler>.f32`), không giữ cả bài.
 *
 * Tham số: `input`, `resampler` (sinc | sonic), `mode` (full | decode: chỉ giải mã, bỏ PCM), `zero_crossings` (sinc),
 * `api` (sync: dequeue/queue trên luồng đo | async: `setCallback` trên HandlerThread), `max_seconds` (> 0: dừng sau
 * ngần này giây âm thanh, để đo nhanh từng yếu tố).
 */
object DecodeBench : Bench {
    private const val OUT_RATE = 16000
    private const val TIMEOUT_US = 1000L

    override fun run(b: BenchContext): JSONObject {
        val input = Paths.resolve(Paths.input(b.ctx), requireNotNull(b.string("input")) { "thiếu input" })
        val resamplerName = b.string("resampler", "sinc")!!
        val mode = b.string("mode", "full")!!
        val api = b.string("api", "sync")!!
        val zeroCrossings = b.int("zero_crossings", 32)
        val maxSeconds = b.int("max_seconds", 0)
        require(input.isFile) { "không có $input" }

        val ex = MediaExtractor()
        ex.setDataSource(input.path)
        val track = (0 until ex.trackCount).first {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
        }
        ex.selectTrack(track)
        val inFormat = ex.getTrackFormat(track)
        val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        val codecName = codec.name
        val outFile = File(File(Paths.results(b.ctx), "decode").apply { mkdirs() }, "${input.name}_$resamplerName.f32")
        val sink = PcmSink(if (mode == "full") F32Writer(outFile) else null, resamplerName, zeroCrossings,
            inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        val limitUs = if (maxSeconds > 0) maxSeconds * 1_000_000L else Long.MAX_VALUE
        var lastProgress = 0L
        val progress = {
            val now = SystemClock.elapsedRealtime()
            if (now - lastProgress > 2000) {
                lastProgress = now
                b.progress("decode ${input.name}: ${sink.frames / sink.sr} s")
            }
        }

        /** Đưa mẫu nén kế tiếp vào buffer [i]; trả về true nếu vừa gửi EOS. */
        fun feed(c: MediaCodec, i: Int): Boolean {
            val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
            if (n < 0 || ex.sampleTime >= limitUs) {
                c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return true
            }
            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
            ex.advance()
            return false
        }

        val t0 = SystemClock.elapsedRealtime()
        try {
            if (api == "async") {
                val ht = HandlerThread("decode-cb").apply { start() }
                val done = CountDownLatch(1)
                var error: Throwable? = null
                var inEos = false
                codec.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                        if (!inEos) inEos = feed(c, index)
                    }

                    override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        try {
                            if (info.size > 0) sink.onPcm(c.getOutputBuffer(index)!!, info)
                            c.releaseOutputBuffer(index, false)
                            progress()
                        } catch (e: Throwable) {
                            error = e
                            done.countDown()
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done.countDown()
                    }

                    override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                        error = e
                        done.countDown()
                    }

                    override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) = sink.onFormat(format)
                }, Handler(ht.looper))
                codec.configure(inFormat, null, null, 0)
                codec.start()
                done.await(6, TimeUnit.HOURS)
                ht.quitSafely()
                error?.let { throw it }
            } else {
                codec.configure(inFormat, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inEos = false
                var outEos = false
                while (!outEos) {
                    // Nạp mọi buffer đầu vào đang trống, không chờ; chỉ chờ (ngắn) ở phía đầu ra. Chờ ở cả hai phía cho
                    // từng khung AAC 21 ms thì thời gian đo là thời gian chờ chứ không phải thời gian giải mã.
                    while (!inEos) {
                        val ii = codec.dequeueInputBuffer(0)
                        if (ii < 0) break
                        inEos = feed(codec, ii)
                    }
                    val oi = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        sink.onFormat(codec.outputFormat)
                    } else if (oi >= 0) {
                        if (info.size > 0) sink.onPcm(codec.getOutputBuffer(oi)!!, info)
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                        progress()
                    }
                }
            }
            sink.finish()
        } finally {
            sink.close()
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        val elapsed = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val mediaSeconds = sink.frames.toDouble() / sink.sr
        return JSONObject()
            .put("tep", input.name)
            .put("mime", mime)
            .put("bo_giai_ma", codecName)
            .put("api", api)
            .put("tan_so_goc", sink.sr)
            .put("so_kenh_goc", sink.channels)
            .put("che_do", mode)
            .put("resampler", resamplerName)
            .put("zero_crossings", zeroCrossings)
            .put("giay_am_thanh", mediaSeconds)
            .put("giay_xu_ly", elapsed)
            .put("ms_resample_va_ghi", sink.resampleMs)
            .put("x_thoi_gian_thuc", mediaSeconds / elapsed)
            .put("mau_ra", sink.samplesOut)
            .put("giay_ra", sink.samplesOut / OUT_RATE.toDouble())
            .put("tep_ra", if (mode == "full") outFile.path else JSONObject.NULL)
            .put("ram", b.mem.toJson())
    }

    /** PCM 16-bit từ bộ giải mã → mono → resample → f32. `writer` null (mode=decode) thì chỉ đếm khung. */
    private class PcmSink(
        val writer: F32Writer?, val resampler: String, val zeroCrossings: Int, var sr: Int, var channels: Int,
    ) {
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var frames = 0L
        var resampleMs = 0L
        private var sinc: SincResampler? = null
        private var sonic: SonicResampler? = null
        private var mono = FloatArray(0)
        private var mono16 = ShortArray(0)
        val samplesOut get() = writer?.samples ?: 0L

        fun onFormat(f: MediaFormat) {
            sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
        }

        fun onPcm(ob: ByteBuffer, info: MediaCodec.BufferInfo) {
            require(encoding == AudioFormat.ENCODING_PCM_16BIT) { "PCM encoding $encoding chưa hỗ trợ" }
            ob.position(info.offset)
            ob.limit(info.offset + info.size)
            val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
            val n = sb.remaining() / channels
            frames += n
            val w = writer ?: return
            val tr = SystemClock.elapsedRealtime()
            if (resampler == "sonic") {
                if (mono16.size < n) mono16 = ShortArray(n)
                for (i in 0 until n) {
                    var s = 0
                    for (c in 0 until channels) s += sb.get()
                    mono16[i] = (s / channels).toShort()
                }
                if (sr == OUT_RATE) {
                    w.write(FloatArray(n) { mono16[it] / 32768f })
                } else {
                    w.write((sonic ?: SonicResampler(sr, OUT_RATE).also { sonic = it }).process(mono16, n))
                }
            } else {
                if (mono.size < n) mono = FloatArray(n)
                for (i in 0 until n) {
                    var s = 0f
                    for (c in 0 until channels) s += sb.get()
                    mono[i] = s / channels / 32768f
                }
                if (sr == OUT_RATE) {
                    w.write(mono, n)
                } else {
                    w.write((sinc ?: SincResampler(sr, OUT_RATE, zeroCrossings).also { sinc = it }).process(mono, n))
                }
            }
            resampleMs += SystemClock.elapsedRealtime() - tr
        }

        fun finish() {
            val w = writer ?: return
            sinc?.let { w.write(it.flush()) }
            sonic?.let { w.write(it.flush()) }
        }

        fun close() {
            writer?.close()
        }
    }
}

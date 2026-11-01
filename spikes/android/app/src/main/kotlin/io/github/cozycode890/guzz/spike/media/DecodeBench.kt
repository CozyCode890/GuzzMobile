package io.github.cozycode890.guzz.spike.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import io.github.cozycode890.guzz.spike.Paths
import io.github.cozycode890.guzz.spike.bench.Bench
import io.github.cozycode890.guzz.spike.bench.BenchContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder

/**
 * Bench `decode` (E3): MediaExtractor chọn luồng âm thanh đầu tiên → MediaCodec → PCM → trộn mono → resample về 16 kHz
 * → ghi f32 theo từng khối (`results/decode/<tệp>_<resampler>.f32`), không giữ cả bài.
 *
 * Tham số: `input`, `resampler` (sinc | sonic), `mode` (full | decode: chỉ giải mã, bỏ PCM), `zero_crossings` (sinc).
 */
object DecodeBench : Bench {
    private const val OUT_RATE = 16000
    private const val TIMEOUT_US = 5000L

    override fun run(b: BenchContext): JSONObject {
        val input = Paths.resolve(Paths.input(b.ctx), requireNotNull(b.string("input")) { "thiếu input" })
        val resamplerName = b.string("resampler", "sinc")!!
        val mode = b.string("mode", "full")!!
        val zeroCrossings = b.int("zero_crossings", 32)
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
        codec.configure(inFormat, null, null, 0)
        codec.start()

        var sr = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val outFile = File(File(Paths.results(b.ctx), "decode").apply { mkdirs() },
            "${input.nameWithoutExtension}_$resamplerName.f32")
        val writer = if (mode == "full") F32Writer(outFile) else null
        var sinc: SincResampler? = null
        var sonic: SonicResampler? = null
        var frames = 0L
        var mono = FloatArray(0)
        var mono16 = ShortArray(0)
        var resampleMs = 0L

        val info = MediaCodec.BufferInfo()
        var inEos = false
        var outEos = false
        val t0 = SystemClock.elapsedRealtime()
        var lastProgress = 0L
        try {
            while (!outEos) {
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.size > 0) {
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT) { "PCM encoding $encoding chưa hỗ trợ" }
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val n = sb.remaining() / channels
                        frames += n
                        if (writer != null) {
                            val tr = SystemClock.elapsedRealtime()
                            if (resamplerName == "sonic") {
                                if (mono16.size < n) mono16 = ShortArray(n)
                                for (i in 0 until n) {
                                    var s = 0
                                    for (c in 0 until channels) s += sb.get()
                                    mono16[i] = (s / channels).toShort()
                                }
                                if (sr == OUT_RATE) {
                                    writer.write(FloatArray(n) { mono16[it] / 32768f })
                                } else {
                                    val s = sonic ?: SonicResampler(sr, OUT_RATE).also { sonic = it }
                                    writer.write(s.process(mono16, n))
                                }
                            } else {
                                if (mono.size < n) mono = FloatArray(n)
                                for (i in 0 until n) {
                                    var s = 0f
                                    for (c in 0 until channels) s += sb.get()
                                    mono[i] = s / channels / 32768f
                                }
                                if (sr == OUT_RATE) {
                                    writer.write(mono, n)
                                } else {
                                    val s = sinc ?: SincResampler(sr, OUT_RATE, zeroCrossings).also { sinc = it }
                                    writer.write(s.process(mono, n))
                                }
                            }
                            resampleMs += SystemClock.elapsedRealtime() - tr
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgress > 2000) {
                        lastProgress = now
                        b.progress("decode ${input.name}: ${frames / sr} s")
                    }
                }
            }
            writer?.let { w ->
                sinc?.let { w.write(it.flush()) }
                sonic?.let { w.write(it.flush()) }
            }
        } finally {
            writer?.close()
            codec.stop()
            codec.release()
            ex.release()
        }
        val elapsed = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val mediaSeconds = frames.toDouble() / sr
        return JSONObject()
            .put("tep", input.name)
            .put("mime", mime)
            .put("bo_giai_ma", codecName)
            .put("tan_so_goc", sr)
            .put("so_kenh_goc", channels)
            .put("che_do", mode)
            .put("resampler", resamplerName)
            .put("zero_crossings", zeroCrossings)
            .put("giay_am_thanh", mediaSeconds)
            .put("giay_xu_ly", elapsed)
            .put("ms_resample_va_ghi", resampleMs)
            .put("x_thoi_gian_thuc", mediaSeconds / elapsed)
            .put("mau_ra", writer?.samples ?: 0)
            .put("giay_ra", (writer?.samples ?: 0) / OUT_RATE.toDouble())
            .put("tep_ra", writer?.let { outFile.path })
            .put("ram", b.mem.toJson())
    }
}

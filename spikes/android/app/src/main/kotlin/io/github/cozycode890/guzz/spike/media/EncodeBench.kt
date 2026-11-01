package io.github.cozycode890.guzz.spike.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import io.github.cozycode890.guzz.spike.Paths
import io.github.cozycode890.guzz.spike.bench.Bench
import io.github.cozycode890.guzz.spike.bench.BenchContext
import io.github.cozycode890.guzz.spike.diarize.F32File
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Bench `encode` (F2): mã hoá một đoạn f32 16 kHz mono (mặc định `F1.f32`, 10 phút) ra các định dạng đoạn gửi Gemini
 * (D36), ghi vào `results/formats/`. Tham số `variants`, cách nhau dấu phẩy: flac, flac8, opus24, opus32, aac48, aac64,
 * wav. Mỗi biến thể: cỡ tệp, thời gian mã hoá, tên bộ mã hoá.
 *
 * FLAC: MediaMuxer không ghi được FLAC, nên tự ghi tệp `.flac` = `fLaC` + metadata từ buffer codec-config + các khung;
 * rồi điền tổng số mẫu và MD5 vào STREAMINFO nếu bộ mã hoá để trống.
 */
object EncodeBench : Bench {
    private const val SR = 16000
    private const val TIMEOUT_US = 1000L
    // Mẫu mỗi buffer đầu vào (1 s). Bộ mã hoá tự chia khung (Opus 20 ms, AAC 1 024 mẫu).
    private const val FRAME = 16000

    override fun run(b: BenchContext): JSONObject {
        val input = Paths.resolve(Paths.input(b.ctx), b.string("input", "F1.f32")!!)
        val variants = b.string("variants", "flac,flac8,opus24,opus32,aac48,aac64,wav")!!.split(',').map { it.trim() }
        val pcm = F32File.read(input).let { f -> ShortArray(f.size) { quantize(f[it]) } }
        val outDir = File(Paths.results(b.ctx), "formats").apply { mkdirs() }
        val results = JSONArray()
        for (v in variants) {
            b.progress("encode $v")
            val t0 = SystemClock.elapsedRealtime()
            val r = when {
                v == "wav" -> writeWav(pcm, File(outDir, "${input.nameWithoutExtension}_wav.wav"))
                // F1 thấy MediaMuxer MPEG_4 nhận track FLAC trên API 36: thử ghi thật FLAC trong MP4.
                v == "flacmp4" -> encodeMuxed(pcm, MediaFormat.MIMETYPE_AUDIO_FLAC, 0,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, File(outDir, "${input.nameWithoutExtension}_flacmp4.mp4"))
                v.startsWith("flac") -> encodeFlac(pcm, File(outDir, "${input.nameWithoutExtension}_$v.flac"),
                    v.removePrefix("flac").toIntOrNull())
                v.startsWith("opus") -> encodeMuxed(pcm, MediaFormat.MIMETYPE_AUDIO_OPUS,
                    v.removePrefix("opus").toInt() * 1000, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
                    File(outDir, "${input.nameWithoutExtension}_$v.ogg"))
                v.startsWith("aac") -> encodeMuxed(pcm, MediaFormat.MIMETYPE_AUDIO_AAC,
                    v.removePrefix("aac").toInt() * 1000, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                    File(outDir, "${input.nameWithoutExtension}_$v.m4a"))
                else -> JSONObject().put("loi", "biến thể không biết")
            }
            r.put("bien_the", v).put("ms_ma_hoa", SystemClock.elapsedRealtime() - t0)
                .put("ram_tich_luy", b.mem.toJson())
            results.put(r)
            b.log.write("su_kien" to "xong_bien_the", "ket_qua" to r)
        }
        return JSONObject().put("dau_vao", input.name).put("giay", pcm.size / SR.toDouble()).put("bien_the", results)
    }

    private fun quantize(x: Float): Short = (x * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()

    private fun writeWav(pcm: ShortArray, out: File): JSONObject {
        val data = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SR).putInt(SR * 2).putShort(2)
            .putShort(16)
        bb.put("data".toByteArray()).putInt(data)
        bb.asShortBuffer().put(pcm)
        out.writeBytes(bb.array())
        return JSONObject().put("tep", out.path).put("bytes", out.length())
    }

    /** Đẩy toàn bộ PCM qua một bộ mã hoá; [onConfig] nhận buffer codec-config, [onFormat] format ra, [onData] khung. */
    private fun runEncoder(
        pcm: ShortArray, format: MediaFormat,
        onConfig: (ByteBuffer) -> Unit, onFormat: (MediaFormat) -> Unit, onData: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    ): String {
        val codec = MediaCodec.createEncoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        val name = codec.name
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var fed = 0
        var inEos = false
        var outEos = false
        try {
            while (!outEos) {
                // Nạp mọi buffer đầu vào đang trống, không chờ; chỉ chờ (ngắn) ở phía đầu ra (xem DecodeBench).
                while (!inEos) {
                    val ii = codec.dequeueInputBuffer(0)
                    if (ii < 0) break
                    val ib = codec.getInputBuffer(ii)!!.order(ByteOrder.nativeOrder())
                    val n = minOf(FRAME, pcm.size - fed, ib.capacity() / 2)
                    val pts = fed * 1_000_000L / SR
                    if (n <= 0) {
                        codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inEos = true
                    } else {
                        ib.asShortBuffer().put(pcm, fed, n)
                        codec.queueInputBuffer(ii, 0, n * 2, pts, 0)
                        fed += n
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onFormat(codec.outputFormat)
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        onConfig(ob)
                    } else if (info.size > 0) {
                        onData(ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return name
    }

    private fun encodeMuxed(pcm: ShortArray, mime: String, bitrate: Int, outFormat: Int, out: File): JSONObject {
        val fmt = MediaFormat.createAudioFormat(mime, SR, 1).apply {
            if (bitrate > 0) setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2 * 4)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
        }
        val muxer = MediaMuxer(out.path, outFormat)
        var track = -1
        var outFmt: MediaFormat? = null
        val name = try {
            runEncoder(pcm, fmt, onConfig = {}, onFormat = {
                outFmt = it
                track = muxer.addTrack(it)
                muxer.start()
            }, onData = { buf, info -> muxer.writeSampleData(track, buf, info) })
        } finally {
            runCatching { muxer.stop() }
            muxer.release()
        }
        return JSONObject().put("tep", out.path).put("bytes", out.length()).put("bo_ma_hoa", name)
            .put("bitrate", bitrate).put("format_ra", outFmt?.toString())
    }

    private fun encodeFlac(pcm: ShortArray, out: File, level: Int?): JSONObject {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, SR, 1).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2 * 4)
            level?.let { setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, it) }
        }
        var configBytes = 0
        var configHex = ""
        val name = out.outputStream().buffered().use { os ->
            var wroteHeader = false
            runEncoder(pcm, fmt, onConfig = { buf ->
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                configBytes += bytes.size
                configHex = bytes.take(8).joinToString("") { "%02x".format(it) }
                // Buffer codec-config của bộ mã hoá FLAC là phần đầu của luồng (thường có sẵn "fLaC").
                if (!wroteHeader && !(bytes.size >= 4 && String(bytes, 0, 4) == "fLaC")) os.write("fLaC".toByteArray())
                wroteHeader = true
                os.write(bytes)
            }, onFormat = {}, onData = { buf, _ ->
                os.write(ByteArray(buf.remaining()).also { buf.get(it) })
            })
        }
        val patched = patchStreamInfo(out, pcm)
        return JSONObject().put("tep", out.path).put("bytes", out.length()).put("bo_ma_hoa", name)
            .put("muc_nen", level ?: JSONObject.NULL).put("codec_config_bytes", configBytes)
            .put("codec_config_dau", configHex).put("streaminfo", patched)
    }

    /** Điền tổng số mẫu và MD5 (PCM 16-bit LE, như libFLAC) vào STREAMINFO nếu đang là 0. */
    private fun patchStreamInfo(file: File, pcm: ShortArray): JSONObject {
        RandomAccessFile(file, "rw").use { raf ->
            val head = ByteArray(42)
            raf.readFully(head)
            if (String(head, 0, 4) != "fLaC" || (head[4].toInt() and 0x7F) != 0) {
                return JSONObject().put("loi", "không có STREAMINFO ở đầu tệp")
            }
            val si = 8
            val total = ((head[si + 13].toLong() and 0x0F) shl 32) or
                (ByteBuffer.wrap(head, si + 14, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL)
            val md5Zero = (si + 18 until si + 34).all { head[it].toInt() == 0 }
            val o = JSONObject().put("tong_mau_bo_ma_hoa_ghi", total).put("md5_trong", md5Zero)
            if (total == 0L) {
                val n = pcm.size.toLong()
                head[si + 13] = ((head[si + 13].toInt() and 0xF0) or ((n shr 32).toInt() and 0x0F)).toByte()
                ByteBuffer.wrap(head, si + 14, 4).order(ByteOrder.BIG_ENDIAN).putInt((n and 0xFFFFFFFFL).toInt())
                o.put("da_dien_tong_mau", n)
            }
            if (md5Zero) {
                val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                bb.asShortBuffer().put(pcm)
                MessageDigest.getInstance("MD5").digest(bb.array()).copyInto(head, si + 18)
                o.put("da_dien_md5", true)
            }
            raf.seek(0)
            raf.write(head)
            return o
        }
    }
}

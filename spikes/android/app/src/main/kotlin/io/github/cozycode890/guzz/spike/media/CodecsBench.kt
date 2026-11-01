package io.github.cozycode890.guzz.spike.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import io.github.cozycode890.guzz.spike.Paths
import io.github.cozycode890.guzz.spike.bench.Bench
import io.github.cozycode890.guzz.spike.bench.BenchContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bench `codecs` (F1): bộ mã hoá / giải mã âm thanh có trên máy (`MediaCodecList(REGULAR_CODECS)`) và MediaMuxer nhận
 * track nào ở từng định dạng ra. Ghi `results/codecs.json`.
 */
object CodecsBench : Bench {
    private val ENCODE_MIMES = listOf(MediaFormat.MIMETYPE_AUDIO_FLAC, MediaFormat.MIMETYPE_AUDIO_OPUS,
        MediaFormat.MIMETYPE_AUDIO_AAC)
    private val DECODE_MIMES = listOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_MPEG,
        MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_VORBIS, MediaFormat.MIMETYPE_AUDIO_FLAC,
        MediaFormat.MIMETYPE_AUDIO_RAW)

    override fun run(b: BenchContext): JSONObject {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val encoders = JSONArray()
        val decoders = JSONArray()
        for (ci in infos) {
            for (type in ci.supportedTypes) {
                val t = type.lowercase()
                if (ci.isEncoder && t in ENCODE_MIMES) encoders.put(describe(ci, type))
                if (!ci.isEncoder && t in DECODE_MIMES) decoders.put(describe(ci, type))
            }
        }
        val result = JSONObject()
            .put("ma_hoa", encoders)
            .put("giai_ma", decoders)
            .put("muxer", muxers(b))
        File(Paths.results(b.ctx), "codecs.json").writeText(result.toString(1))
        return JSONObject().put("so_bo_ma_hoa", encoders.length()).put("so_bo_giai_ma", decoders.length())
    }

    private fun describe(ci: MediaCodecInfo, type: String): JSONObject {
        val caps = ci.getCapabilitiesForType(type)
        val o = JSONObject().put("ten", ci.name).put("mime", type)
        if (Build.VERSION.SDK_INT >= 29) {
            o.put("chi_phan_mem", ci.isSoftwareOnly).put("tang_toc_phan_cung", ci.isHardwareAccelerated)
                .put("cua_hang", ci.isVendor).put("ten_goc", ci.canonicalName)
        }
        caps.audioCapabilities?.let { a ->
            o.put("bitrate", "${a.bitrateRange.lower}–${a.bitrateRange.upper}")
            o.put("kenh_toi_da", a.maxInputChannelCount)
            a.supportedSampleRates?.let { o.put("tan_so", JSONArray(it.toList())) }
                ?: o.put("dai_tan_so", JSONArray(a.supportedSampleRateRanges.map { "${it.lower}–${it.upper}" }))
        }
        caps.encoderCapabilities?.let { e ->
            o.put("complexity", "${e.complexityRange.lower}–${e.complexityRange.upper}")
            if (Build.VERSION.SDK_INT >= 28) o.put("quality", "${e.qualityRange.lower}–${e.qualityRange.upper}")
            o.put("bitrate_mode", JSONObject()
                .put("cq", e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ))
                .put("vbr", e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                .put("cbr", e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)))
        }
        return o
    }

    /** Thử addTrack + start + stop (không ghi mẫu nào) cho từng cặp định dạng ra × loại track. */
    private fun muxers(b: BenchContext): JSONArray {
        val outputs = mutableListOf("MPEG_4" to MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            "WEBM" to MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "3GPP" to MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP)
        if (Build.VERSION.SDK_INT >= 29) outputs.add("OGG" to MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        val tracks = listOf(MediaFormat.MIMETYPE_AUDIO_FLAC, MediaFormat.MIMETYPE_AUDIO_OPUS,
            MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_VORBIS)
        val tmp = File(b.ctx.cacheDir, "mux.tmp")
        val r = JSONArray()
        for ((name, fmt) in outputs) {
            for (mime in tracks) {
                val res = try {
                    val m = MediaMuxer(tmp.path, fmt)
                    try {
                        m.addTrack(MediaFormat.createAudioFormat(mime, 16000, 1))
                        "addTrack ok"
                    } finally {
                        runCatching { m.release() }
                    }
                } catch (e: Exception) {
                    e.javaClass.simpleName + ": " + e.message
                }
                r.put(JSONObject().put("dinh_dang", name).put("track", mime).put("ket_qua", res))
            }
        }
        tmp.delete()
        return r
    }
}

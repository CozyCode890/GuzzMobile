package io.github.cozycode890.guzz.core.media

import android.media.MediaExtractor
import android.media.MediaFormat

/**
 * Một luồng âm thanh trong tệp. [index] là thứ tự TRONG CÁC LUỒNG ÂM THANH (0, 1, 2…), dùng cho cài đặt
 * `[VIDEO] audio_track`; [trackIndex] là chỉ số thật của MediaExtractor (tính cả luồng hình).
 * Port của video_io.py: `AudioTrack`.
 */
data class AudioTrackInfo(
    val index: Int,
    val trackIndex: Int,
    val mime: String,
    val language: String = "",
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    /** Thời lượng khai trong container (µs); 0 nếu không có. */
    val durationUs: Long = 0,
) {
    /** Như `AudioTrack.describe` của desktop: "#0 · vie · audio/mp4a-latm · 48000 Hz, 2 kênh". */
    fun describe(): String = buildList {
        add("#$index")
        if (language.isNotBlank() && language != "und") add(language)
        add(mime)
        if (sampleRate > 0) add("$sampleRate Hz, $channelCount kênh")
    }.joinToString(" · ")
}

/** Tệp không mở / không đọc được. Không được lẫn thành "không có tiếng" rồi bị bỏ qua âm thầm (như desktop). */
class MediaReadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Tệp đọc được nhưng không có luồng âm thanh nào (video câm). Port của transcribe.py: `NoAudioError`; bỏ qua hay báo
 * lỗi (`[VIDEO] skip_silent`) là việc của Stage 4.
 */
class NoAudioException(message: String) : RuntimeException(message)

object AudioTracks {
    /** Giá trị `[VIDEO] audio_track` nghĩa là "luồng đầu tiên". */
    const val AUTO = "auto"

    /** Các luồng âm thanh theo thứ tự của container. Danh sách rỗng = tệp mở được nhưng không có tiếng. */
    fun list(extractor: MediaExtractor): List<AudioTrackInfo> {
        val result = ArrayList<AudioTrackInfo>()
        for (t in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(t)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            result += AudioTrackInfo(
                index = result.size,
                trackIndex = t,
                mime = mime,
                language = f.stringOr(MediaFormat.KEY_LANGUAGE),
                sampleRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = f.intOr(MediaFormat.KEY_CHANNEL_COUNT),
                durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L,
            )
        }
        return result
    }

    /**
     * Chỉ số luồng sẽ dùng. [request]: `auto` / rỗng / null = luồng đầu tiên; số = luồng thứ đó (không có thì lùi về luồng
     * đầu); chữ linh tinh cũng lùi về luồng đầu. `null` khi tệp không có luồng âm thanh nào.
     * Port của video_io.py: `select_audio_track`.
     */
    fun select(tracks: List<AudioTrackInfo>, request: String?): AudioTrackInfo? {
        if (tracks.isEmpty()) return null
        val r = request?.trim()?.lowercase()
        if (r.isNullOrEmpty() || r == AUTO) return tracks[0]
        // int() của Python nhận cả "+1", " 1 "; số âm thì ngoài khoảng → luồng đầu.
        val num = r.toIntOrNull() ?: return tracks[0]
        return if (num in tracks.indices) tracks[num] else tracks[0]
    }

    private fun MediaFormat.stringOr(key: String) = if (containsKey(key)) getString(key) ?: "" else ""

    private fun MediaFormat.intOr(key: String) = if (containsKey(key)) getInteger(key) else 0
}

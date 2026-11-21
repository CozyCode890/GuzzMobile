package io.github.cozycode890.guzz.core.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Tệp để tải lên server (Stage 11 E4, D144). [open] mở luồng đọc từ đầu, [streamIndex] là luồng âm thanh thứ mấy của tệp
 * tải lên mà server giải mã (`-map 0:a:N`), [temp] là tệp tạm cần xoá sau (luồng tách từ video).
 */
class UploadFile(
    val name: String,
    val length: Long,
    val streamIndex: Int,
    val temp: File?,
    val open: () -> InputStream,
)

/**
 * Chuẩn bị tệp tải lên mà không giải mã:
 * - tệp âm thanh: gửi nguyên tệp gốc, server chọn đúng luồng bằng [UploadFile.streamIndex];
 * - video: chọn luồng âm thanh theo `[VIDEO] audio_track` ([AudioTracks.select]) rồi tách bằng MediaMuxer, AAC → `.m4a`,
 *   Opus / Vorbis → `.webm`. Video 1 GB còn vài chục MB, chỉ mất vài giây.
 *
 * Codec khác (vd MP3, AC-3 trong MP4) MediaMuxer không ghi được: trả `null`, pipeline làm trên máy.
 */
object UploadPreparation {
    const val EXTRACTED_FILE = "tai_len"

    /**
     * @param isVideo theo tên / MIME như `Transcript.isVideo` của pipeline.
     * @param size cỡ tệp gốc (byte), `-1` nếu không biết.
     * @throws NoAudioException tệp không có luồng âm thanh nào.
     * @throws MediaReadException không đọc được tệp.
     */
    fun prepare(
        context: Context,
        input: MediaInput,
        displayName: String,
        size: Long,
        isVideo: Boolean,
        audioTrack: String,
        folder: File,
        checkCancelled: () -> Unit = {},
    ): UploadFile? {
        input.open(context).use { media ->
            val tracks = AudioTracks.list(media.extractor)
            val track = AudioTracks.select(tracks, audioTrack)
                ?: throw NoAudioException("$displayName không có luồng âm thanh nào.")
            if (!isVideo) {
                return UploadFile(displayName, size, track.index, null) { openInput(context, input) }
            }
            val (ext, format) = when (track.mime) {
                MediaFormat.MIMETYPE_AUDIO_AAC -> "m4a" to MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_VORBIS ->
                    "webm" to MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else -> return null
            }
            folder.mkdirs()
            val out = File(folder, "$EXTRACTED_FILE.$ext")
            remux(media.extractor, track.trackIndex, out, format, checkCancelled)
            return UploadFile(out.name, out.length(), 0, out) { FileInputStream(out) }
        }
    }

    private fun openInput(context: Context, input: MediaInput): InputStream = when (input) {
        is MediaInput.Path -> FileInputStream(input.file)
        is MediaInput.Content -> context.contentResolver.openInputStream(input.uri)
            ?: throw MediaReadException("Không mở được ${input.uri}")
    }

    /** Chép nguyên các gói của một luồng sang container mới (không giải mã, không mã hoá lại). */
    private fun remux(extractor: MediaExtractor, trackIndex: Int, out: File, format: Int, checkCancelled: () -> Unit) {
        val part = File(out.path + ".part")
        part.delete()
        val muxer = MediaMuxer(part.path, format)
        var ok = false
        try {
            val trackFormat = extractor.getTrackFormat(trackIndex)
            val dst = muxer.addTrack(trackFormat)
            val cap = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else {
                1 shl 20
            }
            val buf = ByteBuffer.allocate(cap)
            val info = MediaCodec.BufferInfo()
            extractor.selectTrack(trackIndex)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            muxer.start()
            var n = 0
            while (true) {
                if (++n % 256 == 0) checkCancelled()
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(dst, buf, info)
                extractor.advance()
            }
            muxer.stop()
            ok = true
        } finally {
            try {
                muxer.release()
            } catch (e: IllegalStateException) {
                // release sau lỗi giữa chừng có thể ném lại; tệp .part bị xoá ở dưới.
            }
            extractor.unselectTrack(trackIndex)
            if (ok) {
                out.delete()
                if (!part.renameTo(out)) throw MediaReadException("Không đổi tên được ${part.name}")
            } else {
                part.delete()
            }
        }
    }
}

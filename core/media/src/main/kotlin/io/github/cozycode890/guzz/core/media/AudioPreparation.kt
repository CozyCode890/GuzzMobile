package io.github.cozycode890.guzz.core.media

import android.content.Context
import android.os.SystemClock
import io.github.cozycode890.guzz.core.audio.ChunkList
import io.github.cozycode890.guzz.core.audio.ChunkPreparer
import io.github.cozycode890.guzz.core.audio.ChunkSettings
import io.github.cozycode890.guzz.core.audio.PrepareListener
import io.github.cozycode890.guzz.core.audio.PrepareStep
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import java.io.File

/** Nghe cả bước giải mã lẫn các bước của [ChunkPreparer]; [checkCancelled] chung cho cả hai. */
interface PreparationListener : DecodeListener, PrepareListener {
    fun onTracks(tracks: List<AudioTrackInfo>, chosen: AudioTrackInfo) {}

    override fun checkCancelled() {}
}

/** Kết quả một lần chuẩn bị: luồng đã chọn, thông tin giải mã, danh sách đoạn, thời gian từng bước (ms). */
data class PreparationResult(
    val tracks: List<AudioTrackInfo>,
    val track: AudioTrackInfo,
    val decode: DecodeResult,
    val chunks: ChunkList,
    val decodeMs: Long,
    val prepareMs: Long,
)

/**
 * Từ một tệp âm thanh / video ra các đoạn FLAC + `danh_sach_doan.json` trong [folder]: chọn luồng âm thanh, kiểm chỗ
 * trống (D44), giải mã ra `goc.pcm`, rồi [ChunkPreparer]. Phần "nguồn âm thanh" + `prepare_chunks` của transcribe.py,
 * chưa có cache theo mã băm (Stage 4).
 */
class AudioPreparation(private val context: Context) {

    fun run(
        input: MediaInput,
        audioTrack: String,
        settings: ChunkSettings,
        folder: File,
        listener: PreparationListener = object : PreparationListener {},
    ): PreparationResult {
        folder.mkdirs()
        File(folder, ChunkPreparer.CHUNK_LIST_FILE).delete()
        val raw = File(folder, RAW_PCM_FILE)
        val t0 = SystemClock.elapsedRealtime()
        val (tracks, track, decoded) = input.open(context).use { media ->
            val tracks = AudioTracks.list(media.extractor)
            val track = AudioTracks.select(tracks, audioTrack)
                ?: throw NoAudioException("${input.name} không có luồng âm thanh nào.")
            listener.onTracks(tracks, track)
            StorageCheck.ensureSpace(folder, track.durationUs)
            Triple(tracks, track, AudioDecoder(SAMPLE_RATE).decode(media.extractor, track, raw, listener))
        }
        val t1 = SystemClock.elapsedRealtime()
        val chunks = Pcm16File(raw).use { pcm ->
            ChunkPreparer(settings, FlacEncoder()).prepare(pcm, SAMPLE_RATE, input.name, folder, listener)
        }
        val t2 = SystemClock.elapsedRealtime()
        return PreparationResult(tracks, track, decoded, chunks, t1 - t0, t2 - t1)
    }

    companion object {
        const val RAW_PCM_FILE = "goc.pcm"

        /** Tần số xử lý cố định (mục "Mặc định kỹ thuật" của Stage 2). */
        const val SAMPLE_RATE = 16_000

        /** Đọc [ChunkSettings] từ cài đặt của app. */
        suspend fun chunkSettings(settings: Settings) = ChunkSettings(
            targetDbfs = settings.get(SettingKeys.TARGET_DBFS),
            trimSilence = settings.get(SettingKeys.TRIM_SILENCE),
            minSilenceLen = settings.get(SettingKeys.MIN_SILENCE_LEN),
            silenceThreshOffset = settings.get(SettingKeys.SILENCE_THRESH_OFFSET),
            keepSilence = settings.get(SettingKeys.KEEP_SILENCE),
            chunkMinutes = settings.get(SettingKeys.CHUNK_MINUTES),
            searchSeconds = settings.get(SettingKeys.SEARCH_SECONDS),
            minLastChunkSeconds = settings.get(SettingKeys.MIN_LAST_CHUNK_SECONDS),
        )
    }
}

/** Để [PrepareStep] hiện được tên bước ở màn hình thử / log. */
val PrepareStep.label: String
    get() = when (this) {
        PrepareStep.NORMALIZE -> "chuẩn hoá âm lượng"
        PrepareStep.TRIM -> "cắt lặng"
        PrepareStep.WRITE_CLEAN -> "ghi sach.pcm"
        PrepareStep.WRITE_CHUNKS -> "ghi đoạn FLAC"
    }

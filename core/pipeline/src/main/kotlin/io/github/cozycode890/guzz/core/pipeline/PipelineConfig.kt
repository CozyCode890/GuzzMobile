package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.audio.ChunkSettings
import io.github.cozycode890.guzz.core.audio.CleanSettings
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.core.gemini.GeminiConfig

/**
 * Cài đặt của pipeline cho một tệp: phần `Config` của desktop mà `transcribe_single_file` dùng. Đọc lại ở mỗi tệp
 * ([load]) như `TranscribeThread.run`, nên đổi cài đặt lúc hàng đợi đang chạy thì có tác dụng từ tệp sau.
 */
data class PipelineConfig(
    val outputDir: String = SettingKeys.OUTPUT_DIR.default,
    val filenameTemplate: String = SettingKeys.FILENAME_TEMPLATE.default,
    val extension: String = SettingKeys.EXTENSION.default,
    val onConflict: String = SettingKeys.ON_CONFLICT.default,
    val encoding: String = SettingKeys.ENCODING.default,
    val newline: String = SettingKeys.NEWLINE.default,
    val addHeader: Boolean = SettingKeys.ADD_HEADER.default,
    val markChunks: Boolean = SettingKeys.MARK_CHUNKS.default,
    val skipSilent: Boolean = SettingKeys.SKIP_SILENT.default,
    val onFileError: String = SettingKeys.ON_FILE_ERROR.default,
    val notifyWhenDone: Boolean = SettingKeys.NOTIFY_WHEN_DONE.default,
    val deleteTempWhenDone: Boolean = SettingKeys.DELETE_TEMP_WHEN_DONE.default,
    val audioTrack: String = SettingKeys.AUDIO_TRACK.default,
    val chunk: ChunkSettings = ChunkSettings(),
    val clean: CleanSettings = CleanSettings(),
    val saveCleanAudio: Boolean = SettingKeys.SAVE_CLEAN_AUDIO.default,
    /** `[VIDEO] save_extracted` (D68): nguồn là video thì lưu thêm `<tên>.am_thanh.flac` cạnh bản chép lời. */
    val saveExtracted: Boolean = SettingKeys.SAVE_EXTRACTED.default,
    val chunkFormat: String = SettingKeys.CHUNK_FORMAT.default,
    val gemini: GeminiConfig = GeminiConfig(),
    val speakers: SpeakerConfig = SpeakerConfig(),
    /** Chỉ bản debug (Stage 6, B6): Gemini giả / thật cho riêng tệp, từ [ItemOverrides.fakeGemini]; `null` = theo cờ chung. */
    val debugFakeGemini: Boolean? = null,
    /** `[AUTOMATION] save_beside_source` (Stage 6, D96): xem [OutputChoice]. */
    val saveBesideSource: Boolean = SettingKeys.SAVE_BESIDE_SOURCE.default,
    /** `[SERVER] enabled` (Stage 11): nhờ server xử lý âm thanh, lỗi thì làm trên máy. */
    val serverEnabled: Boolean = SettingKeys.SERVER_ENABLED.default,
) {
    companion object {
        /** Tần số xử lý cố định (Stage 2), có trong mã âm thanh như `sample_rate` của desktop. */
        const val SAMPLE_RATE = 16_000

        const val CONFLICT_NUMBER = "number"
        const val CONFLICT_OVERWRITE = "overwrite"
        const val CONFLICT_SKIP = "skip"
        const val ON_ERROR_STOP = "stop"

        suspend fun load(settings: Settings): PipelineConfig = PipelineConfig(
            outputDir = settings.get(SettingKeys.OUTPUT_DIR),
            filenameTemplate = settings.get(SettingKeys.FILENAME_TEMPLATE),
            extension = settings.get(SettingKeys.EXTENSION),
            onConflict = settings.get(SettingKeys.ON_CONFLICT),
            encoding = settings.get(SettingKeys.ENCODING),
            newline = settings.get(SettingKeys.NEWLINE),
            addHeader = settings.get(SettingKeys.ADD_HEADER),
            markChunks = settings.get(SettingKeys.MARK_CHUNKS),
            skipSilent = settings.get(SettingKeys.SKIP_SILENT),
            onFileError = settings.get(SettingKeys.ON_FILE_ERROR),
            notifyWhenDone = settings.get(SettingKeys.NOTIFY_WHEN_DONE),
            deleteTempWhenDone = settings.get(SettingKeys.DELETE_TEMP_WHEN_DONE),
            audioTrack = settings.get(SettingKeys.AUDIO_TRACK),
            chunk = ChunkSettings(
                targetDbfs = settings.get(SettingKeys.TARGET_DBFS),
                trimSilence = settings.get(SettingKeys.TRIM_SILENCE),
                minSilenceLen = settings.get(SettingKeys.MIN_SILENCE_LEN),
                silenceThreshOffset = settings.get(SettingKeys.SILENCE_THRESH_OFFSET),
                keepSilence = settings.get(SettingKeys.KEEP_SILENCE),
                chunkMinutes = settings.get(SettingKeys.CHUNK_MINUTES),
                searchSeconds = settings.get(SettingKeys.SEARCH_SECONDS),
                minLastChunkSeconds = settings.get(SettingKeys.MIN_LAST_CHUNK_SECONDS),
            ),
            clean = CleanSettings.load(settings),
            saveCleanAudio = settings.get(SettingKeys.SAVE_CLEAN_AUDIO),
            saveExtracted = settings.get(SettingKeys.SAVE_EXTRACTED),
            chunkFormat = settings.get(SettingKeys.CHUNK_FORMAT),
            gemini = GeminiConfig.load(settings),
            speakers = SpeakerConfig.load(settings),
            saveBesideSource = settings.get(SettingKeys.SAVE_BESIDE_SOURCE),
            serverEnabled = settings.get(SettingKeys.SERVER_ENABLED),
        )
    }
}

package io.github.cozycode890.guzz.core.common.settings

/**
 * Mọi mục cài đặt của app, ở một chỗ.
 *
 * Ràng buộc và mặc định lấy đúng theo `load_config` trong config.py của desktop. Mỗi stage thêm mục của mình vào đây
 * và vào [all].
 */
object SettingKeys {
    // [SYSTEM]
    val LOG_LEVEL = ChoiceSetting(
        "SYSTEM", "log_level", default = "INFO", choices = listOf("INFO", "DEBUG"), case = ChoiceSetting.Case.UPPER,
    )
    val LOG_SIZE_MB = IntSetting("SYSTEM", "log_size_mb", default = 2, min = 1)

    /** Số tệp nhật ký cũ giữ lại. Riêng 0 thì khác desktop: đầy là xoá trắng `guzz.log` (D23, `RotatingFileSink`). */
    val OLD_LOG_COUNT = IntSetting("SYSTEM", "old_log_count", default = 5, min = 0)

    // [AUDIO] (Stage 2). Desktop không chặn các số này; chặn dưới ở đây chỉ để giá trị âm vô nghĩa không lọt vào thuật
    // toán (`find_speech_segments` coi cửa sổ ≤ 0 là không cắt gì, `trim_silence` với keep_silence âm thì cắt lẹm vào
    // lời nói).
    val TARGET_DBFS = DoubleSetting("AUDIO", "target_dbfs", default = -16.0)
    val TRIM_SILENCE = BoolSetting("AUDIO", "trim_silence", default = true)
    val MIN_SILENCE_LEN = IntSetting("AUDIO", "min_silence_len", default = 1000, min = 0)
    val SILENCE_THRESH_OFFSET = IntSetting("AUDIO", "silence_thresh_offset", default = 20)
    val KEEP_SILENCE = IntSetting("AUDIO", "keep_silence", default = 400, min = 0)

    // [VIDEO] (Stage 2). `extract_first`, `extract_format` của desktop không có trên mobile (D47): bài nào cũng được giải
    // mã một lần ra PCM tạm. Giá trị lạ của audio_track không làm hỏng gì: chọn luồng lùi về luồng đầu như desktop.
    val AUDIO_TRACK = StringSetting("VIDEO", "audio_track", default = "auto")

    // [CHUNKING] (Stage 2). Desktop báo lỗi khi chunk_minutes ≤ 0; mobile không được làm app chết vì cài đặt, nên kẹp.
    val CHUNK_MINUTES = DoubleSetting("CHUNKING", "chunk_minutes", default = 10.0, min = 0.1)
    val SEARCH_SECONDS = DoubleSetting("CHUNKING", "search_seconds", default = 20.0, min = 0.0)
    val MIN_LAST_CHUNK_SECONDS = DoubleSetting("CHUNKING", "min_last_chunk_seconds", default = 60.0, min = 0.0)

    /** Chỉ FLAC (D41, D48): Android không có bộ mã hoá MP3; `wav` / `mp3` nhập từ desktop tự về `flac`. */
    val CHUNK_FORMAT = ChoiceSetting("CHUNKING", "format", default = "flac", choices = listOf("flac"))

    val all: List<SettingKey<*>> = listOf(
        LOG_LEVEL,
        LOG_SIZE_MB,
        OLD_LOG_COUNT,
        TARGET_DBFS,
        TRIM_SILENCE,
        MIN_SILENCE_LEN,
        SILENCE_THRESH_OFFSET,
        KEEP_SILENCE,
        AUDIO_TRACK,
        CHUNK_MINUTES,
        SEARCH_SECONDS,
        MIN_LAST_CHUNK_SECONDS,
        CHUNK_FORMAT,
    )

    // [APP]: trạng thái app tự ghi, người dùng không chỉnh. Không nằm trong [all] để không bị xuất / nhập cùng cài đặt.

    /** versionCode của lần mở app gần nhất; 0 = chưa mở lần nào (D27). Ghi bằng [Settings.recordAppVersion]. */
    val LAST_VERSION_CODE = IntSetting("APP", "last_version_code", default = 0, min = 0, sameAsDesktop = false)

    val appState: List<SettingKey<*>> = listOf(
        LAST_VERSION_CODE,
    )
}

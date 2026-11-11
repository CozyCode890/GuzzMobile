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

    // [GOOGLE_AI] (Stage 3). `prompt_file` của desktop không có trên mobile: prompt mặc định đóng gói trong app, sửa
    // prompt để Stage 8. Danh sách (languages, vocabulary) lưu nguyên chuỗi cách nhau dấu phẩy như config.txt; tách ra ở
    // `GeminiConfig`. Desktop không chặn timeout_seconds / chunk_retries; chặn dưới 0 chỉ để số âm vô nghĩa không lọt
    // vào OkHttp (timeout âm thì ném lỗi).
    val MODEL = StringSetting("GOOGLE_AI", "model", default = "gemini-3.5-flash-lite")
    val TRANSCRIBE_MODE = ChoiceSetting(
        "GOOGLE_AI", "transcribe_mode", default = "smart", choices = listOf("smart", "verbatim"),
    )
    val LANGUAGES = StringSetting("GOOGLE_AI", "languages", default = "")
    val VOCABULARY = StringSetting("GOOGLE_AI", "vocabulary", default = "")
    val STORE_ON_SERVER = BoolSetting("GOOGLE_AI", "store_on_server", default = false)
    val TOKEN_RATE_LIMIT = IntSetting("GOOGLE_AI", "token_rate_limit", default = 10000, min = 0)
    val TIMEOUT_SECONDS = IntSetting("GOOGLE_AI", "timeout_seconds", default = 600, min = 0)
    val CHUNK_RETRIES = IntSetting("GOOGLE_AI", "chunk_retries", default = 4, min = 0)
    val DELETE_REMOTE_FILE = BoolSetting("GOOGLE_AI", "delete_remote_file", default = true)

    // [FALLBACK] (Stage 3), chặn như load_config.
    val FALLBACK_ENABLED = BoolSetting("FALLBACK", "enabled", default = true)
    val FALLBACK_MODELS = StringSetting(
        "FALLBACK", "models",
        default = "gemini-3.5-flash-lite, gemini-3.5-flash, gemini-3.8-flash, gemini-3.5-transcribe",
    )
    val RETRIES_BEFORE_SWITCH = IntSetting("FALLBACK", "retries_before_switch", default = 2, min = 1)
    val OVERLOAD_LOCK_MINUTES = DoubleSetting("FALLBACK", "overload_lock_minutes", default = 10.0, min = 0.0)
    val RETURN_TO_MAIN_MODEL = BoolSetting("FALLBACK", "return_to_main_model", default = true)
    val SWITCH_WAIT_THRESHOLD_SEC = IntSetting("FALLBACK", "switch_wait_threshold_sec", default = 0, min = 0)
    val MAX_ALL_LOCKED_WAIT_MIN = DoubleSetting("FALLBACK", "max_all_locked_wait_min", default = 20.0, min = 0.0)
    val PROACTIVE_DAILY_LOCK = BoolSetting("FALLBACK", "proactive_daily_lock", default = true)

    /**
     * [QUOTAS] của desktop là section có khoá động (tên model), DataStore không có kiểu đó nên lưu cả section thành
     * một chuỗi, mỗi dòng `model = token/phút, yêu cầu/phút, yêu cầu/ngày` như config.txt. Mặc định là đúng các dòng
     * của `config.default.txt` (golden `QuotaLinesGoldenTest` so với `model_quotas` của desktop).
     */
    val QUOTAS_LINES = StringSetting(
        "QUOTAS", "lines",
        default = """
            gemini-3.5-transcribe  = 10000, 3, 25
            gemini-3.5-flash       = 250000, 5, 20
            gemini-3.8-flash       = 250000, 5, 20
            gemini-3.5-flash-lite  = 250000, 15, 500
        """.trimIndent(),
        sameAsDesktop = false,
    )

    // [SPEAKERS] (Stage 3 chỉ dùng để tính chuỗi model và ràng buộc model ↔ cách nhận diện; dùng thật ở Stage 5).
    val SPEAKERS_ENABLED = BoolSetting("SPEAKERS", "enabled", default = false)
    val SPEAKERS_METHOD = ChoiceSetting(
        "SPEAKERS", "method", default = "pyannote", choices = listOf("pyannote", "gemini", "hybrid"),
    )

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
        MODEL,
        TRANSCRIBE_MODE,
        LANGUAGES,
        VOCABULARY,
        STORE_ON_SERVER,
        TOKEN_RATE_LIMIT,
        TIMEOUT_SECONDS,
        CHUNK_RETRIES,
        DELETE_REMOTE_FILE,
        FALLBACK_ENABLED,
        FALLBACK_MODELS,
        RETRIES_BEFORE_SWITCH,
        OVERLOAD_LOCK_MINUTES,
        RETURN_TO_MAIN_MODEL,
        SWITCH_WAIT_THRESHOLD_SEC,
        MAX_ALL_LOCKED_WAIT_MIN,
        PROACTIVE_DAILY_LOCK,
        QUOTAS_LINES,
        SPEAKERS_ENABLED,
        SPEAKERS_METHOD,
    )

    // [APP]: trạng thái app tự ghi, người dùng không chỉnh. Không nằm trong [all] để không bị xuất / nhập cùng cài đặt.

    /** versionCode của lần mở app gần nhất; 0 = chưa mở lần nào (D27). Ghi bằng [Settings.recordAppVersion]. */
    val LAST_VERSION_CODE = IntSetting("APP", "last_version_code", default = 0, min = 0, sameAsDesktop = false)

    val appState: List<SettingKey<*>> = listOf(
        LAST_VERSION_CODE,
    )
}

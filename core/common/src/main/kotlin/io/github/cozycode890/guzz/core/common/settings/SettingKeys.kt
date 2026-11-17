package io.github.cozycode890.guzz.core.common.settings

/**
 * Mọi mục cài đặt của app, ở một chỗ.
 *
 * Ràng buộc và mặc định lấy đúng theo `load_config` trong config.py của desktop. Mỗi stage thêm mục của mình vào đây
 * và vào [all].
 */
object SettingKeys {
    // [OUTPUT] (Stage 4), chặn như load_config.

    /**
     * URI cây SAF của thư mục lưu bản chép lời (D59); rỗng = chưa chọn, app hỏi lúc bấm Bắt đầu. Desktop: rỗng = cạnh
     * tệp nguồn, đường dẫn thường; mobile lưu cạnh tệp nguồn ở Stage 6, nên giá trị không chuyển qua lại được.
     */
    val OUTPUT_DIR = StringSetting("OUTPUT", "output_dir", default = "", sameAsDesktop = false, clean = String::trim)
    val FILENAME_TEMPLATE = StringSetting(
        "OUTPUT", "filename_template", default = "{name}", clean = { it.trim().ifEmpty { "{name}" } },
    )

    /** Như load_config: chữ thường, rỗng thì `.txt`, thiếu dấu chấm thì thêm. */
    val EXTENSION = StringSetting(
        "OUTPUT", "extension", default = ".txt",
        clean = { v -> v.trim().lowercase().ifEmpty { ".txt" }.let { if (it.startsWith(".")) it else ".$it" } },
    )
    val ON_CONFLICT = ChoiceSetting(
        "OUTPUT", "on_conflict", default = "number", choices = listOf("number", "overwrite", "skip"),
    )
    val ENCODING = ChoiceSetting("OUTPUT", "encoding", default = "utf-8", choices = listOf("utf-8", "utf-8-sig"))
    val NEWLINE = ChoiceSetting("OUTPUT", "newline", default = "crlf", choices = listOf("crlf", "lf"))
    val ADD_HEADER = BoolSetting("OUTPUT", "add_header", default = false)
    val MARK_CHUNKS = BoolSetting("OUTPUT", "mark_chunks", default = false)

    // [QUEUE] (Stage 4)
    val ON_FILE_ERROR = ChoiceSetting(
        "QUEUE", "on_file_error", default = "continue", choices = listOf("continue", "stop"),
    )
    val NOTIFY_WHEN_DONE = BoolSetting("QUEUE", "notify_when_done", default = true)

    // [SYSTEM]

    /**
     * Xoá thư mục tạm của tệp đã xong (D66). `config.default.txt` của desktop đặt `false` (dataclass mới mặc định
     * `true`); điện thoại ít chỗ trống hơn PC và bài 2 giờ chiếm khoảng 200 MB tạm, nên mobile mặc định xoá.
     */
    val DELETE_TEMP_WHEN_DONE = BoolSetting("SYSTEM", "delete_temp_when_done", default = true, sameAsDesktop = false)
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

    // [AUDIO] (Stage 7): làm sạch đầy đủ. Mặc định = preset "Giảng viên đi lại", đúng config.default.txt (D87). Desktop
    // chỉ chặn transient_clamp_db (`max(0, …)`); ở đây chặn thêm prop_decrease trong [0, 1] và noise_sample_sec,
    // level_window_sec không âm vì giá trị ngoài khoảng đó vô nghĩa (reduce_noise tự nâng time_constant_s lên 0,2 s,
    // level_loudness coi ≤ 0 là tắt). Không có `sample_rate`: mobile xử lý cố định 16 kHz (Stage 2,
    // `AudioPreparation.SAMPLE_RATE`), Google AI cũng hạ âm thanh về 16 kHz.
    val DENOISE = BoolSetting("AUDIO", "denoise", default = true)
    val NOISE_SAMPLE_SEC = DoubleSetting("AUDIO", "noise_sample_sec", default = 1.5, min = 0.0)
    val BANDPASS_LOW = IntSetting("AUDIO", "bandpass_low", default = 80)
    val BANDPASS_HIGH = IntSetting("AUDIO", "bandpass_high", default = 7000)
    val PROP_DECREASE = DoubleSetting("AUDIO", "prop_decrease", default = 0.8, min = 0.0, max = 1.0)
    val LEVEL_WINDOW_SEC = DoubleSetting("AUDIO", "level_window_sec", default = 3.0, min = 0.0)

    /** Ghìm tiếng động to hơn mức thường quá số dB này; 0 = tắt. Như load_config: `max(0.0, …)`. */
    val TRANSIENT_CLAMP_DB = DoubleSetting("AUDIO", "transient_clamp_db", default = 0.0, min = 0.0)

    /** Lưu thêm âm thanh đã làm sạch `<tên>.sach.flac` cạnh bản chép lời. */
    val SAVE_CLEAN_AUDIO = BoolSetting("OUTPUT", "save_clean_audio", default = false)

    // [VIDEO] (Stage 2). `extract_first`, `extract_format` của desktop không có trên mobile (D47): bài nào cũng được giải
    // mã một lần ra PCM tạm. Giá trị lạ của audio_track không làm hỏng gì: chọn luồng lùi về luồng đầu như desktop.
    val AUDIO_TRACK = StringSetting("VIDEO", "audio_track", default = "auto")

    /** Video không có luồng âm thanh nào: bỏ qua tệp (true) hay báo lỗi (Stage 4, Phát sinh Stage 2). */
    val SKIP_SILENT = BoolSetting("VIDEO", "skip_silent", default = true)

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

    // [SPEAKERS] (Stage 5), chặn như load_config. Giá trị của `method` giữ như desktop (D71): `pyannote` là "Tách giọng
    // trên máy + Prompt" (sherpa-onnx, không còn pyannote), `gemini`, `hybrid`. Không có trên mobile, vì tách giọng chạy
    // sherpa-onnx ngay trong app với model đóng gói trong APK: `python_path`, `pyannote_model`, `model_dir`, `offline`
    // (không tải gì từ Hugging Face), `device` (chỉ CPU), `timeout_minutes` (Dừng giết process `:diarize`, không cần hạn
    // giờ). `prompt_file`: prompt mặc định đóng gói trong app, sửa prompt để Stage 8 (D76).
    val SPEAKERS_ENABLED = BoolSetting("SPEAKERS", "enabled", default = false)
    val SPEAKERS_METHOD = ChoiceSetting(
        "SPEAKERS", "method", default = "pyannote", choices = listOf("pyannote", "gemini", "hybrid"),
    )

    /** Biết chính xác số người thì ghi vào (0 = tự đoán trong [min_speakers, max_speakers]); D80: khuyên nhập. */
    val NUM_SPEAKERS = IntSetting("SPEAKERS", "num_speakers", default = 0, min = 0)
    val MIN_SPEAKERS = IntSetting("SPEAKERS", "min_speakers", default = 1, min = 0)
    val MAX_SPEAKERS = IntSetting("SPEAKERS", "max_speakers", default = 6, min = 0)
    val RUN_ON = ChoiceSetting("SPEAKERS", "run_on", default = "clean", choices = listOf("clean", "original"))
    val SMOOTH = BoolSetting("SPEAKERS", "smooth", default = true)
    val SANDWICH_MAX_SEC = DoubleSetting("SPEAKERS", "sandwich_max_sec", default = 3.0)
    val SANDWICH_GAP_SEC = DoubleSetting("SPEAKERS", "sandwich_gap_sec", default = 1.0)
    val DENSITY_WINDOW_SEC = DoubleSetting("SPEAKERS", "density_window_sec", default = 60.0)
    val DENSITY_MIN_SEC = DoubleSetting("SPEAKERS", "density_min_sec", default = 15.0)
    val MERGE_GAP_SEC = DoubleSetting("SPEAKERS", "merge_gap_sec", default = 1.0, min = 0.0)
    val LABEL_MAIN_SPEAKER = BoolSetting("SPEAKERS", "label_main_speaker", default = true)
    val MAIN_LABEL = StringSetting("SPEAKERS", "main_label", default = "Giảng viên", clean = { it.trim().ifEmpty { "Giảng viên" } })
    val OTHER_LABEL = StringSetting("SPEAKERS", "other_label", default = "Người hỏi", clean = { it.trim().ifEmpty { "Người hỏi" } })
    val GENERIC_LABEL = StringSetting("SPEAKERS", "generic_label", default = "Người nói", clean = { it.trim().ifEmpty { "Người nói" } })
    val MAP_DELIVERY = ChoiceSetting("SPEAKERS", "map_delivery", default = "file", choices = listOf("file", "text"))
    val SPEAKER_TIMESTAMPS = BoolSetting("SPEAKERS", "timestamps", default = true)
    val PARAGRAPH_TEMPLATE = StringSetting(
        "SPEAKERS", "paragraph_template", default = "[{time}] {speaker}: {text}",
        clean = { it.trim().ifEmpty { "[{time}] {speaker}: {text}" } },
    )
    val MAX_PARAGRAPH_SEC = DoubleSetting("SPEAKERS", "max_paragraph_sec", default = 60.0, min = 0.0)
    val PARAGRAPH_GAP_SEC = DoubleSetting("SPEAKERS", "paragraph_gap_sec", default = 3.0, min = 0.0)
    val MARK_REVIEW = BoolSetting("SPEAKERS", "mark_review", default = true)

    /** Lưu thêm bản đồ người nói `<tên>.nguoi_noi.txt` cạnh bản chép lời. */
    val SAVE_SPEAKER_MAP = BoolSetting("OUTPUT", "save_speaker_map", default = false)

    // [AUTOMATION] (Stage 6): app khác (MacroDroid, Tasker…) điều khiển Guzz bằng intent. Chỉ có trên mobile. Đặt trước
    // [all] vì [all] dựng lúc khởi tạo object, khoá khai báo sau nó còn null.

    /** Cho phép app khác gửi lệnh (mặc định tắt). Chia sẻ tới Guzz không cần mục này (D98). */
    val AUTOMATION_ENABLED = BoolSetting("AUTOMATION", "enabled", default = false, sameAsDesktop = false)

    /** Mã bí mật tuỳ chọn (D93): rỗng = không kiểm. Không nằm trong [all]: không xuất / nhập, như API key. */
    val AUTOMATION_SECRET = StringSetting("AUTOMATION", "secret", default = "", sameAsDesktop = false, clean = String::trim)

    /** Sự kiện gửi ra có kèm đường dẫn tệp nguồn / bản chép lời (D97). */
    val AUTOMATION_INCLUDE_PATHS = BoolSetting("AUTOMATION", "include_paths", default = true, sameAsDesktop = false)

    /** Lưu bản chép lời cạnh tệp nguồn khi có quyền "Truy cập mọi tệp" (D96); không thì vào thư mục SAF. */
    val SAVE_BESIDE_SOURCE = BoolSetting("AUTOMATION", "save_beside_source", default = false, sameAsDesktop = false)

    /**
     * "Thư mục nguồn" cấp qua SAF (D104), mỗi dòng một URI cây: đường dẫn nằm trong đó thì đọc được dù không có quyền
     * "Truy cập mọi tệp". Không nằm trong [all]: URI chỉ dùng được trên máy đã cấp.
     */
    val AUTOMATION_SOURCE_DIRS = StringSetting("AUTOMATION", "source_dirs", default = "", sameAsDesktop = false, clean = String::trim)

    val all: List<SettingKey<*>> = listOf(
        OUTPUT_DIR,
        FILENAME_TEMPLATE,
        EXTENSION,
        ON_CONFLICT,
        ENCODING,
        NEWLINE,
        ADD_HEADER,
        MARK_CHUNKS,
        ON_FILE_ERROR,
        NOTIFY_WHEN_DONE,
        DELETE_TEMP_WHEN_DONE,
        LOG_LEVEL,
        LOG_SIZE_MB,
        OLD_LOG_COUNT,
        TARGET_DBFS,
        TRIM_SILENCE,
        MIN_SILENCE_LEN,
        SILENCE_THRESH_OFFSET,
        KEEP_SILENCE,
        DENOISE,
        NOISE_SAMPLE_SEC,
        BANDPASS_LOW,
        BANDPASS_HIGH,
        PROP_DECREASE,
        LEVEL_WINDOW_SEC,
        TRANSIENT_CLAMP_DB,
        SAVE_CLEAN_AUDIO,
        AUDIO_TRACK,
        SKIP_SILENT,
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
        NUM_SPEAKERS,
        MIN_SPEAKERS,
        MAX_SPEAKERS,
        RUN_ON,
        SMOOTH,
        SANDWICH_MAX_SEC,
        SANDWICH_GAP_SEC,
        DENSITY_WINDOW_SEC,
        DENSITY_MIN_SEC,
        MERGE_GAP_SEC,
        LABEL_MAIN_SPEAKER,
        MAIN_LABEL,
        OTHER_LABEL,
        GENERIC_LABEL,
        MAP_DELIVERY,
        SPEAKER_TIMESTAMPS,
        PARAGRAPH_TEMPLATE,
        MAX_PARAGRAPH_SEC,
        PARAGRAPH_GAP_SEC,
        MARK_REVIEW,
        SAVE_SPEAKER_MAP,
        AUTOMATION_ENABLED,
        AUTOMATION_INCLUDE_PATHS,
        SAVE_BESIDE_SOURCE,
    )

    /** Mục của máy này, không xuất / nhập cùng [all]: mã bí mật và quyền SAF chỉ có nghĩa trên máy đã cấp. */
    val deviceLocal: List<SettingKey<*>> = listOf(
        AUTOMATION_SECRET,
        AUTOMATION_SOURCE_DIRS,
    )

    // [APP]: trạng thái app tự ghi, người dùng không chỉnh. Không nằm trong [all] để không bị xuất / nhập cùng cài đặt.

    /** versionCode của lần mở app gần nhất; 0 = chưa mở lần nào (D27). Ghi bằng [Settings.recordAppVersion]. */
    val LAST_VERSION_CODE = IntSetting("APP", "last_version_code", default = 0, min = 0, sameAsDesktop = false)

    val appState: List<SettingKey<*>> = listOf(
        LAST_VERSION_CODE,
    )
}

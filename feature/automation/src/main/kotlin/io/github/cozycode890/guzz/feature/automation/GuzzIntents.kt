package io.github.cozycode890.guzz.feature.automation

/**
 * API intent của Guzz cho app tự động hoá (Stage 6). Gốc tên cố định cho mọi bản, cả debug (D91): macro chọn bản debug
 * hay bản thật bằng ô Package. `docs/intents.md` liệt kê đúng các tên ở đây (có test so khớp).
 */
object GuzzIntents {
    const val PREFIX = "io.github.cozycode890.guzz"

    // ---- lệnh gửi tới Guzz
    const val ACTION_TRANSCRIBE = "$PREFIX.action.TRANSCRIBE"
    const val ACTION_START = "$PREFIX.action.START"
    const val ACTION_STOP = "$PREFIX.action.STOP"
    const val ACTION_STATUS = "$PREFIX.action.STATUS"
    val actions = listOf(ACTION_TRANSCRIBE, ACTION_START, ACTION_STOP, ACTION_STATUS)

    // ---- sự kiện Guzz phát ra (không có FILE_PROGRESS, D92)
    const val EVENT_FILE_STARTED = "$PREFIX.event.FILE_STARTED"
    const val EVENT_FILE_DONE = "$PREFIX.event.FILE_DONE"
    const val EVENT_FILE_FAILED = "$PREFIX.event.FILE_FAILED"
    const val EVENT_QUEUE_FINISHED = "$PREFIX.event.QUEUE_FINISHED"
    const val EVENT_STATUS = "$PREFIX.event.STATUS"
    const val EVENT_ERROR = "$PREFIX.event.ERROR"
    val events = listOf(EVENT_FILE_STARTED, EVENT_FILE_DONE, EVENT_FILE_FAILED, EVENT_QUEUE_FINISHED, EVENT_STATUS, EVENT_ERROR)

    // ---- tên lớp nhận lệnh (D94), cho macro điền ô Class
    const val RECEIVER_CLASS = "$PREFIX.feature.automation.CommandReceiver"
    const val ACTIVITY_CLASS = "$PREFIX.feature.automation.CommandActivity"

    // ---- extra của lệnh
    const val EXTRA_PATH = "path"
    const val EXTRA_PATHS = "paths"
    const val EXTRA_URI = "uri"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_SECRET = "secret"
    const val EXTRA_START = "start"
    const val EXTRA_SPEAKERS = "speakers"
    const val EXTRA_NUM_SPEAKERS = "num_speakers"
    const val EXTRA_PRESET = "preset"
    const val EXTRA_DENOISE = "denoise"
    const val EXTRA_OUTPUT_DIR = "output_dir"

    /** Chỉ bản debug (B6): Gemini giả / thật cho riêng tệp; bản release bỏ qua. Không ghi trong docs/intents.md. */
    const val EXTRA_FAKE_GEMINI = "fake_gemini"
    val commandExtras = listOf(
        EXTRA_PATH, EXTRA_PATHS, EXTRA_URI, EXTRA_REQUEST_ID, EXTRA_SECRET, EXTRA_START, EXTRA_SPEAKERS,
        EXTRA_NUM_SPEAKERS, EXTRA_PRESET, EXTRA_DENOISE, EXTRA_OUTPUT_DIR,
    )

    // ---- extra của sự kiện (mọi giá trị là chuỗi, để app nào cũng đọc được)
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_NAME = "name"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_OUTPUT_PATH = "output_path"
    const val EXTRA_OUTPUT_URI = "output_uri"
    const val EXTRA_DURATION_SEC = "duration_sec"
    const val EXTRA_CHARS = "chars"
    const val EXTRA_MODELS = "models"
    const val EXTRA_SKIPPED = "skipped"
    const val EXTRA_ERROR = "error"
    const val EXTRA_ERROR_KIND = "error_kind"
    const val EXTRA_DONE = "done"
    const val EXTRA_FAILED = "failed"
    const val EXTRA_STOPPED = "stopped"
    const val EXTRA_RUNNING = "running"
    const val EXTRA_WAITING = "waiting"
    const val EXTRA_CURRENT = "current"
    const val EXTRA_PROGRESS = "progress"
    const val EXTRA_CHUNK = "chunk"
    const val EXTRA_CHUNK_TOTAL = "chunk_total"
    const val EXTRA_STEP = "step"
    const val EXTRA_COMMAND = "command"
    val eventExtras = listOf(
        EXTRA_PACKAGE, EXTRA_REQUEST_ID, EXTRA_NAME, EXTRA_SOURCE, EXTRA_OUTPUT_PATH, EXTRA_OUTPUT_URI,
        EXTRA_DURATION_SEC, EXTRA_CHARS, EXTRA_MODELS, EXTRA_SKIPPED, EXTRA_ERROR, EXTRA_ERROR_KIND, EXTRA_DONE,
        EXTRA_FAILED, EXTRA_STOPPED, EXTRA_RUNNING, EXTRA_WAITING, EXTRA_CURRENT, EXTRA_PROGRESS, EXTRA_CHUNK,
        EXTRA_CHUNK_TOTAL, EXTRA_STEP, EXTRA_COMMAND,
    )

    /** `error_kind` của sự kiện ERROR (lỗi của lệnh) và FILE_FAILED (tệp không đọc được), ngoài mã lỗi của pipeline. */
    object Kind {
        const val DISABLED = "disabled"
        const val BAD_SECRET = "bad_secret"
        const val BAD_EXTRA = "bad_extra"
        const val DUPLICATE = "duplicate"
        const val NOT_READY = "not_ready"
        const val BACKGROUND_BLOCKED = "background_blocked"
        const val NO_PERMISSION = "no_permission"
        const val NOT_FOUND = "not_found"
        val all = listOf(DISABLED, BAD_SECRET, BAD_EXTRA, DUPLICATE, NOT_READY, BACKGROUND_BLOCKED, NO_PERMISSION, NOT_FOUND)
    }

    /** Tên ngắn của action / event (phần sau dấu chấm cuối), cho log. */
    fun shortName(action: String?): String = action?.substringAfterLast('.') ?: "?"
}

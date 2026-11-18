package io.github.cozycode890.guzz.feature.automation

/**
 * Lọc tệp được chia sẻ tới Guzz (D106). Zalo, Telegram… chia sẻ tệp với kiểu chung `application/octet-stream`, nên
 * Guzz nhận cả kiểu đó rồi tự xem tệp có phải âm thanh / video không: kiểu MIME của URI (nếu provider biết) hoặc đuôi
 * tên tệp. Không nhận `*` (mọi kiểu) để Guzz không hiện khi chia sẻ ảnh, chữ, liên kết.
 */
object ShareFilter {
    /** Đuôi âm thanh: `accepted_extensions` của desktop (config.py) thêm vài đuôi máy ghi âm Android hay dùng. */
    val AUDIO_EXTENSIONS = setOf(
        "m4a", "flac", "mp3", "wav", "wma", "aac", "ogg", "opus", "amr", "3gp", "3ga", "awb", "mka", "aif", "aiff",
    )

    /** Đuôi video: Guzz lấy luồng tiếng (Stage 2, chọn luồng). */
    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "3g2", "ts", "wmv")

    /**
     * Tệp có phải âm thanh / video không. [mime] là kiểu provider báo cho URI (có thể null hoặc chung chung), [name]
     * là tên hiển thị (có thể null).
     */
    fun isMedia(mime: String?, name: String?): Boolean {
        val m = mime?.lowercase()?.substringBefore(';')?.trim()
        if (m != null && (m.startsWith("audio/") || m.startsWith("video/"))) return true
        val ext = name?.substringAfterLast('/')?.let { n ->
            n.lastIndexOf('.').takeIf { it > 0 && it < n.length - 1 }?.let { n.substring(it + 1).lowercase() }
        } ?: return false
        return ext in AUDIO_EXTENSIONS || ext in VIDEO_EXTENSIONS
    }
}

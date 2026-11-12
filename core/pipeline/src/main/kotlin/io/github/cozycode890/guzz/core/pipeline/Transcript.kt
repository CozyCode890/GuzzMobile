package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.common.pyRoundToLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Kết quả chép lời một đoạn, cùng khoá JSON với `doan_NNN.<mã>.json` của desktop: chữ, từ có mốc (chỉ khi nhận diện
 * người nói, Stage 5), model đã chép lời (kết quả cũ không có thì tính cho model chính).
 */
@Serializable
data class ChunkResult(
    @SerialName("van_ban") val text: String,
    @SerialName("tu") val words: JsonElement = JsonArray(emptyList()),
    val model: String? = null,
)

/** Ghép bản chép lời (nhánh tắt nhận diện người nói của Stage 4). */
object Transcript {
    /** Giây → `h:mm:ss`, làm tròn nửa về số chẵn như `round()` của Python. Port của transcribe.py: `hms`. */
    fun hms(seconds: Double): String {
        val s = pyRoundToLong(seconds)
        return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    /**
     * Thân bản chép lời: các đoạn cách nhau một dòng trống, đoạn rỗng bị bỏ; [markChunks] thêm dòng
     * `----- Đoạn i/n -----` trước mỗi đoạn (cả đoạn rỗng). Port của transcribe.py: `assemble_transcript`, nhánh
     * `method is None` (số phút theo người nói luôn rỗng).
     */
    fun assemble(results: List<ChunkResult>, markChunks: Boolean): String {
        val blocks = ArrayList<String>()
        val total = results.size
        results.forEachIndexed { i, r ->
            if (markChunks) blocks += "----- Đoạn ${i + 1}/$total -----"
            if (r.text.isNotEmpty()) blocks += r.text
        }
        return blocks.joinToString("\n\n")
    }

    /** {model: số đoạn} theo thứ tự gặp; kết quả không ghi model thì tính cho [defaultModel]. Port của transcribe.py: `count_models`. */
    fun countModels(results: List<ChunkResult>, defaultModel: String): LinkedHashMap<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (r in results) {
            val m = r.model?.takeIf { it.isNotEmpty() } ?: defaultModel
            counts[m] = (counts[m] ?: 0) + 1
        }
        return counts
    }

    /**
     * Đoạn này có ai nói không. Không có bản đồ người nói (Stage 4) thì coi như có, vì đoạn lặng đã bị cắt ở bước âm
     * thanh. Port của transcribe.py: `has_speech`.
     */
    fun hasSpeech(mapText: String?): Boolean = mapText == null || mapText.lines().any { " - " in it }

    private val NOW_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * Phần đầu bản chép lời (`[OUTPUT] add_header`). Port của transcribe.py: `format_header` (chưa có dòng "Người nói",
     * Stage 5).
     *
     * @param models {model: số đoạn}; rỗng thì ghi [defaultModel].
     */
    fun formatHeader(
        isVideo: Boolean,
        displayName: String,
        now: LocalDateTime,
        origDuration: Double,
        models: Map<String, Int>,
        defaultModel: String,
    ): String {
        val modelName = when {
            models.size > 1 -> models.entries.joinToString(", ") { (m, n) -> "$m ($n đoạn)" }
            models.isNotEmpty() -> models.keys.first()
            else -> defaultModel
        }
        return listOf(
            "Tệp ${if (isVideo) "video" else "âm thanh"}: $displayName",
            "Chuyển lúc: ${now.format(NOW_FORMAT)} · Model: $modelName · Thời lượng: ${hms(origDuration)}",
            "-".repeat(60),
        ).joinToString("\n")
    }

    /** Đuôi video mặc định của desktop (`[VIDEO] extensions`), dùng khi provider không cho MIME. */
    val VIDEO_EXTENSIONS = listOf(
        ".mp4", ".mkv", ".mov", ".avi", ".wmv", ".webm", ".m4v", ".mpg", ".mpeg", ".ts", ".flv", ".3gp",
    )

    /** Video hay âm thanh: theo MIME `video/...` nếu có, không thì theo đuôi như `Config.is_video_file` của desktop. */
    fun isVideo(displayName: String, mimeType: String?): Boolean =
        if (!mimeType.isNullOrEmpty()) mimeType.startsWith("video/")
        else pySplitext(displayName).second.lowercase() in VIDEO_EXTENSIONS
}

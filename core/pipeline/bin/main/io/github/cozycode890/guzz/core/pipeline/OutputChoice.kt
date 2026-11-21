package io.github.cozycode890.guzz.core.pipeline

/**
 * Chọn chỗ lưu bản chép lời cho một tệp (Stage 6, D96). Thứ tự: `output_dir` ghi đè của tệp → "Lưu cạnh tệp nguồn"
 * (`[AUTOMATION] save_beside_source`) → thư mục SAF đã chọn (D59). Hai cách đầu ghi bằng đường dẫn, nên chỉ dùng được khi
 * có quyền "Truy cập mọi tệp" (bản sideload); không dùng được thì lùi về SAF và trả kèm lý do để ghi log.
 *
 * Hàm thuần để test trên PC; `QueueController` dựng thư mục thật theo kết quả.
 */
object OutputChoice {
    sealed interface Target

    /** Ghi thẳng vào thư mục [dir] (đường dẫn tuyệt đối). */
    data class Directory(val dir: String) : Target

    /** Ghi vào thư mục SAF đã chọn ([PipelineConfig.outputDir]). */
    data object Saf : Target

    /** [target] và lý do không theo được yêu cầu (`null` = đúng như yêu cầu). */
    data class Choice(val target: Target, val note: String? = null)

    /**
     * @param source nguồn của tệp (đường dẫn tuyệt đối hoặc content:// URI).
     * @param overrideDir `output_dir` của lệnh TRANSCRIBE ([ItemOverrides.outputDir]).
     * @param besideSource cài đặt "Lưu cạnh tệp nguồn".
     * @param allFilesAccess đang có quyền "Truy cập mọi tệp".
     */
    fun choose(source: String, overrideDir: String?, besideSource: Boolean, allFilesAccess: Boolean): Choice {
        val wanted = overrideDir?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (besideSource) ItemOverrides.OUTPUT_BESIDE_SOURCE else null
        wanted ?: return Choice(Saf)
        if (!allFilesAccess) {
            return Choice(Saf, "chưa có quyền \"Truy cập mọi tệp\" nên không ghi được vào \"$wanted\", lưu vào thư mục đã chọn")
        }
        if (wanted == ItemOverrides.OUTPUT_BESIDE_SOURCE) {
            // Cắt chuỗi thay vì File.parent: đường dẫn của Android luôn dùng "/", test chạy trên Windows thì không.
            val parent = source.takeIf { it.startsWith("/") }?.substringBeforeLast('/')?.ifEmpty { "/" }
                ?: return Choice(Saf, "nguồn không phải đường dẫn nên không lưu cạnh tệp nguồn được, lưu vào thư mục đã chọn")
            return Choice(Directory(parent))
        }
        if (!wanted.startsWith("/")) {
            return Choice(Saf, "output_dir \"$wanted\" không phải đường dẫn tuyệt đối, lưu vào thư mục đã chọn")
        }
        return Choice(Directory(wanted.trimEnd('/').ifEmpty { "/" }))
    }
}

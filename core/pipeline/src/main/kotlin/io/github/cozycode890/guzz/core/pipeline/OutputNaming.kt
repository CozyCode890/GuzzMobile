package io.github.cozycode890.guzz.core.pipeline

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Mẫu tên tệp không hợp lệ (`[OUTPUT] filename_template`). */
class TemplateException(message: String) : IllegalArgumentException(message)

/** Đặt tên và ghi bản chép lời. Port các hàm của config.py và transcribe.py, xem KDoc từng hàm. */
object OutputNaming {
    /** Biến trong mẫu tên tệp: tên mới → tên cũ (tên cũ vẫn dùng được). Port của config.py: `FILENAME_FIELDS`. */
    val FILENAME_FIELDS = linkedMapOf(
        "name" to "ten", "recorded" to "ngay_ghi", "date" to "ngay", "time" to "gio", "model" to "model",
    )

    private val RECORDED = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HHmm")

    /**
     * Tên bản chép lời (không kèm thư mục). Port của config.py: `Config.output_filename`.
     *
     * @param sourceName tên tệp nguồn (tên hiển thị của URI, hoặc tên tệp của đường dẫn).
     * @param recordedAt giờ ghi âm: desktop dùng `getctime`; mobile dùng giờ sửa tệp, không có thì [now].
     */
    fun outputFilename(
        template: String,
        sourceName: String,
        recordedAt: LocalDateTime,
        now: LocalDateTime,
        model: String,
        extension: String,
    ): String {
        val values = mapOf(
            "name" to pySplitext(sourceName.substringAfterLast('/')).first,
            "recorded" to recordedAt.format(RECORDED),
            "date" to now.format(DATE),
            "time" to now.format(TIME),
            "model" to model,
        )
        return sanitizeFilename(format(template, templateValues(values))) + extension
    }

    /** Lỗi của mẫu tên tệp, `null` nếu dùng được. Port của config.py: phần kiểm `filename_template` của `validate_config`. */
    fun templateError(template: String): String? = try {
        format(template, templateValues(FILENAME_FIELDS.keys.associateWith { "x" }))
        null
    } catch (e: TemplateException) {
        "[OUTPUT] filename_template = '$template' không hợp lệ (${e.message}). " +
            "Dùng các biến {name} {recorded} {date} {time} {model}."
    }

    /** Giá trị cho cả tên mới lẫn tên cũ của mỗi biến. Port của config.py: `_template_values`. */
    private fun templateValues(values: Map<String, String>): Map<String, String> =
        values + FILENAME_FIELDS.mapNotNull { (new, old) -> values[new]?.let { old to it } }

    /**
     * `str.format(**values)` của Python, chỉ phần mẫu tên tệp cần: `{biến}`, `{{` và `}}`. Khác desktop: `{biến!r}`,
     * `{biến:định dạng}`, `{biến[0]}`, `{biến.thuộc_tính}` bị coi là mẫu sai, vì cần cả bộ máy định dạng của Python mà
     * không ai dùng trong tên tệp.
     */
    fun format(template: String, values: Map<String, String>): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                c == '{' && template.getOrNull(i + 1) == '{' -> { out.append('{'); i += 2 }
                c == '}' && template.getOrNull(i + 1) == '}' -> { out.append('}'); i += 2 }
                c == '}' -> throw TemplateException("dấu '}' lẻ")
                c == '{' -> {
                    val end = template.indexOf('}', i + 1)
                    if (end < 0) throw TemplateException("thiếu dấu '}'")
                    val field = template.substring(i + 1, end)
                    if (field.isEmpty() || field[0].isDigit()) throw TemplateException("biến không tên: {$field}")
                    if (field.any { it in "{!:.[" }) throw TemplateException("không hỗ trợ {$field}")
                    out.append(values[field] ?: throw TemplateException("không có biến {$field}"))
                    i = end + 1
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private const val FORBIDDEN_CHARS = "<>:\"/\\|?*"

    /** Bỏ các ký tự Windows không cho đặt tên tệp, khoảng trắng và dấu chấm hai đầu. Port của config.py: `sanitize_filename`. */
    fun sanitizeFilename(name: String): String {
        var s = name
        for (c in FORBIDDEN_CHARS) s = s.replace(c, '-')
        return s.pyStrip().trim('.').ifEmpty { "transcript" }
    }

    /**
     * Tên không trùng với [existing]: `bai giang (2).txt`, `(3)`… Port của transcribe.py: `unique_path`, nhưng so trên
     * danh sách tên của thư mục (SAF liệt kê một lần) thay vì hỏi hệ thống tệp. So không phân biệt hoa thường như hệ
     * thống tệp của desktop (Windows) và của bộ nhớ dùng chung trên Android.
     */
    fun uniqueName(name: String, existing: Collection<String>): String {
        val taken = existing.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in taken) return name
        val (base, ext) = pySplitext(name)
        var i = 2
        while ("$base ($i)$ext".lowercase() in taken) i++
        return "$base ($i)$ext"
    }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /**
     * Bytes ghi ra tệp. Port của transcribe.py: `write_text` (phần nội dung): `crlf` đổi mọi xuống dòng thành `\r\n`
     * (không nhân đôi `\r\n` có sẵn), `utf-8-sig` thêm BOM (kể cả khi nội dung rỗng, như Python).
     */
    fun encodeText(content: String, encoding: String, newline: String): ByteArray {
        val text = if (newline == "crlf") content.replace("\r\n", "\n").replace("\n", "\r\n") else content
        val bytes = text.toByteArray(Charsets.UTF_8)
        return if (encoding == "utf-8-sig") UTF8_BOM + bytes else bytes
    }
}

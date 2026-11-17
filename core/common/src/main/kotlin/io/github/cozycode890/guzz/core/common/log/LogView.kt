package io.github.cozycode890.guzz.core.common.log

import java.io.File
import java.io.RandomAccessFile

/**
 * Đọc và lọc `guzz.log` cho trang Nhật ký (Stage 8 D3). Port phần lọc của gui/pages/logs.py (`_line_level`,
 * `_is_shown`).
 */
object LogView {
    /** Một bản ghi: dòng đầu `thời gian | MỨC | lời` và các dòng tiếp theo (stack trace) nếu có. */
    data class Entry(val text: String, val level: LogLevel)

    private val START = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} \|""")

    /**
     * Mức của một dòng như `_line_level` của desktop: cột thứ hai giữa hai dấu `|`; không phải WARNING / ERROR (hay
     * CRITICAL) thì coi là INFO. DEBUG cũng tính là INFO, như desktop (bộ lọc chỉ có Tất cả / Cảnh báo / Lỗi).
     */
    fun lineLevel(line: String): LogLevel {
        val parts = line.split("|")
        if (parts.size <= 2) return LogLevel.INFO
        return when (parts[1].trim()) {
            "WARNING" -> LogLevel.WARNING
            "ERROR", "CRITICAL" -> LogLevel.ERROR
            else -> LogLevel.INFO
        }
    }

    /** Gom dòng thành bản ghi: dòng không bắt đầu bằng thời gian (stack trace) đi theo bản ghi trước. */
    fun entries(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                val body = current.toString().trimEnd()
                out += Entry(body, lineLevel(body.substringBefore('\n')))
                current.clear()
            }
        }
        for (line in text.lines()) {
            if (START.containsMatchIn(line)) flush()
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        flush()
        return out.filter { it.text.isNotBlank() }
    }

    /** Lọc như `_is_shown` của desktop: mức ≥ [minLevel] và (nếu có) chứa [query], không phân biệt hoa thường. */
    fun filter(entries: List<Entry>, minLevel: LogLevel, query: String): List<Entry> {
        val q = query.trim().lowercase()
        return entries.filter { it.level.priority >= minLevel.priority && (q.isEmpty() || it.text.lowercase().contains(q)) }
    }

    /**
     * Đọc tối đa [maxBytes] cuối của [file] (tệp log có thể vài MB); cắt bỏ dòng dở ở đầu. Thiếu tệp thì chuỗi rỗng.
     */
    fun readTail(file: File, maxBytes: Long): String {
        if (!file.exists()) return ""
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val start = (len - maxBytes).coerceAtLeast(0)
            // Bắt đầu đúng đầu dòng (byte trước là xuống dòng) thì giữ dòng đầu, không thì bỏ phần dòng dở.
            val atLineStart = start == 0L || raf.run { seek(start - 1); read() == '\n'.code }
            raf.seek(start)
            val bytes = ByteArray((len - start).toInt())
            raf.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            return if (atLineStart) text else text.substringAfter('\n', "")
        }
    }
}

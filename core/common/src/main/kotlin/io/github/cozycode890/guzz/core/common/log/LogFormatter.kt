package io.github.cozycode890.guzz.core.common.log

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Viết bản ghi ra một dòng chữ, cùng mẫu với desktop (transcribe.py: `setup_logging`):
 * `"%(asctime)s | %(levelname)-7s | %(message)s"`, giờ địa phương `yyyy-MM-dd HH:mm:ss`.
 *
 * Có lỗi kèm theo thì stack trace nằm ở các dòng sau, như `logging.Formatter` in traceback.
 */
class LogFormatter(zone: ZoneId = ZoneId.systemDefault()) {
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)

    fun format(record: LogRecord): String {
        val line = "${timeFormat.format(record.time)} | ${record.level.name.padEnd(7)} | ${record.message}"
        val error = record.error ?: return line
        return line + "\n" + error.stackTraceToString().trimEnd()
    }
}

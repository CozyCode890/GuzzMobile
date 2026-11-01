package io.github.cozycode890.guzz.core.common.log

import java.util.Collections

/** Giữ bản ghi trong bộ nhớ, cho test. */
class MemorySink(private val formatter: LogFormatter = LogFormatter()) : LogSink {
    private val _records = Collections.synchronizedList(mutableListOf<LogRecord>())

    val records: List<LogRecord> get() = synchronized(_records) { _records.toList() }

    /** Các bản ghi đã viết ra chữ như trong tệp log. */
    val lines: List<String> get() = records.map(formatter::format)

    override fun write(record: LogRecord) {
        _records += record
    }

    fun clear() = _records.clear()
}

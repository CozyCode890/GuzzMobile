package io.github.cozycode890.guzz.core.common.log

import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Mức log, tên in ra giống `logging` của Python để tệp log đọc như log của desktop. */
enum class LogLevel(val priority: Int) {
    DEBUG(10),
    INFO(20),
    WARNING(30),
    ERROR(40),
    ;

    companion object {
        /** Đọc giá trị của `SYSTEM.log_level` (`INFO` / `DEBUG`); tên lạ thì lấy [INFO] như config.py. */
        fun parse(name: String): LogLevel = entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: INFO
    }
}

class LogRecord(
    val time: Instant,
    val level: LogLevel,
    val message: String,
    val error: Throwable? = null,
)

/** Nơi nhận bản ghi: Logcat, tệp, bộ nhớ (test)… Một sink lỗi không được làm hỏng các sink khác. */
fun interface LogSink {
    fun write(record: LogRecord)
}

/**
 * Giao diện log mà mọi class dùng. Class nào cần log thì nhận [Logger] qua constructor, mặc định [GuzzLog], để test
 * truyền logger riêng vào.
 */
interface Logger {
    fun isEnabled(level: LogLevel): Boolean

    fun log(level: LogLevel, message: String, error: Throwable? = null)

    fun debug(message: String) = log(LogLevel.DEBUG, message)

    fun info(message: String) = log(LogLevel.INFO, message)

    fun warning(message: String, error: Throwable? = null) = log(LogLevel.WARNING, message, error)

    fun error(message: String, error: Throwable? = null) = log(LogLevel.ERROR, message, error)
}

/** [Logger] chuyển bản ghi tới các [LogSink] đã gắn. Bản ghi dưới [level] bị bỏ trước khi tới sink. */
open class SinkLogger(private val clock: Clock = Clock.systemDefaultZone()) : Logger {
    @Volatile
    var level: LogLevel = LogLevel.INFO

    private val sinks = CopyOnWriteArrayList<LogSink>()

    fun addSink(sink: LogSink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: LogSink) {
        sinks.remove(sink)
    }

    override fun isEnabled(level: LogLevel) = level.priority >= this.level.priority

    override fun log(level: LogLevel, message: String, error: Throwable?) {
        if (!isEnabled(level)) return
        val record = LogRecord(clock.instant(), level, message, error)
        for (sink in sinks) {
            try {
                sink.write(record)
            } catch (e: Exception) {
                // Như logging.Handler.handleError của Python: báo ra stderr rồi bỏ qua, log hỏng không làm app chết.
                System.err.println("Guzz: sink ${sink.javaClass.simpleName} lỗi: $e")
            }
        }
    }
}

/** Logger toàn cục của app. Không đặt tên `Log` cho khỏi trùng `android.util.Log`. */
object GuzzLog : SinkLogger()

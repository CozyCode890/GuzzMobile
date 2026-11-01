package io.github.cozycode890.guzz

import android.os.Build
import android.util.Log
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.log.LogLevel
import io.github.cozycode890.guzz.core.common.log.LogRecord
import io.github.cozycode890.guzz.core.common.log.LogSink
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Nối [GuzzLog] với Logcat và tệp log của app (D14, D15). */
object AppLogging {

    fun start(container: AppContainer) {
        GuzzLog.addSink(LogcatSink)
        GuzzLog.addSink(container.logFileSink)
        installCrashHandler()

        GuzzLog.info(
            "Guzz ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}/${BuildConfig.BUILD_TYPE}) khởi động trên " +
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )

        // Đọc Settings ở nền rồi mới áp: không đọc DataStore chặn luồng chính lúc mở app. Trước đó dùng mặc định.
        val settings = container.settings
        container.appScope.launch {
            settings.flow(SettingKeys.LOG_LEVEL)
                .catch { GuzzLog.error("Không đọc được SYSTEM.log_level, giữ mức INFO", it) }
                .collect { GuzzLog.level = LogLevel.parse(it) }
        }
        container.appScope.launch {
            combine(settings.flow(SettingKeys.LOG_SIZE_MB), settings.flow(SettingKeys.OLD_LOG_COUNT)) { mb, count ->
                mb to count
            }
                .catch { GuzzLog.error("Không đọc được cài đặt dung lượng nhật ký, giữ mặc định", it) }
                .collect { (mb, count) -> container.logFileSink.configure(mb * AppContainer.BYTES_PER_MB, count) }
        }
    }

    /** Lỗi chưa ai bắt: ghi ERROR kèm stack trace vào log, rồi để handler cũ của hệ thống xử lý (đóng app như thường). */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                GuzzLog.error("Lỗi chưa xử lý ở luồng ${thread.name}", error)
            } catch (_: Throwable) {
                // Ghi log hỏng cũng không được nuốt mất lỗi gốc.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

/** Ghi ra Logcat, tag `Guzz`. Logcat tự có giờ nên chỉ ghi nội dung. */
internal object LogcatSink : LogSink {
    private const val TAG = "Guzz"

    override fun write(record: LogRecord) {
        val priority = when (record.level) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARNING -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        val text = record.error?.let { "${record.message}\n${it.stackTraceToString()}" } ?: record.message
        Log.println(priority, TAG, text)
    }
}

package io.github.cozycode890.guzz

import android.app.Application
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.feature.queue.QueueController
import io.github.cozycode890.guzz.feature.queue.QueueHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class GuzzApp : Application(), QueueHost {
    lateinit var container: AppContainer
        private set

    override val queueController: QueueController get() = container.queueController

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Gắn log trước mọi việc khác để lỗi lúc khởi động cũng vào tệp.
        AppLogging.start(container)
        recordAppVersion()
    }

    /** Ghi versionCode của lần mở này (D27). Chạy nền: không đọc / ghi DataStore trên luồng chính. */
    private fun recordAppVersion() {
        val current = BuildConfig.VERSION_CODE
        container.appScope.launch {
            try {
                when (val previous = container.settings.recordAppVersion(current)) {
                    current -> Unit
                    0 -> GuzzLog.info("Lần mở đầu tiên trên máy này (versionCode $current)")
                    else -> GuzzLog.info("Đã cập nhật từ versionCode $previous lên $current")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Không ghi được thì app vẫn chạy với cài đặt mặc định; không để lỗi này làm sập app.
                GuzzLog.error("Không ghi được APP.last_version_code", e)
            }
        }
    }
}

package io.github.cozycode890.guzz

import android.content.Context
import io.github.cozycode890.guzz.core.common.log.RotatingFileSink
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.core.gemini.ApiKeyStore
import io.github.cozycode890.guzz.core.gemini.GeminiClient
import io.github.cozycode890.guzz.core.gemini.QuotaTracker
import io.github.cozycode890.guzz.core.gemini.RateLimiters
import io.github.cozycode890.guzz.feature.queue.FakeTranscriber
import io.github.cozycode890.guzz.feature.queue.QueueController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File

/**
 * Các đối tượng dùng chung của app, dựng tay (D13): không thư viện DI, module JVM thuần chỉ nhận tham số qua
 * constructor. Sống cùng [GuzzApp].
 */
class AppContainer(appContext: Context) {
    private val context: Context = appContext.applicationContext

    /** Scope sống cùng app, cho việc nền không gắn với màn hình nào. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: Settings = Settings.create(File(context.filesDir, "datastore/settings.preferences_pb"))

    /**
     * `Android/data/<package>/files/logs/guzz.log` (D15): không cần quyền, lấy được bằng `adb pull` hoặc cắm USB, kể cả
     * bản release. Bộ nhớ ngoài không dùng được (hiếm) thì lùi về bộ nhớ trong của app.
     */
    val logFile: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs/guzz.log")

    /** Mở với giá trị mặc định; [AppLogging] cập nhật theo Settings khi đọc xong. */
    val logFileSink = RotatingFileSink(
        logFile,
        maxBytes = SettingKeys.LOG_SIZE_MB.default * BYTES_PER_MB,
        backupCount = SettingKeys.OLD_LOG_COUNT.default,
    )

    /** API key Google AI, mã hoá bằng khoá trong Android Keystore (D54, D55). */
    val apiKeyStore: ApiKeyStore = KeystoreApiKeyStore(File(context.filesDir, "secrets/api_key.bin"))

    /** Sổ hạn mức của cả app (D52): đọc tệp lần đầu khi cần, không ở lúc mở app. */
    val quotaTracker: QuotaTracker by lazy { QuotaTracker(File(context.filesDir, "quota/${QuotaTracker.FILENAME}")) }

    /** Mỗi model một bộ giãn nhịp, dùng chung mọi client trong app (như `model_rate_limiter` của desktop). */
    val rateLimiters = RateLimiters()

    /** Một OkHttpClient cho cả app (dùng chung pool kết nối). */
    val httpClient: OkHttpClient by lazy { GeminiClient.defaultHttpClient() }

    /** Client Gemini với key đã lưu; `null` khi chưa có key. */
    fun geminiClient(
        apiKey: String? = apiKeyStore.read(),
        http: OkHttpClient = httpClient,
        timeoutSeconds: Int = SettingKeys.TIMEOUT_SECONDS.default,
    ): GeminiClient? =
        apiKey?.takeIf { it.isNotBlank() }?.let {
            GeminiClient(it, quotaTracker, rateLimiters, http, timeoutSeconds = timeoutSeconds.toDouble())
        }

    /**
     * Chỉ bản debug: có tệp này thì hàng đợi dùng Gemini giả ([FakeTranscriber]), không gọi mạng. Bật / tắt bằng lệnh adb
     * (`queue_debug.ps1 -Fake on|off`). Bản release không bao giờ đọc tệp này.
     */
    val fakeGeminiFlag = File(context.filesDir, "debug/gemini_gia")

    fun fakeGeminiEnabled(): Boolean = BuildConfig.DEBUG && fakeGeminiFlag.exists()

    /** Hàng đợi của cả app (Stage 4). Client Gemini dựng lại mỗi tệp, đọc lại key và timeout như desktop. */
    val queueController: QueueController by lazy {
        QueueController(
            context, settings, { quotaTracker },
            clientFactory = { cfg ->
                if (fakeGeminiEnabled()) FakeTranscriber() else geminiClient(timeoutSeconds = cfg.gemini.timeoutSeconds)
            },
            // Bản debug: chưa chọn thư mục SAF thì lệnh adb ghi vào files/output (D2).
            fallbackOutput = if (BuildConfig.DEBUG) File(context.getExternalFilesDir(null) ?: context.filesDir, "output") else null,
            // Kết quả đoạn của Gemini giả lưu dưới mã riêng: tắt giả rồi chạy thật thì không dùng lại "Đoạn i." giả.
            cacheSalt = { if (fakeGeminiEnabled()) "gemini_gia" else "" },
        )
    }

    companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}

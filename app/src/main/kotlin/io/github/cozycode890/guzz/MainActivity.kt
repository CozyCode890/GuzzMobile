package io.github.cozycode890.guzz

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.ui.nav.GuzzRoot
import io.github.cozycode890.guzz.ui.nav.Routes
import io.github.cozycode890.guzz.ui.theme.GuzzTheme
import io.github.cozycode890.guzz.ui.theme.isDarkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Activity duy nhất của giao diện (D114): mọi trang là route trong [GuzzRoot]. AppCompatActivity để đổi ngôn ngữ trong
 * app (D115); chủ đề tính trong Compose nên đổi Sáng / Tối không dựng lại Activity.
 */
class MainActivity : AppCompatActivity() {
    /** Tăng mỗi lần màn hình hiện lại, để dải báo (key, tối ưu pin) đọc lại sau khi quay về từ app khác. */
    private val resumes = MutableStateFlow(0)

    /** Trang cần mở thẳng (extra [EXTRA_ROUTE]); `null` khi không có. */
    private val openRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as GuzzApp).container
        // Ngôn ngữ có thể đã đổi ở Cài đặt hệ thống (Android 13+): chép về GUI.language để xuất / nhập đúng.
        lifecycleScope.launch { AppLanguage.syncToSettings(container.settings) }
        if (savedInstanceState == null) handleIntent(intent)
        val dynamicColor = !(BuildConfig.DEBUG && File(filesDir, NO_DYNAMIC_COLOR_FLAG).exists())
        setContent {
            val themeMode by container.settings.flow(SettingKeys.THEME).collectAsState(initial = null)
            val mode = themeMode ?: return@setContent
            val dark = isDarkTheme(mode)
            DisposableEffect(dark) {
                // Màu biểu tượng thanh trạng thái / điều hướng theo chủ đề của app, không theo hệ thống.
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            GuzzTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                Surface {
                    GuzzRoot(container, resumes, openRoute) { openRoute.value = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, fromNewIntent = true)
    }

    /**
     * Extra [EXTRA_ROUTE] (bản debug, `ui_check.ps1`) mở thẳng trang đó. Intent launcher tới khi app đang mở (Chia sẻ tới
     * Guzz, thông báo "Bấm để bắt đầu" của Stage 6, thông báo tiến độ: đều dùng `getLaunchIntentForPackage`) thì về tab
     * Chuyển đổi để thấy hàng đợi (Stage 8 H4); `singleTop` nên không mở chồng màn hình mới.
     *
     * Bản release bỏ qua [EXTRA_ROUTE] (Stage 9 B1): MainActivity exported, app nào cũng gửi được extra này, và route lạ
     * làm Navigation ném lỗi.
     */
    private fun handleIntent(intent: Intent?, fromNewIntent: Boolean = false) {
        val route = intent?.getStringExtra(EXTRA_ROUTE)?.takeIf { BuildConfig.DEBUG }
            ?: Routes.CONVERT.takeIf {
                fromNewIntent && intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
            }
        route?.let { openRoute.value = it }
    }

    /**
     * D64: hàng đợi bị giết / bị hệ thống dừng (`onTimeout`) khi đang chạy dở thì mở app là làm tiếp. Ở `onResume` chứ
     * không ở `onCreate`: sau `onTimeout` màn hình này thường vẫn còn, mở lại app chỉ gọi `onResume`. Gọi nhiều lần không
     * sao: chỉ chạy khi cờ đang chạy còn bật và chưa có vòng chạy nào.
     */
    override fun onResume() {
        super.onResume()
        resumes.value++
        (application as GuzzApp).container.queueController.resumeIfNeeded()
    }

    companion object {
        /** Extra: tên trang (`Routes`) để mở thẳng, vd `settings/google_ai` (thử giao diện bằng adb, Stage 8 A5). */
        const val EXTRA_ROUTE = "route"

        /** Chỉ bản debug: có tệp này thì tắt màu động, xem bộ màu Guzz dự phòng (D111). */
        const val NO_DYNAMIC_COLOR_FLAG = "debug/no_dynamic_color"

        // Như mặc định của enableEdgeToEdge: thanh điều hướng 3 nút có nền mờ.
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

/** URI để mở / chia sẻ bản chép lời: URI SAF giữ nguyên, đường dẫn (thư mục ra của bản debug) đi qua FileProvider. */
fun shareableUri(context: Context, location: String): Uri =
    if (location.startsWith("/")) {
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(location))
    } else {
        location.toUri()
    }

/** Mở bản chép lời bằng app khác (D61). */
fun openWithOtherApp(context: Context, location: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(shareableUri(context, location), "text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null))
    }.onFailure { GuzzLog.warning("Không mở được $location bằng app khác: ${it.message}") }
}

/** Chia sẻ tệp .txt (D62): `ACTION_SEND` kèm URI, cấp quyền đọc cho app nhận. */
fun shareTranscript(context: Context, location: String, name: String?) {
    shareFile(context, location, name, "text/plain")
}

/** Chia sẻ một tệp (bản chép lời, tệp nhật ký): `ACTION_SEND` kèm URI, cấp quyền đọc cho app nhận. */
fun shareFile(context: Context, location: String, name: String?, mimeType: String) {
    runCatching {
        // getUriForFile ném lỗi khi đường dẫn nằm ngoài thư mục đã khai cho FileProvider: bắt chung, không làm sập app.
        val uri = shareableUri(context, location)
        val intent = Intent(Intent.ACTION_SEND).setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData để quyền đọc đi theo cả qua màn hình chọn app.
        intent.clipData = ClipData.newRawUri(name, uri)
        context.startActivity(Intent.createChooser(intent, null))
    }.onFailure { GuzzLog.warning("Không chia sẻ được $location: ${it.message}") }
}

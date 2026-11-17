package io.github.cozycode890.guzz.feature.automation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val entryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private val Context.automation: Automation get() = (applicationContext as AutomationHost).automation

/**
 * Lối nhận lệnh chính (D94): MacroDroid "Gửi Intent" → Target Broadcast, Package của Guzz, Class này. Android 12+ chỉ cho
 * khởi động foreground service từ đây khi Guzz đã được miễn tối ưu pin (D69); bị chặn thì tệp vẫn trong hàng đợi (D102).
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        entryScope.launch {
            try {
                context.automation.handle(intent, "broadcast")
            } catch (e: Exception) {
                GuzzLog.error("Lệnh ${GuzzIntents.shortName(intent.action)} (broadcast) lỗi: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Lối dự phòng (D94): activity trong suốt, không vẽ gì. Activity hiện lên nên app được coi là ở tiền cảnh và khởi động
 * được service dù chưa miễn tối ưu pin; MacroDroid cần quyền "Hiển thị trên ứng dụng khác" để mở activity từ nền.
 */
class CommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        entryScope.launch {
            try {
                automation.handle(intent, "activity")
            } catch (e: Exception) {
                GuzzLog.error("Lệnh ${GuzzIntents.shortName(intent.action)} (activity) lỗi: ${e.message}", e)
            } finally {
                withContext(Dispatchers.Main) { finishQuietly() }
            }
        }
    }
}

/**
 * Chia sẻ tới Guzz (D98): `ACTION_SEND` / `ACTION_SEND_MULTIPLE` cho tệp âm thanh và video. Thêm vào hàng đợi, bắt đầu
 * ngay, rồi mở màn hình chính. Activity giữ tới khi đã giữ quyền / chép xong tệp (D103): quyền đọc URI được chia sẻ có
 * thể hết khi Activity đóng.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            GuzzLog.warning("Chia sẻ tới Guzz: không có tệp (${intent.action}, ${intent.type}).")
            finishQuietly()
            return
        }
        entryScope.launch {
            val outcome = try {
                automation.share(uris)
            } catch (e: Exception) {
                GuzzLog.error("Chia sẻ tới Guzz lỗi: ${e.message}", e)
                Automation.ShareOutcome(0, uris.map { it.toString() }, e.message)
            }
            withContext(Dispatchers.Main) {
                val text = when {
                    outcome.problem != null -> getString(R.string.share_problem, outcome.problem)
                    outcome.failed.isNotEmpty() -> getString(R.string.share_failed, outcome.failed.joinToString(", "))
                    else -> getString(R.string.share_added, outcome.added)
                }
                Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
                finishQuietly()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> (
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                )?.let(out::add)
            Intent.ACTION_SEND_MULTIPLE -> (
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                )?.let(out::addAll)
        }
        // App chia sẻ đặt URI ở ClipData (để được cấp quyền); vài app / lệnh adb đặt ở data.
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(out::add) }
        intent.data?.let(out::add)
        return out.toList()
    }
}

/** Đóng không hiệu ứng chuyển cảnh (activity trong suốt không được nháy). */
private fun Activity.finishQuietly() {
    finish()
    @Suppress("DEPRECATION")
    overridePendingTransition(0, 0)
}

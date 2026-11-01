package io.github.cozycode890.guzz.spike.automation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import io.github.cozycode890.guzz.spike.SpikeApp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * FGS giả làm việc nền của Guzz (G1, G4, G5): chạy `minutes` phút (mặc định 3), cứ 30 s ghi một nhịp kèm kết quả
 * `GET generate_204` để biết trong Doze có mạng không. Xong thì phát broadcast ngầm [AutomationLog.EVENT_DONE] (extra
 * `request_id`) cho MacroDroid.
 */
class HeartbeatService : Service() {
    @Volatile private var stopAt = 0L
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val method = intent?.getStringExtra("method")
        val requestId = intent?.getStringExtra("request_id")
        val minutes = intent?.extras?.get("minutes")?.toString()?.toIntOrNull() ?: 3
        try {
            SpikeApp.startForeground(this, NOTIFICATION_ID,
                SpikeApp.notification(this, "Guzz spike", "Heartbeat $minutes phút ($method)"))
        } catch (e: Exception) {
            AutomationLog.write(this, "fgs_loi", method, requestId, JSONObject().put("loi", e.toString()))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running) {
            AutomationLog.write(this, "fgs_ok_dang_chay", method, requestId)
            return START_NOT_STICKY
        }
        running = true
        stopAt = 0L
        AutomationLog.write(this, "fgs_ok", method, requestId, JSONObject().put("minutes", minutes))
        thread(name = "heartbeat") {
            val ok = beat(this, method, requestId, minutes) { stopAt }
            running = false
            sendDone(this, requestId, method, ok)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val t = SystemClock.elapsedRealtime()
        AutomationLog.write(this, "onTimeout", null, null, JSONObject().put("start_id", startId).put("fgs_type", fgsType))
        stopAt = t
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        AutomationLog.write(this, "onTimeout_da_dung", null, null,
            JSONObject().put("ms_tu_onTimeout", SystemClock.elapsedRealtime() - t))
    }

    override fun onDestroy() {
        AutomationLog.write(this, "service_destroy", null, null)
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 2
        private const val BEAT_MS = 30_000L

        /**
         * Chạy [minutes] phút, mỗi 30 s một nhịp; giữ partial wake lock như Guzz thật sẽ phải làm. Trả về true nếu chạy
         * hết giờ, false nếu bị dừng (onTimeout). Dùng chung cho ExpeditedWorker.
         */
        fun beat(ctx: Context, method: String?, requestId: String?, minutes: Int, stopAt: () -> Long): Boolean {
            val wl = ctx.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GuzzSpike:heartbeat")
                .apply { acquire(minutes * 60_000L + 60_000L) }
            val start = SystemClock.elapsedRealtime()
            var n = 0
            try {
                while (SystemClock.elapsedRealtime() - start < minutes * 60_000L) {
                    if (stopAt() != 0L) return false
                    AutomationLog.write(ctx, "nhip", method, requestId, JSONObject()
                        .put("n", n++)
                        .put("giay", (SystemClock.elapsedRealtime() - start) / 1000)
                        .put("wakelock", wl.isHeld)
                        .put("mang", checkNetwork()))
                    var slept = 0L
                    while (slept < BEAT_MS && stopAt() == 0L) {
                        Thread.sleep(1000)
                        slept += 1000
                    }
                }
                return true
            } finally {
                if (wl.isHeld) wl.release()
            }
        }

        private fun checkNetwork(): String = try {
            val c = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            try {
                "http ${c.responseCode}"
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            e.javaClass.simpleName + ": " + e.message
        }

        fun sendDone(ctx: Context, requestId: String?, method: String?, completed: Boolean) {
            // Broadcast ngầm: MacroDroid đăng ký receiver lúc chạy nên vẫn nhận được trên Android 8+.
            ctx.sendBroadcast(Intent(AutomationLog.EVENT_DONE)
                .putExtra("request_id", requestId)
                .putExtra("method", method)
                .putExtra("completed", completed))
            AutomationLog.write(ctx, "done_gui", method, requestId, JSONObject().put("completed", completed))
        }
    }
}

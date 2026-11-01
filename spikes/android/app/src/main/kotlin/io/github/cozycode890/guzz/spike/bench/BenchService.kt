package io.github.cozycode890.guzz.spike.bench

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import io.github.cozycode890.guzz.spike.SpikeApp
import io.github.cozycode890.guzz.spike.automation.HeartbeatService
import kotlin.concurrent.thread

/**
 * FGS chạy một phép đo theo extra của intent (`bench` = tên, các extra khác là tham số). Loại `mediaProcessing` trên
 * API 35+, `dataSync` dưới đó. Giữ partial wake lock trong lúc đo, vì FGS không giữ CPU thức khi màn hình tắt.
 * `bench=stop` đặt cờ Dừng cho phép đo đang chạy (D7).
 */
open class BenchService : Service() {
    @Volatile private var busy = false
    @Volatile private var stopAt = 0L
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val args = intent?.extras ?: android.os.Bundle()
        val name = args.getString("bench") ?: "noop"
        try {
            SpikeApp.startForeground(this, NOTIFICATION_ID, SpikeApp.notification(this, "Guzz spike", "Đo $name"))
        } catch (e: Exception) {
            // IsolatedBenchService được gọi bằng startService nên lên FGS không được thì vẫn chạy như service thường.
            BenchLog.of(this, name).write("su_kien" to "khong_len_fgs", "loi" to e.toString())
        }
        if (name == "stop") {
            stopAt = SystemClock.elapsedRealtime()
            BenchLog.of(this, "stop").write("su_kien" to "yeu_cau_dung", "dang_chay" to busy)
            if (!busy) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (busy) {
            BenchLog.of(this, name).write("su_kien" to "bo_qua", "ly_do" to "đang chạy phép đo khác")
            return START_NOT_STICKY
        }
        busy = true
        stopAt = 0L
        val wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GuzzSpike:bench")
            .apply { acquire(4 * 60 * 60 * 1000L) }
        thread(name = "bench-$name") {
            try {
                val end = BenchRunner.run(this, args, ::notifyProgress) { stopAt }
                // Do MacroDroid khởi động (G4 / D6) thì báo xong như HeartbeatService.
                args.getString("request_id")?.let {
                    HeartbeatService.sendDone(this, it, args.getString("method"), end?.isNull("loi") == true)
                }
            } finally {
                if (wl.isHeld) wl.release()
                busy = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notifyProgress(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 2000) return
        lastNotify = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, SpikeApp.notification(this, "Guzz spike", text))
    }

    /** Android 15+: hết hạn mức 6 giờ / 24 giờ của loại FGS. Phải dừng trong vài giây, không thì app bị báo lỗi. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        BenchLog.of(this, "timeout").write("su_kien" to "onTimeout", "service" to javaClass.simpleName,
            "start_id" to startId, "fgs_type" to fgsType)
        stopAt = SystemClock.elapsedRealtime()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val NOTIFICATION_ID = 1
    }
}

/** Như [BenchService] nhưng chạy trong process `:diarize` (manifest), để thử giết process đó giữa chừng (D7 cách 3). */
class IsolatedBenchService : BenchService()

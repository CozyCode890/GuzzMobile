package io.github.cozycode890.guzz.spike

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.app.Notification
import io.github.cozycode890.guzz.spike.bench.BenchLog
import io.github.cozycode890.guzz.spike.bench.json

class SpikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Đo spike", NotificationManager.IMPORTANCE_LOW),
        )
        // Lỗi làm chết process (kể cả OOM ở luồng đo) vẫn để lại dấu vết trong results/crash.jsonl.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                BenchLog.of(this, "crash").write("luong" to t.name, "loi" to e.toString(), "stack" to e.stackTraceToString())
            }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        const val CHANNEL = "spike"

        fun notification(ctx: Context, title: String, text: String): Notification =
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()

        /** Loại FGS của Guzz: `mediaProcessing` từ API 35 (có hạn 6 giờ / 24 giờ riêng), dưới đó `dataSync`. */
        val fgsType: Int
            get() = if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }

        fun startForeground(service: Service, id: Int, n: Notification) {
            if (Build.VERSION.SDK_INT >= 29) service.startForeground(id, n, fgsType) else service.startForeground(id, n)
        }
    }
}

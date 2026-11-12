package io.github.cozycode890.guzz.feature.queue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.pipeline.ItemStatus
import io.github.cozycode890.guzz.core.pipeline.QueueItem
import io.github.cozycode890.guzz.core.pipeline.QueueSummary
import io.github.cozycode890.guzz.core.pipeline.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Application cung cấp [QueueController] cho service (dựng tay, D13). */
interface QueueHost {
    val queueController: QueueController
}

/**
 * Foreground service chạy hàng đợi lúc tắt màn hình (Android 15+: loại `mediaProcessing`; 10–14: `dataSync`), giữ partial
 * wake lock suốt lúc chạy (Stage 1: FGS không giữ CPU thức). Thông báo tiến độ có nút Dừng, chạm mở app; xong thì thông
 * báo tổng kết (`[QUEUE] notify_when_done`).
 *
 * - `onTimeout` (hết hạn mức thời gian FGS của Android 15): dừng như bấm Dừng nhưng giữ cờ đang chạy, mở app thì làm tiếp
 *   (D64).
 * - `START_STICKY`: hệ thống khởi động lại service sau khi giết tiến trình thì làm tiếp nếu hàng đợi đang chạy dở.
 * - Nhịp log mỗi 60 s (tệp, bước, đoạn, màn hình, Doze, mạng, pin) để thử chạy dài; bước âm thanh không tiến độ quá 60 s
 *   thì cảnh báo cài đặt pin (HyperOS tắt wake lock của app bị hạn chế pin, Phát sinh Stage 2).
 */
class QueueService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchers: Job? = null
    private var stalled = false

    private val controller: QueueController get() = (application as QueueHost).queueController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            return START_NOT_STICKY
        }
        if (controller.active.value) return START_STICKY
        if (intent == null && !controller.shouldResume()) {
            // Hệ thống khởi động lại service nhưng không còn gì để làm.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startInForeground(buildNotification(null))) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) {
            GuzzLog.info("Hệ thống khởi động lại service hàng đợi (START_STICKY, PID ${Process.myPid()}): làm tiếp.")
        } else {
            GuzzLog.info("Service hàng đợi bắt đầu (PID ${Process.myPid()}).")
        }
        acquireWakeLock()
        startWatchers()
        controller.launchRun(scope) { summary, error ->
            scope.launch {
                val notify = runCatching { controller.notifyWhenDone() }.getOrDefault(true)
                withContext(Dispatchers.Main) { finish(summary, error, notify) }
            }
        }
        return START_STICKY
    }

    private fun finish(summary: QueueSummary?, error: Throwable?, notify: Boolean) {
        watchers?.cancel()
        releaseWakeLock()
        if (notify) notifySummary(summary, error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15+: hết thời gian cho phép của loại FGS. Phải dừng trong vài giây, không thì app bị coi là ANR. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout("loại $fgsType")
    }

    @Deprecated("Android 14 gọi bản này cho dataSync")
    override fun onTimeout(startId: Int) {
        handleTimeout("dataSync")
    }

    private fun handleTimeout(type: String) {
        val t0 = SystemClock.elapsedRealtime()
        GuzzLog.warning("onTimeout: hệ thống báo hết thời gian chạy foreground service ($type). Dừng, mở app thì làm tiếp.")
        controller.stopForSystem()
        watchers?.cancel()
        releaseWakeLock()
        notifyText(getString(R.string.queue_timeout_title), getString(R.string.queue_timeout_text))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        GuzzLog.info("onTimeout: đã dừng service sau ${SystemClock.elapsedRealtime() - t0} ms.")
    }

    override fun onDestroy() {
        if (controller.active.value) {
            GuzzLog.warning("Service hàng đợi bị huỷ khi đang chạy: dừng, giữ cờ để làm tiếp.")
            controller.stopForSystem()
        }
        watchers?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ wake lock, nhịp log, thông báo

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        // Hạn 12 giờ chỉ để không giữ mãi nếu có lỗi; bài dài nhất cũng xong sớm hơn nhiều.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guzz:queue")
            .apply { acquire(12 * 3600_000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun startWatchers() {
        watchers?.cancel()
        watchers = scope.launch {
            launch { heartbeat() }
            var lastUpdate = 0L
            controller.state.collectLatest { s ->
                // Thông báo cập nhật tối đa mỗi giây (hệ thống bỏ bớt nếu dày hơn).
                val wait = 1000 - (SystemClock.elapsedRealtime() - lastUpdate)
                if (wait > 0) delay(wait)
                lastUpdate = SystemClock.elapsedRealtime()
                val item = s.items.firstOrNull { it.status == ItemStatus.RUNNING }
                notificationManager().notify(NOTIFICATION_ID, buildNotification(item))
            }
        }
    }

    /** Nhịp log mỗi 60 s; theo dõi bước âm thanh đứng yên. */
    private suspend fun heartbeat() {
        var lastKey = ""
        var lastChange = SystemClock.elapsedRealtime()
        while (scope.isActive) {
            delay(HEARTBEAT_MS)
            val item = controller.state.value.items.firstOrNull { it.status == ItemStatus.RUNNING }
            val key = item?.let { "${it.id}|${it.stage}|${it.chunk}|${(it.progress * 1000).roundToInt()}" } ?: ""
            val now = SystemClock.elapsedRealtime()
            if (key != lastKey) {
                lastKey = key
                lastChange = now
                stalled = false
            }
            val power = getSystemService(PowerManager::class.java)
            val battery = getSystemService(BatteryManager::class.java)
            val describe = item?.let { "${it.displayName} | ${it.stage ?: "-"}" +
                (it.chunk?.let { c -> " đoạn $c/${it.chunkTotal}" } ?: "") + (it.step?.let { s -> " ($s)" } ?: "") +
                " ${"%.0f".format(it.progress * 100)}%" } ?: "không có tệp đang chạy"
            GuzzLog.info(
                "Nhịp: $describe | màn hình ${if (power.isInteractive) "bật" else "tắt"} | " +
                    "Doze ${if (power.isDeviceIdleMode) "có" else "không"} | mạng ${networkState()} | " +
                    "pin ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%" +
                    (if (battery.isCharging) " (sạc)" else "") + " | PID ${Process.myPid()}",
            )
            if (item != null && item.stage == Stage.CLEAN.code && now - lastChange > STALL_MS && !stalled) {
                stalled = true
                GuzzLog.warning(
                    "Bước xử lý âm thanh không có tiến độ hơn ${(now - lastChange) / 1000} giây. Kiểm cài đặt pin của " +
                        "Guzz (Không hạn chế): HyperOS tắt wake lock của app bị hạn chế pin khi màn hình tắt.",
                )
                notificationManager().notify(NOTIFICATION_ID, buildNotification(item))
            }
        }
    }

    private fun networkState(): String = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities)
        when {
            caps == null -> "không"
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "có"
            else -> "chưa xác thực"
        }
    } catch (e: SecurityException) {
        "?"
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    private fun ensureChannels() {
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, getString(R.string.queue_channel_progress), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, getString(R.string.queue_channel_done), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openAppIntent(): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(item: QueueItem?): Notification {
        ensureChannels()
        val stop = PendingIntent.getService(
            this, 1, Intent(this, QueueService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            item == null -> getString(R.string.queue_notification_starting)
            stalled -> getString(R.string.queue_notification_stalled)
            else -> describe(this, item)
        }
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(item?.displayName ?: getString(R.string.queue_notification_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setProgress(1000, ((item?.progress ?: 0.0) * 1000).roundToInt(), item == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, getString(R.string.queue_stop), stop).build())
            .build()
    }

    private fun notifySummary(summary: QueueSummary?, error: Throwable?) {
        val text = when {
            error != null -> getString(R.string.queue_done_error, error.message ?: error.javaClass.simpleName)
            summary != null -> getString(R.string.queue_done_text, summary.done, summary.failed, summary.skipped)
            else -> return
        }
        notifyText(getString(R.string.queue_done_title), text)
    }

    private fun notifyText(title: String, text: String) {
        ensureChannels()
        notificationManager().notify(
            NOTIFICATION_DONE_ID,
            Notification.Builder(this, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build(),
        )
    }

    private fun startInForeground(notification: Notification): Boolean = try {
        when {
            Build.VERSION.SDK_INT >= 35 ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            // Android 8–9: chưa có loại FGS, loại khai trong manifest.
            else -> startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        // Android 12+: không được lên FGS từ nền (khi app chưa được miễn tối ưu pin), hoặc đã hết hạn mức thời gian.
        GuzzLog.error("Không lên được foreground service: ${e.javaClass.simpleName}: ${e.message}", e)
        false
    }

    companion object {
        const val ACTION_START = "io.github.cozycode890.guzz.action.QUEUE_START"
        const val ACTION_STOP = "io.github.cozycode890.guzz.action.QUEUE_STOP"
        private const val CHANNEL_PROGRESS = "queue"
        private const val CHANNEL_DONE = "queue-done"
        private const val NOTIFICATION_ID = 3001
        private const val NOTIFICATION_DONE_ID = 3002
        private const val HEARTBEAT_MS = 60_000L
        private const val STALL_MS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, QueueService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, QueueService::class.java).setAction(ACTION_STOP))
        }

        /** Chi tiết tiến độ một tệp để hiện ở thông báo và màn hình Hàng đợi. */
        fun describe(context: Context, item: QueueItem): String = when (item.stage) {
            Stage.CLEAN.code -> context.getString(
                R.string.queue_detail_audio, item.step ?: "", (item.progress / 0.15 * 100).coerceAtMost(100.0).roundToInt(),
            )
            Stage.TRANSCRIBE.code -> if (item.chunk != null && item.chunkTotal != null) {
                context.getString(R.string.queue_detail_chunk, item.chunk, item.chunkTotal, item.model ?: "")
            } else {
                context.getString(R.string.queue_detail_transcribe)
            }
            Stage.MERGE.code -> context.getString(R.string.queue_detail_merge)
            else -> context.getString(R.string.queue_notification_starting)
        }
    }
}

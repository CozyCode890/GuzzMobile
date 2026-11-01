package io.github.cozycode890.guzz.spike.automation

import android.content.Context
import android.os.SystemClock
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.cozycode890.guzz.spike.SpikeApp
import org.json.JSONObject

/**
 * Cách c của G1: WorkManager expedited, rồi `setForeground` lên FGS `mediaProcessing`. Lên FGS lỗi thì ghi lại, rồi vẫn
 * chạy tiếp 5 phút không có FGS để xem job sống được bao lâu.
 */
class ExpeditedWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val method = inputData.getString("method")
        val requestId = inputData.getString("request_id")
        val minutes = inputData.getInt("minutes", 3)
        AutomationLog.write(applicationContext, "worker_bat_dau", method, requestId)
        val fgs = try {
            setForegroundAsync(getForegroundInfo()).get()
            AutomationLog.write(applicationContext, "fgs_ok", method, requestId, JSONObject().put("minutes", minutes))
            true
        } catch (e: Exception) {
            AutomationLog.write(applicationContext, "fgs_loi", method, requestId,
                JSONObject().put("loi", (e.cause ?: e).let { it.javaClass.name + ": " + it.message }))
            false
        }
        val t0 = SystemClock.elapsedRealtime()
        val ok = HeartbeatService.beat(applicationContext, method, requestId, if (fgs) minutes else 5) {
            if (isStopped) SystemClock.elapsedRealtime() else 0L
        }
        AutomationLog.write(applicationContext, "worker_ket_thuc", method, requestId, JSONObject()
            .put("co_fgs", fgs).put("het_gio", ok).put("is_stopped", isStopped)
            .put("stop_reason", if (android.os.Build.VERSION.SDK_INT >= 31) stopReason else -1)
            .put("giay", (SystemClock.elapsedRealtime() - t0) / 1000))
        if (fgs) HeartbeatService.sendDone(applicationContext, requestId, method, ok)
        return Result.success()
    }

    override fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(HeartbeatService.NOTIFICATION_ID + 1,
            SpikeApp.notification(applicationContext, "Guzz spike", "ExpeditedWorker"), SpikeApp.fgsType)
}

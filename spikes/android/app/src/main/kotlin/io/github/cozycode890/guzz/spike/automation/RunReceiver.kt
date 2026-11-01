package io.github.cozycode890.guzz.spike.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import io.github.cozycode890.guzz.spike.bench.BenchService
import org.json.JSONObject

/**
 * Nhận lệnh [AutomationLog.ACTION_RUN] từ MacroDroid (G1). `method=broadcast`: gọi `startForegroundService` ngay trong
 * receiver (cách a, chỉ được khi app thuộc ngoại lệ, vd đã miễn tối ưu pin). `method=work`: xếp [ExpeditedWorker]
 * (cách c).
 */
class RunReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val method = intent.getStringExtra("method") ?: "broadcast"
        val requestId = intent.getStringExtra("request_id")
        val minutes = intent.extras?.get("minutes")?.toString()?.toIntOrNull() ?: 3
        AutomationLog.write(ctx, "nhan", method, requestId)
        when (method) {
            "work" -> {
                val req = OneTimeWorkRequestBuilder<ExpeditedWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(Data.Builder()
                        .putString("method", method)
                        .putString("request_id", requestId)
                        .putInt("minutes", minutes)
                        .build())
                    .build()
                WorkManager.getInstance(ctx).enqueue(req)
                AutomationLog.write(ctx, "work_xep_hang", method, requestId)
            }
            else -> startHeartbeat(ctx, method, requestId, minutes, intent.extras)
        }
    }

    companion object {
        /**
         * Khởi động FGS: có extra `bench` thì chạy phép đo đó bằng BenchService (D6 / G4: bench 2 giờ do MacroDroid
         * khởi động), không thì HeartbeatService [minutes] phút.
         */
        fun startHeartbeat(ctx: Context, method: String?, requestId: String?, minutes: Int,
                           extras: android.os.Bundle? = null) {
            try {
                val bench = extras?.getString("bench")
                val intent = if (bench != null) {
                    Intent(ctx, BenchService::class.java).putExtras(extras)
                } else {
                    Intent(ctx, HeartbeatService::class.java)
                }
                ctx.startForegroundService(intent
                    .putExtra("method", method)
                    .putExtra("request_id", requestId)
                    .putExtra("minutes", minutes))
                AutomationLog.write(ctx, "start_fgs_ok", method, requestId, JSONObject().put("bench", bench))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (API 31+) khi app ở nền và không thuộc ngoại lệ nào.
                AutomationLog.write(ctx, "start_fgs_loi", method, requestId,
                    JSONObject().put("loi", e.javaClass.name + ": " + e.message))
            }
        }
    }
}

package io.github.cozycode890.guzz.spike.automation

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import io.github.cozycode890.guzz.spike.bench.BenchLog
import org.json.JSONObject

/**
 * `results/automation.jsonl` (G1): mỗi lần nhận lệnh / mỗi nhịp ghi trạng thái máy lúc đó, để đếm được FGS lên bao nhiêu
 * lần trong điều kiện màn hình tắt + Doze.
 */
object AutomationLog {
    const val ACTION_RUN = "io.github.cozycode890.guzz.spike.action.RUN"
    const val EVENT_DONE = "io.github.cozycode890.guzz.spike.event.DONE"

    fun write(ctx: Context, event: String, method: String?, requestId: String?, extra: JSONObject = JSONObject()) {
        val fields = state(ctx).put("su_kien", event).put("method", method).put("request_id", requestId)
        extra.keys().forEach { fields.put(it, extra.get(it)) }
        BenchLog.of(ctx, "automation").write(fields)
    }

    fun state(ctx: Context): JSONObject {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val o = JSONObject()
            .put("interactive", pm.isInteractive)
            .put("device_idle", pm.isDeviceIdleMode)
            .put("ignoring_battery_opt", pm.isIgnoringBatteryOptimizations(ctx.packageName))
        if (Build.VERSION.SDK_INT >= 28) {
            o.put("standby_bucket", ctx.getSystemService(UsageStatsManager::class.java).appStandbyBucket)
        }
        if (Build.VERSION.SDK_INT >= 35) {
            runCatching {
                val info = ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessStartReasons(1)
                    .firstOrNull()
                if (info != null) {
                    o.put("process_start_reason", info.reason)
                    o.put("process_start_type", info.startType)
                    o.put("process_was_force_stopped", info.wasForceStopped())
                }
            }
        }
        return o
    }
}

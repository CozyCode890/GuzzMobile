package io.github.cozycode890.guzz.spike.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject

/**
 * Pin và nhiệt trong lúc đo (D30): % pin, `BATTERY_PROPERTY_CHARGE_COUNTER` (µAh), nhiệt độ pin từ
 * `ACTION_BATTERY_CHANGED`, `PowerManager.getCurrentThermalStatus()`. Giữ giá trị đầu, cuối và đỉnh.
 */
class BatteryProbe(private val ctx: Context) {
    private val bm = ctx.getSystemService(BatteryManager::class.java)
    private val pm = ctx.getSystemService(PowerManager::class.java)

    private var startPct = 0
    private var startCharge = 0L
    private var startTemp = 0f
    private var startPlugged = 0
    @Volatile private var peakTemp = Float.NEGATIVE_INFINITY
    @Volatile private var peakThermal = 0
    @Volatile private var everPlugged = false

    fun start(): BatteryProbe {
        startPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        startCharge = chargeCounter()
        val s = sticky()
        startTemp = temperature(s)
        startPlugged = s?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        sample()
        return this
    }

    /** Gọi định kỳ (MemSampler gọi mỗi giây). */
    fun sample() {
        val s = sticky()
        peakTemp = maxOf(peakTemp, temperature(s))
        if ((s?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) everPlugged = true
        if (Build.VERSION.SDK_INT >= 29) peakThermal = maxOf(peakThermal, pm.currentThermalStatus)
    }

    fun stop(): JSONObject {
        sample()
        val endPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val endCharge = chargeCounter()
        return JSONObject()
            .put("pin_dau_pct", startPct)
            .put("pin_cuoi_pct", endPct)
            .put("pin_ton_pct", startPct - endPct)
            .put("sac_dau_uah", startCharge)
            .put("sac_cuoi_uah", endCharge)
            .put("sac_ton_uah", startCharge - endCharge)
            .put("nhiet_dau_c", startTemp.toDouble())
            .put("nhiet_dinh_c", peakTemp.toDouble())
            .put("thermal_dinh", peakThermal)
            .put("cam_sac_luc_dau", startPlugged != 0)
            .put("co_luc_cam_sac", everPlugged)
    }

    private fun chargeCounter(): Long = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

    private fun sticky(): Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun temperature(i: Intent?): Float = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
}

package io.github.cozycode890.guzz.spike.diarize

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import io.github.cozycode890.guzz.spike.bench.Bench
import io.github.cozycode890.guzz.spike.bench.BenchContext
import io.github.cozycode890.guzz.spike.bench.IsolatedBenchService
import org.json.JSONObject

/**
 * D7 cách 3: chạy bench `diarize` trong process `:diarize` (IsolatedBenchService), chờ `kill_after_sec` giây rồi
 * `Process.killProcess`. Ghi: process chính còn sống không (bench này chạy trong process chính), process `:diarize` mất
 * bao lâu thì biến mất, RAM trống của máy trước / lúc giết / sau khi giết.
 */
object IsolatedKillBench : Bench {
    override fun run(b: BenchContext): JSONObject {
        val ctx = b.ctx
        val am = ctx.getSystemService(ActivityManager::class.java)
        val killAfter = b.int("kill_after_sec", 30)
        val before = availMb(am)
        val args = Bundle(b.args).apply {
            putString("bench", "diarize")
            putString("log", "isolated_diarize")
        }
        ctx.startService(Intent(ctx, IsolatedBenchService::class.java).putExtras(args))
        val processName = ctx.packageName + ":diarize"
        var pid = 0
        val t0 = SystemClock.elapsedRealtime()
        while (pid == 0 && SystemClock.elapsedRealtime() - t0 < 10_000) {
            pid = findPid(am, processName)
            if (pid == 0) Thread.sleep(200)
        }
        if (pid == 0) return JSONObject().put("loi", "không thấy process $processName")
        b.progress("đợi $killAfter s rồi giết pid $pid")
        Thread.sleep(killAfter * 1000L)
        val pss = runCatching { am.getProcessMemoryInfo(intArrayOf(pid))[0].totalPss / 1024 }.getOrDefault(-1)
        val atKill = availMb(am)
        val tk = SystemClock.elapsedRealtime()
        Process.killProcess(pid)
        while (findPid(am, processName) == pid && SystemClock.elapsedRealtime() - tk < 10_000) Thread.sleep(50)
        val goneMs = SystemClock.elapsedRealtime() - tk
        Thread.sleep(2000)
        return JSONObject()
            .put("pid_chinh", Process.myPid())
            .put("pid_diarize", pid)
            .put("pss_diarize_luc_giet_mb", pss)
            .put("ram_trong_truoc_mb", before)
            .put("ram_trong_luc_giet_mb", atKill)
            .put("ram_trong_sau_2s_mb", availMb(am))
            .put("ms_tu_luc_giet_toi_khi_mat", goneMs)
            .put("con_process_diarize", findPid(am, processName) != 0)
            .put("process_chinh_con_song", true)
    }

    private fun findPid(am: ActivityManager, name: String): Int =
        am.runningAppProcesses?.firstOrNull { it.processName == name }?.pid ?: 0

    private fun availMb(am: ActivityManager): Long =
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / (1024 * 1024)
}

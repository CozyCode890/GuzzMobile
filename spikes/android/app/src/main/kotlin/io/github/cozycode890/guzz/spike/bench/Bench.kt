package io.github.cozycode890.guzz.spike.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import io.github.cozycode890.guzz.spike.diarize.DiarizeBench
import io.github.cozycode890.guzz.spike.diarize.IsolatedKillBench
import io.github.cozycode890.guzz.spike.media.CodecsBench
import io.github.cozycode890.guzz.spike.media.DecodeBench
import io.github.cozycode890.guzz.spike.media.EncodeBench
import org.json.JSONObject

/** Một phép đo. Trả về kết quả; dòng bắt đầu / kết thúc, RAM và pin do [BenchRunner] ghi. */
fun interface Bench {
    fun run(b: BenchContext): JSONObject
}

class BenchContext(
    val ctx: Context,
    val args: Bundle,
    val log: BenchLog,
    val progress: (String) -> Unit,
    /** Đã có lệnh Dừng chưa, và lúc nào (elapsedRealtime, ms); 0 = chưa. */
    val stopRequestedAt: () -> Long,
    val mem: MemSampler,
) {
    /** Giá trị dạng chuỗi của extra, dù adb gửi bằng --es, --ei hay --ef. */
    @Suppress("DEPRECATION")
    fun string(key: String, default: String? = null): String? = args.get(key)?.toString() ?: default
    fun int(key: String, default: Int): Int = if (args.containsKey(key)) args.get(key).toString().toInt() else default
    fun float(key: String, default: Float): Float =
        if (args.containsKey(key)) args.get(key).toString().toFloat() else default
}

object Benches {
    val all: Map<String, Bench> = mapOf(
        "noop" to NoopBench,
        "diarize" to DiarizeBench,
        "isolated" to IsolatedKillBench,
        "decode" to DecodeBench,
        "codecs" to CodecsBench,
        "encode" to EncodeBench,
    )
}

/** C2: chỉ ngủ, để kiểm hạ tầng đo (dòng bắt đầu / kết thúc, PSS, thông báo, largeMemoryClass). */
object NoopBench : Bench {
    override fun run(b: BenchContext): JSONObject {
        val seconds = b.int("seconds", 60)
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < seconds * 1000L && b.stopRequestedAt() == 0L) {
            b.progress("noop ${(SystemClock.elapsedRealtime() - start) / 1000}/$seconds s")
            Thread.sleep(1000)
        }
        val am = b.ctx.getSystemService(ActivityManager::class.java)
        return JSONObject()
            .put("large_memory_class_mb", am.largeMemoryClass)
            .put("memory_class_mb", am.memoryClass)
            .put("max_memory_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024))
    }
}

/** Chạy một phép đo: ghi dòng bắt đầu, lấy mẫu RAM và pin, ghi dòng kết thúc kèm kết quả hoặc lỗi. */
object BenchRunner {
    fun run(ctx: Context, args: Bundle, progress: (String) -> Unit, stopRequestedAt: () -> Long): JSONObject? {
        val name = args.getString("bench") ?: "noop"
        val log = BenchLog.of(ctx, args.getString("log") ?: name)
        val bench = Benches.all[name]
        val argJson = JSONObject().apply { args.keySet().forEach { put(it, args.get(it)?.toString()) } }
        if (bench == null) {
            log.write("su_kien" to "loi", "loi" to "Không có phép đo '$name'", "tham_so" to argJson)
            return null
        }
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        // Màn hình tắt thì HyperOS hạ xung CPU: giải mã chậm đi khoảng 2 lần, nên mỗi dòng ghi trạng thái màn hình.
        log.write("su_kien" to "bat_dau", "tham_so" to argJson, "man_hinh_bat" to pm.isInteractive)
        val battery = BatteryProbe(ctx).start()
        val mem = MemSampler { battery.sample() }.start()
        val t0 = SystemClock.elapsedRealtime()
        var result: JSONObject? = null
        var error: Throwable? = null
        try {
            result = bench.run(BenchContext(ctx, args, log, progress, stopRequestedAt, mem))
        } catch (e: Throwable) {
            // Bắt cả OutOfMemoryError để ghi lại thay vì để app chết (D4).
            error = e
        }
        val seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val end = json(
            "su_kien" to "ket_thuc",
            "tham_so" to argJson,
            "giay" to seconds,
            "man_hinh_bat" to pm.isInteractive,
            "ram" to mem.stop(),
            "pin" to battery.stop(),
            "ket_qua" to result,
            "loi" to error?.toString(),
            "stack" to error?.stackTraceToString()?.take(4000),
        )
        log.write(end)
        return end
    }
}

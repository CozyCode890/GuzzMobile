package io.github.cozycode890.guzz.spike.bench

import android.os.Debug
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Mỗi giây đọc PSS (`Debug.getPss`, đọc thẳng smaps_rollup nên không bị giới hạn tần suất như
 * `ActivityManager.getProcessMemoryInfo`), VmRSS / VmHWM trong `/proc/self/status`, heap Java đang dùng và native heap đã
 * cấp; giữ giá trị đỉnh. Cùng nhịp đó gọi [onTick] (vd đọc nhiệt độ pin).
 */
class MemSampler(private val periodMs: Long = 1000, private val onTick: () -> Unit = {}) {
    @Volatile private var running = false
    private var worker: Thread? = null

    @Volatile var peakPssKb = 0L
        private set
    @Volatile var peakRssKb = 0L
        private set
    @Volatile var peakHeapBytes = 0L
        private set
    @Volatile var peakNativeBytes = 0L
        private set
    @Volatile var samples = 0
        private set

    fun start(): MemSampler {
        running = true
        worker = thread(name = "MemSampler", isDaemon = true) {
            while (running) {
                sample()
                try {
                    Thread.sleep(periodMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return this
    }

    fun sample() {
        val rt = Runtime.getRuntime()
        peakPssKb = maxOf(peakPssKb, Debug.getPss())
        peakRssKb = maxOf(peakRssKb, procStatusKb("VmRSS"))
        peakHeapBytes = maxOf(peakHeapBytes, rt.totalMemory() - rt.freeMemory())
        peakNativeBytes = maxOf(peakNativeBytes, Debug.getNativeHeapAllocatedSize())
        samples++
        onTick()
    }

    fun stop(): JSONObject {
        running = false
        worker?.interrupt()
        worker?.join(2000)
        sample()
        return toJson()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("pss_dinh_mb", peakPssKb / 1024)
        .put("vmrss_dinh_mb", peakRssKb / 1024)
        // VmHWM là đỉnh RSS do kernel giữ suốt đời process, kể cả lúc giữa hai lần lấy mẫu.
        .put("vmhwm_mb", procStatusKb("VmHWM") / 1024)
        .put("heap_java_dinh_mb", peakHeapBytes / MB)
        .put("native_heap_dinh_mb", peakNativeBytes / MB)
        .put("so_mau", samples)

    companion object {
        private const val MB = 1024L * 1024L

        /** Một dòng của `/proc/self/status`, đơn vị kB; không có thì 0. */
        fun procStatusKb(key: String): Long =
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("$key:") }
                    ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: 0L
            }
    }
}

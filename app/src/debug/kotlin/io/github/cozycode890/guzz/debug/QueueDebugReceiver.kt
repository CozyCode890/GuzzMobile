package io.github.cozycode890.guzz.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import io.github.cozycode890.guzz.GuzzApp
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.pipeline.QueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Lệnh adb cho hàng đợi thật, chỉ bản debug (Stage 4, D2; intent thật cho MacroDroid là Stage 6). Dùng qua
 * `tools/device/queue_debug.ps1`, hoặc tay:
 *
 *     adb shell am broadcast -n io.github.cozycode890.guzz.debug/io.github.cozycode890.guzz.debug.QueueDebugReceiver \
 *         --es cmd add --es path B5.m4a --ef delay_min 10
 *
 * - `add`: `path` là tên tệp trong `files/input/` (adb push vào đó) hoặc đường dẫn tuyệt đối; `delay_min`: chờ giữa
 *   các đoạn (D67).
 * - `start`, `stop`, `clear` (bỏ mọi tệp không chạy), `status` (ghi `files/debug-queue/trang_thai.json`).
 * - `fake` với `--es value on|off`: Gemini giả (không gọi mạng).
 *
 * Lệnh nào cũng ghi lại `trang_thai.json` khi xong.
 */
class QueueDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as GuzzApp
        val container = app.container
        val queue = container.queueController
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (val cmd = intent.getStringExtra("cmd")) {
                    "add" -> {
                        val path = intent.getStringExtra("path").orEmpty()
                        val file = if (path.startsWith("/")) File(path) else File(context.getExternalFilesDir("input"), path)
                        val delay = intent.getFloatExtra("delay_min", 0f).toDouble() * 60
                        val added = queue.addPath(file, delay)
                        GuzzLog.info("Lệnh adb: thêm ${file.path} (chờ giữa đoạn ${delay.toInt()} s): ${added.size} tệp.")
                    }
                    "start" -> {
                        val problem = queue.checkStart()
                        if (problem != null) {
                            GuzzLog.warning("Lệnh adb: không bắt đầu được: ${problem.message}")
                        } else {
                            queue.start()
                        }
                    }
                    "stop" -> queue.stop()
                    "clear" -> queue.state.value.items.forEach { queue.remove(it.id) }
                    "fake" -> {
                        val on = intent.getStringExtra("value") != "off"
                        container.fakeGeminiFlag.parentFile?.mkdirs()
                        if (on) container.fakeGeminiFlag.writeText("1") else container.fakeGeminiFlag.delete()
                        GuzzLog.info("Lệnh adb: Gemini giả ${if (on) "BẬT" else "tắt"}.")
                    }
                    "status", null -> Unit
                    else -> GuzzLog.warning("Lệnh adb lạ: $cmd")
                }
                writeStatus(context, queue.state.value, queue.active.value, container.fakeGeminiEnabled())
            } catch (e: Exception) {
                GuzzLog.error("Lệnh adb lỗi: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun writeStatus(context: Context, state: QueueState, active: Boolean, fake: Boolean) {
        val dir = File(context.getExternalFilesDir(null), "debug-queue").apply { mkdirs() }
        val json = buildJsonObject {
            put("dang_chay_vong", active)
            put("gemini_gia", fake)
            put("pid", Process.myPid())
            put("hang_doi", Json.encodeToJsonElement(QueueState.serializer(), state))
        }
        val tmp = File(dir, "trang_thai.json.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        tmp.renameTo(File(dir, "trang_thai.json"))
    }
}

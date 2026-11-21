package io.github.cozycode890.guzz.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import io.github.cozycode890.guzz.GuzzApp
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.settings.AudioPresetValues
import io.github.cozycode890.guzz.core.common.settings.AudioPresets
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
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
 * - `start` (chỉ được khi app đã được miễn tối ưu pin, D69: Android 12+ không cho app ở nền khởi động foreground
 *   service), `stop`, `clear` (bỏ mọi tệp không chạy), `status` (ghi `files/debug-queue/trang_thai.json`).
 * - `fake` với `--es value on|off`: Gemini giả (không gọi mạng).
 * - `automation` (Stage 6): `--es enabled on|off`, `--es secret <mã>` (rỗng = bỏ), `--es include_paths on|off`,
 *   `--es beside_source on|off`, `--es clear_events 1` (xoá `files/debug-events/events.jsonl`).
 * - `server` (Stage 11): `--es enabled on|off`, `--es url <URL>`, `--es token_file <tên tệp trong files/>`: đọc token
 *   từ tệp đã adb push (không đưa token vào dòng lệnh), lưu Keystore rồi xoá tệp; `--es token_file -` = xoá token.
 *
 * Lệnh nào cũng ghi lại `trang_thai.json` khi xong.
 */
class QueueDebugReceiver : BroadcastReceiver() {
    // encodeDefaults: ghi cả trạng thái "cho", running = false… để script đọc được.
    private val statusJson = Json { encodeDefaults = true }

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
                            try {
                                queue.start()
                            } catch (e: IllegalStateException) {
                                // ForegroundServiceStartNotAllowedException (Android 12+) là IllegalStateException.
                                GuzzLog.warning(
                                    "Lệnh adb: Android không cho khởi động service từ nền (${e.javaClass.simpleName}). " +
                                        "Miễn tối ưu pin cho Guzz (D69) rồi chạy lại.",
                                )
                            }
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
                    "speakers" -> {
                        // Stage 5: đặt cài đặt người nói để thử 3 cách bằng adb (method: pyannote|gemini|hybrid|off).
                        val s = container.settings
                        intent.getStringExtra("model")?.let { s.set(SettingKeys.MODEL, it) }
                        intent.getStringExtra("method")?.let { m ->
                            if (m == "off") {
                                s.set(SettingKeys.SPEAKERS_ENABLED, false)
                            } else {
                                s.set(SettingKeys.SPEAKERS_ENABLED, true)
                                s.set(SettingKeys.SPEAKERS_METHOD, m)
                            }
                        }
                        if (intent.hasExtra("num")) s.set(SettingKeys.NUM_SPEAKERS, intent.getIntExtra("num", 0))
                        if (intent.hasExtra("save_map")) s.set(SettingKeys.SAVE_SPEAKER_MAP, intent.getBooleanExtra("save_map", false))
                        GuzzLog.info(
                            "Lệnh adb: người nói ${if (s.get(SettingKeys.SPEAKERS_ENABLED)) s.get(SettingKeys.SPEAKERS_METHOD) else "tắt"}, " +
                                "model ${s.get(SettingKeys.MODEL)}, số người ${s.get(SettingKeys.NUM_SPEAKERS)}, " +
                                "lưu bản đồ ${s.get(SettingKeys.SAVE_SPEAKER_MAP)}.",
                        )
                    }
                    "audio" -> {
                        // Stage 7: chọn preset âm thanh / bật tắt từng bước bằng adb (preset: near_lecturer|near_speaker|
                        // moving_lecturer|speakers_around).
                        val s = container.settings
                        intent.getStringExtra("preset")?.let { name ->
                            val values = AudioPresets.all[name] ?: error("preset lạ: $name")
                            values.applyTo(s)
                            s.set(SettingKeys.DENOISE, true)
                            s.set(SettingKeys.TRIM_SILENCE, true)
                        }
                        if (intent.hasExtra("denoise")) s.set(SettingKeys.DENOISE, intent.getBooleanExtra("denoise", true))
                        if (intent.hasExtra("save_clean")) s.set(SettingKeys.SAVE_CLEAN_AUDIO, intent.getBooleanExtra("save_clean", false))
                        // Stage 8 G2: lưu âm thanh tách từ video, chọn luồng âm thanh (auto | 0..3).
                        if (intent.hasExtra("save_extracted")) {
                            s.set(SettingKeys.SAVE_EXTRACTED, intent.getBooleanExtra("save_extracted", false))
                        }
                        intent.getStringExtra("track")?.let { s.set(SettingKeys.AUDIO_TRACK, it) }
                        GuzzLog.info(
                            "Lệnh adb: âm thanh ${AudioPresets.detect(AudioPresetValues.read(s))}, khử ồn ${s.get(SettingKeys.DENOISE)}, " +
                                "lưu âm thanh sạch ${s.get(SettingKeys.SAVE_CLEAN_AUDIO)}, " +
                                "lưu âm thanh tách ${s.get(SettingKeys.SAVE_EXTRACTED)}, luồng ${s.get(SettingKeys.AUDIO_TRACK)}.",
                        )
                    }
                    "automation" -> {
                        // Stage 6: bật / tắt điều khiển từ app khác và các mục [AUTOMATION] bằng adb (intent_debug.ps1).
                        val s = container.settings
                        fun flag(name: String) = intent.getStringExtra(name)?.let { it == "on" || it == "1" || it == "true" }
                        flag("enabled")?.let { s.set(SettingKeys.AUTOMATION_ENABLED, it) }
                        flag("include_paths")?.let { s.set(SettingKeys.AUTOMATION_INCLUDE_PATHS, it) }
                        flag("beside_source")?.let { s.set(SettingKeys.SAVE_BESIDE_SOURCE, it) }
                        if (intent.hasExtra("secret")) s.set(SettingKeys.AUTOMATION_SECRET, intent.getStringExtra("secret").orEmpty())
                        if (intent.hasExtra("clear_events")) {
                            File(context.getExternalFilesDir(null), "debug-events/events.jsonl").delete()
                        }
                        GuzzLog.info(
                            "Lệnh adb: tự động hoá ${if (s.get(SettingKeys.AUTOMATION_ENABLED)) "BẬT" else "tắt"}, " +
                                "mã bí mật ${if (s.get(SettingKeys.AUTOMATION_SECRET).isEmpty()) "không" else "có"}, " +
                                "kèm đường dẫn ${s.get(SettingKeys.AUTOMATION_INCLUDE_PATHS)}, " +
                                "cạnh tệp nguồn ${s.get(SettingKeys.SAVE_BESIDE_SOURCE)}.",
                        )
                    }
                    "server" -> {
                        // Stage 11 G1: bật server, đặt URL, nạp token từ tệp (xoá tệp ngay sau khi đọc).
                        val s = container.settings
                        intent.getStringExtra("enabled")?.let { s.set(SettingKeys.SERVER_ENABLED, it == "on" || it == "1" || it == "true") }
                        intent.getStringExtra("url")?.let { s.set(SettingKeys.SERVER_URL, it) }
                        intent.getStringExtra("token_file")?.let { name ->
                            if (name == "-") {
                                container.serverTokenStore.clear()
                            } else {
                                val f = File(context.getExternalFilesDir(null), name)
                                try {
                                    container.serverTokenStore.save(f.readText().trim())
                                } finally {
                                    f.delete()
                                }
                            }
                        }
                        GuzzLog.info(
                            "Lệnh adb: server ${if (s.get(SettingKeys.SERVER_ENABLED)) "BẬT" else "tắt"}, " +
                                "URL ${s.get(SettingKeys.SERVER_URL).ifEmpty { "(trống)" }}, " +
                                "token ${if (container.serverTokenStore.read() != null) "có" else "không"}.",
                        )
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
            put("hang_doi", statusJson.encodeToJsonElement(QueueState.serializer(), state))
        }
        val tmp = File(dir, "trang_thai.json.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        tmp.renameTo(File(dir, "trang_thai.json"))
    }
}

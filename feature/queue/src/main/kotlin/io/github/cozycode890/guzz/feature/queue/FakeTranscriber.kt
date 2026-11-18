package io.github.cozycode890.guzz.feature.queue

import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.gemini.ChunkTranscriber
import io.github.cozycode890.guzz.core.gemini.GeminiConfig
import io.github.cozycode890.guzz.core.gemini.QuotaTracker
import io.github.cozycode890.guzz.core.gemini.TranscribeOptions
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale

/**
 * Gemini giả, chỉ dùng ở bản debug (app chỉ dựng khi `BuildConfig.DEBUG`): chờ vài giây rồi trả "Đoạn i." với i lấy từ
 * tên tệp `doan_NNN`, không gọi mạng. Để thử service, Dừng, giết app, giao diện mà không gửi gì lên Google (Stage 4,
 * bước D–E). Sổ hạn mức riêng trong bộ nhớ: không làm bẩn sổ thật.
 *
 * Stage 5: model đa năng có bản đồ người nói thì trả hai đoạn văn có tên lấy từ bản đồ; `diarize` / `word_timestamps`
 * (model `*-transcribe`) thì trả từ có mốc (một từ mỗi 2 giây), nhãn `spk_1` cho 10% đầu đoạn và `spk_2` cho phần còn
 * lại, để thử cách "Gemini tự nhận diện" và "Kết hợp" không cần mạng.
 */
class FakeTranscriber(private val delayMs: Long = 3_000) : ChunkTranscriber {
    override val tracker = QuotaTracker()

    override suspend fun transcribeRaw(
        audio: File,
        cfg: GeminiConfig,
        prompt: String?,
        audioSeconds: Double?,
        options: TranscribeOptions,
    ): JsonObject {
        val index = Regex("doan_(\\d+)").find(audio.name)?.groupValues?.get(1)?.toInt() ?: 0
        GuzzLog.info("Gemini GIẢ: ${audio.name} (${audio.length() / 1024} KB, ${options.model}), không gọi mạng.")
        // Stage 8 G3: thấy được prompt đã sửa có tới pipeline không.
        GuzzLog.debug("Gemini GIẢ: prompt = ${prompt?.replace('\n', '⏎')?.take(300) ?: "(không có)"}")
        delay(delayMs)
        val seconds = audioSeconds ?: 60.0
        val map = options.attachedText ?: options.attachment?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
        val text: String
        val words = ArrayList<JsonObject>()
        when {
            options.diarize || options.wordTimestamps -> {
                var t = 0.0
                var k = 0
                while (t + 1.5 <= seconds) {
                    val w = "từ${++k}"
                    words += buildJsonObject {
                        put("type", "word_info")
                        put("text", w)
                        if (options.diarize) put("speaker", if (t < seconds * 0.1) "spk_1" else "spk_2")
                        put("start_offset", "%.3fs".format(Locale.ROOT, t))
                        put("end_offset", "%.3fs".format(Locale.ROOT, t + 1.5))
                    }
                    t += 2.0
                }
                text = "Đoạn $index. " + words.joinToString(" ") { (it["text"] as JsonPrimitive).content }
            }
            map != null -> {
                // Tên người nói từ bản đồ: "00:00.0 - 00:05.0  Giảng viên".
                val names = map.lines().filter { " - " in it }.map { it.substringAfter("  ").trim() }.distinct()
                val a = names.getOrElse(0) { "Giảng viên" }
                val b = names.getOrElse(1) { a }
                text = "[00:00] $a: Đoạn $index, phần đầu.\n\n[00:30] $b: Đoạn $index, phần sau."
            }
            else -> text = "Đoạn $index."
        }
        return buildJsonObject {
            put("status", "completed")
            put(
                "steps",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "model_output")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", JsonPrimitive(text))
                                            if (words.isNotEmpty()) put("annotations", buildJsonArray { words.forEach { add(it) } })
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
    }
}

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

/**
 * Gemini giả, chỉ dùng ở bản debug (app chỉ dựng khi `BuildConfig.DEBUG`): chờ vài giây rồi trả "Đoạn i." với i lấy từ
 * tên tệp `doan_NNN`, không gọi mạng. Để thử service, Dừng, giết app, giao diện mà không gửi gì lên Google (Stage 4,
 * bước D–E). Sổ hạn mức riêng trong bộ nhớ: không làm bẩn sổ thật.
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
        delay(delayMs)
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
                                    add(buildJsonObject { put("type", "text"); put("text", JsonPrimitive("Đoạn $index.")) })
                                },
                            )
                        },
                    )
                },
            )
        }
    }
}

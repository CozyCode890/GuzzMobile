package io.github.cozycode890.guzz.core.gemini

import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.log.Logger
import io.github.cozycode890.guzz.core.common.pyRound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal object JsonSupport {
    val json = Json { ignoreUnknownKeys = true }
}

/** Một từ có mốc thời gian của model `*-transcribe`. Mốc tính bằng giây từ đầu đoạn âm thanh gửi lên. */
data class Word(val text: String, val speaker: String?, val start: Double, val end: Double)

/** Tệp đã tải lên Google kèm âm thanh (bản đồ người nói). */
data class Document(val uri: String, val mimeType: String)

private val MIME = mapOf(
    ".flac" to "audio/flac",
    ".wav" to "audio/wav",
    ".mp3" to "audio/mp3",
    ".ogg" to "audio/ogg",
    ".m4a" to "audio/m4a",
    ".aac" to "audio/aac",
    ".txt" to "text/plain",
    ".json" to "application/json",
)

/** Port của google_ai.py: `guess_mime`. Đuôi tệp lấy như `os.path.splitext` (tệp chỉ có dấu chấm đầu thì không có đuôi). */
fun guessMime(path: String): String = MIME[pyExtension(path).lowercase()] ?: "application/octet-stream"

internal fun pyExtension(path: String): String {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || name.substring(0, dot).all { it == '.' }) return ""
    return name.substring(dot)
}

/** Model chuyên chép lời (`gemini-3.5-transcribe`…): chỉ nhận âm thanh, không nhận prompt hay tệp kèm. */
fun isTranscribeModel(model: String?): Boolean = "transcribe" in model.orEmpty().lowercase()

/** Che key khi phải hiện ra (giao diện, log). Port của google_ai.py: `mask_api_key`. */
fun maskApiKey(key: String?): String {
    if (key.isNullOrEmpty()) return ""
    return if (key.length > 10) key.take(4) + "…" + key.takeLast(4) else "…"
}

/**
 * Thân yêu cầu `POST /v1beta/interactions`. Port của google_ai.py: `build_request`.
 *
 * @param model mặc định [GeminiConfig.model].
 * @param document tệp văn bản tải lên kèm (chỉ model đa năng).
 * @param attachedText chèn vào cuối prompt (thay cho [document]).
 * @param diarize, wordTimestamps chỉ có nghĩa với model `*-transcribe`.
 */
fun buildRequest(
    cfg: GeminiConfig,
    fileUri: String,
    mime: String,
    prompt: String? = null,
    model: String? = null,
    diarize: Boolean = false,
    wordTimestamps: Boolean = false,
    document: Document? = null,
    attachedText: String? = null,
): JsonObject {
    val chosen = model?.takeIf { it.isNotEmpty() } ?: cfg.model
    val audioPart = buildJsonObject {
        put("type", "audio")
        put("uri", fileUri)
        put("mime_type", mime)
    }
    return buildJsonObject {
        put("model", chosen)
        if (isTranscribeModel(chosen)) {
            val timestamps = diarize || wordTimestamps
            putJsonArray("input") { add(audioPart) }
            putJsonObject("generation_config") {
                putJsonObject("transcription_config") {
                    putJsonObject("mode") {
                        if (timestamps) {
                            // Google: smart không đi cùng diarization / timestamps.
                            put("type", "verbatim")
                            if (diarize) put("diarization_mode", "speaker")
                            putJsonArray("timestamp_granularities") { add("word") }
                        } else {
                            put("type", cfg.transcribeMode.ifEmpty { "smart" })
                        }
                    }
                    if (cfg.languages.isNotEmpty()) {
                        putJsonArray("language_codes") { cfg.languages.forEach { add(it) } }
                    }
                    // Google: custom_vocabulary không đi cùng diarization / timestamps.
                    if (cfg.vocabulary.isNotEmpty() && !timestamps) {
                        putJsonArray("custom_vocabulary") { cfg.vocabulary.forEach { add(it) } }
                    }
                }
            }
        } else {
            var content = prompt.orEmpty().ifEmpty { "Transcribe this audio." }.pyStrip()
            if (cfg.languages.isNotEmpty()) content += "\nSpoken language(s): ${cfg.languages.joinToString(", ")}."
            if (cfg.vocabulary.isNotEmpty()) content += "\nTerms that may appear: ${cfg.vocabulary.joinToString(", ")}."
            if (!attachedText.isNullOrEmpty()) content += "\n\nSPEAKER MAP:\n" + attachedText.pyStrip()
            put(
                "input",
                buildJsonArray {
                    addJsonObject {
                        put("type", "text")
                        put("text", content)
                    }
                    add(audioPart)
                    if (document != null) {
                        addJsonObject {
                            put("type", "document")
                            put("uri", document.uri)
                            put("mime_type", document.mimeType)
                        }
                    }
                },
            )
        }
        if (!cfg.storeOnServer) put("store", false)
    }
}

/** Các nội dung `text` trong các bước `model_output`. Port của google_ai.py: `_model_outputs`. */
private fun modelOutputs(response: JsonObject): Sequence<JsonObject> = sequence {
    val steps = response["steps"] as? JsonArray ?: return@sequence
    for (step in steps) {
        if (step !is JsonObject || step["type"].stringOrNull() != "model_output") continue
        val content = step["content"] as? JsonArray ?: continue
        for (c in content) {
            if (c is JsonObject && c["type"].stringOrNull() == "text") yield(c)
        }
    }
}

/** Chữ của một Interaction: các nội dung text trong bước `model_output`. Port của google_ai.py: `extract_text`. */
fun extractText(response: JsonObject): String {
    response["output_text"].stringOrNull()?.let { return it }
    return modelOutputs(response).mapNotNull { nd -> nd["text"]?.takeIf(::pyTruthy)?.let(::pyStr) }.joinToString("")
}

/**
 * Các từ có mốc thời gian (annotation `word_info`) của model `*-transcribe`, theo thứ tự nói. Port của google_ai.py:
 * `extract_words`.
 */
fun extractWords(response: JsonObject): List<Word> {
    val words = mutableListOf<Word>()
    for (nd in modelOutputs(response)) {
        val annotations = nd["annotations"] as? JsonArray ?: continue
        for (annotation in annotations) {
            if (annotation !is JsonObject || annotation["type"].stringOrNull() != "word_info") continue
            val text = pyStrOr(annotation["text"]).pyStrip()
            if (text.isEmpty()) continue
            val start = toSeconds(annotation["start_offset"])
            val end = maxOf(start, toSeconds(annotation["end_offset"]))
            words += Word(
                text = text,
                speaker = annotation["speaker"]?.takeIf(::pyTruthy)?.let(::pyStr),
                start = pyRound(start, 3),
                end = pyRound(end, 3),
            )
        }
    }
    return words
}

/**
 * Ném [GoogleAIException] nếu Interaction không xong; `incomplete` mà có chữ thì vẫn nhận. Port của google_ai.py:
 * `check_status`.
 */
fun checkStatus(response: JsonObject, logger: Logger = GuzzLog): JsonObject {
    val status = (response["status"]?.takeIf(::pyTruthy)?.let(::pyStr) ?: "completed").lowercase()
    if (status == "completed") return response

    val details = (response["errors"] as? JsonArray).orEmpty().joinToString("; ") { e ->
        if (e is JsonObject) {
            (e["message"]?.takeIf(::pyTruthy) ?: e["code"]?.takeIf(::pyTruthy))?.let(::pyStr) ?: e.toString()
        } else {
            pyStr(e)
        }
    }
    val suffix = if (details.isNotEmpty()) ": $details" else ""
    if (status == "incomplete" && extractText(response).pyStrip().isNotEmpty()) {
        logger.warning("   Google AI trả về kết quả chưa trọn vẹn (status incomplete$suffix). Vẫn giữ phần đã có.")
        return response
    }
    val kind = when {
        status == "in_progress" || status == "queued" -> ErrorKind.TEMPORARY
        BLOCKED_IN_STATUS.any { it in "$status $details".lowercase() } -> ErrorKind.BLOCKED
        else -> ErrorKind.OTHER
    }
    throw GoogleAIException("Google AI báo việc gõ chữ '$status'$suffix", kind)
}

private val BLOCKED_IN_STATUS = listOf("blocked", "prohibited_content", "blocklist")

/** Chữ của một Interaction đã kiểm trạng thái. Port của google_ai.py: `parse_response`. */
fun parseResponse(response: JsonObject, logger: Logger = GuzzLog): String = extractText(checkStatus(response, logger))

/** Số token đầu vào Google tính cho một Interaction, 0 nếu phản hồi không có. Port của `parse_input_tokens`. */
fun parseInputTokens(response: JsonObject): Long {
    val usage = (response["usage"]?.takeIf(::pyTruthy) ?: response["usageMetadata"]?.takeIf(::pyTruthy))
        as? JsonObject ?: return 0
    for (field in listOf("total_input_tokens", "input_tokens", "promptTokenCount")) {
        val num = pyIntOrZero(usage[field])
        if (num > 0) return num
    }
    return 0
}

/** `int(x or 0)` của Python, lỗi thì 0: số thực bị cắt phần lẻ, chuỗi phải là số nguyên. */
private fun pyIntOrZero(value: JsonElement?): Long {
    if (!pyTruthy(value)) return 0
    val p = value as? JsonPrimitive ?: return 0
    if (p.isString) return p.content.trim().replace("_", "").toLongOrNull() ?: 0
    if (p.content == "true") return 1
    return p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong() ?: 0
}

/** Đọc thân phản hồi JSON. Port của google_ai.py: `_json` (không phải JSON thì là lỗi tạm thời). */
internal fun parseJsonBody(body: ByteArray): JsonObject {
    val text = body.toString(Charsets.UTF_8)
    val data = try {
        JsonSupport.json.parseToJsonElement(text.ifEmpty { "{}" })
    } catch (_: IllegalArgumentException) {
        throw GoogleAIException(
            "Google AI trả về dữ liệu không phải JSON: " + body.copyOf(minOf(body.size, 200)).toString(Charsets.UTF_8),
            ErrorKind.TEMPORARY,
        )
    }
    return data as? JsonObject ?: JsonObject(emptyMap())
}

/** `str.strip()` của Python (bỏ khoảng trắng Unicode hai đầu). */
internal fun String.pyStrip(): String = trim { it.isWhitespace() || it in '\u001c'..'\u001f' }

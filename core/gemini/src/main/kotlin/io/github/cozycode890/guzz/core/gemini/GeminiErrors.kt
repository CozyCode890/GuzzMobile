package io.github.cozycode890.guzz.core.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Loại lỗi khi gọi Google AI. Port của google_ai.py: `ERR_*`.
 *
 * [code] giữ đúng chuỗi của desktop vì được ghi vào tệp hạn mức (`dem_loi`, `loai_loi`) và log.
 */
enum class ErrorKind(val code: String) {
    /** Mạng, 408: thử lại, hết số lần thì báo tệp lỗi. */
    TEMPORARY("tam_thoi"),

    /** 5xx ("high demand"): thử lại, rồi đổi model dự phòng. */
    OVERLOAD("qua_tai"),

    /** 429: hạn mức phút thì chờ rồi thử lại; hạn mức ngày thì khoá model tới lúc Google tính lại, đổi model. */
    QUOTA("han_muc"),

    /** 400 "Input blocked" (bộ lọc): đổi model dự phòng cho riêng đoạn đó. */
    BLOCKED("bi_chan"),

    /** 404 model không tồn tại: khoá model, đổi model dự phòng. */
    MODEL("model"),

    /** 400 / 401 / 403 khác: API key hay tham số sai; dừng cả hàng đợi. */
    CONFIG("cau_hinh"),

    /** Model báo thất bại…: tệp lỗi. */
    OTHER("khac"),
    ;

    /** Lỗi riêng của một model: còn model dự phòng thì đổi model thay vì báo tệp lỗi. Port của `MODEL_FALLBACK_ERRORS`. */
    val switchesModel: Boolean get() = this == OVERLOAD || this == QUOTA || this == BLOCKED || this == MODEL

    companion object {
        fun fromCode(code: String): ErrorKind? = entries.firstOrNull { it.code == code }
    }
}

/** Đơn vị của một hạn mức Google báo. [code] là chuỗi của desktop (`ngay` / `phut`). */
enum class QuotaUnit(val code: String) {
    DAY("ngay"),
    MINUTE("phut"),
}

/**
 * Một dòng "Quota exceeded for metric: ..., limit: 25, model: ..." trong lỗi 429. Port của google_ai.py:
 * `QuotaViolation`.
 */
data class QuotaViolation(
    val metric: String = "",
    val limit: Int = 0,
    val model: String = "",
    val quotaId: String = "",
) {
    val isToken: Boolean get() = "token" in "$metric $quotaId".lowercase()

    /** Ngày / phút nếu Google nói rõ (quotaId `...PerDay...` / `...PerMinute...`), không thì `null`. */
    val unit: QuotaUnit?
        get() {
            val s = "$quotaId $metric".lowercase()
            return when {
                "perday" in s || "per_day" in s -> QuotaUnit.DAY
                "perminute" in s || "per_minute" in s -> QuotaUnit.MINUTE
                else -> null
            }
        }
}

/**
 * Lỗi khi gọi Google AI. Port của google_ai.py: `GoogleAIError`.
 *
 * Nút Dừng không đi qua lớp này như `Stopped` của desktop: trên mobile là huỷ coroutine (`CancellationException`).
 *
 * @property retryAfter số giây Google bảo nên chờ, 0 nếu không nói.
 * @property stopQueue lỗi sẽ lặp lại ở mọi tệp (key sai, mọi model đều khoá lâu): dừng cả hàng đợi.
 */
class GoogleAIException(
    message: String,
    val kind: ErrorKind = ErrorKind.OTHER,
    val httpStatus: Int? = null,
    val retryAfter: Double = 0.0,
    violations: List<QuotaViolation> = emptyList(),
    stopQueue: Boolean? = null,
) : Exception(message) {
    val violations: List<QuotaViolation> = violations.toList()

    /** Lỗi 429 hết hạn mức trong ngày (client đoán ra): thử lại vô ích. */
    @Volatile
    var isDaily: Boolean = false

    val stopQueue: Boolean = stopQueue ?: (kind == ErrorKind.CONFIG)

    val isRetryable: Boolean
        get() = kind == ErrorKind.TEMPORARY || kind == ErrorKind.OVERLOAD || (kind == ErrorKind.QUOTA && !isDaily)

    override val message: String get() = super.message.orEmpty()
}

/**
 * `'30s'`, `'1.5s'`, `30`, `{"seconds": 1, "nanos": 5e8}` → số giây. Port của google_ai.py: `_to_seconds`.
 *
 * Như Python: chuỗi thì bỏ mọi ký tự không phải số hay dấu chấm rồi đọc số (`"-5s"` → 5), đọc không được thì 0.
 */
fun toSeconds(raw: JsonElement?): Double = when (raw) {
    is JsonObject -> {
        val seconds = pyFloatOrNull(raw["seconds"]) ?: return 0.0
        val nanos = pyFloatOrNull(raw["nanos"]) ?: return 0.0
        seconds + nanos / 1e9
    }
    null, JsonNull -> toSeconds("None")
    is JsonPrimitive -> toSeconds(raw.content)
    is JsonArray -> toSeconds(raw.toString())
}

fun toSeconds(raw: String): Double {
    val digits = raw.filter { it in '0'..'9' || it == '.' }
    if (digits.isEmpty()) return 0.0
    return digits.toDoubleOrNull() ?: 0.0
}

/** `float(x or 0)` của Python cho một giá trị JSON; `null` khi Python ném `ValueError` / `TypeError`. */
private fun pyFloatOrNull(value: JsonElement?): Double? {
    if (value == null || value is JsonNull || !pyTruthy(value)) return 0.0
    val p = value as? JsonPrimitive ?: return null
    if (!p.isString && (p.content == "true" || p.content == "false")) return if (p.content == "true") 1.0 else 0.0
    return p.content.trim().toDoubleOrNull()
}

private val VIOLATION_IN_MESSAGE = Regex(
    "Quota exceeded for metric:\\s*([^\\s,]+)\\s*,\\s*limit:\\s*(\\d+)(?:\\s*,\\s*model:\\s*([^\\s,]+))?",
    setOf(RegexOption.IGNORE_CASE),
)
private val RETRY_IN_MESSAGE = Regex("retry in\\s+([\\d.]+)\\s*s", RegexOption.IGNORE_CASE)
private val BLOCKED_WORDS = listOf("blocked", "prohibited_content", "blocklist")

/**
 * Các hạn mức bị vượt: từ `QuotaFailure` trong `details`, không có thì đọc trong [message]. Port của google_ai.py:
 * `parse_violations`.
 */
fun parseViolations(message: String, detailItems: List<JsonElement>): List<QuotaViolation> {
    val result = mutableListOf<QuotaViolation>()
    for (details in detailItems) {
        if (details !is JsonObject) continue
        val violations = details["violations"] as? JsonArray ?: continue
        for (v in violations) {
            if (v !is JsonObject) continue
            if (!pyTruthy(v["quotaMetric"]) && !pyTruthy(v["quotaId"])) continue
            val dims = v["quotaDimensions"] as? JsonObject ?: JsonObject(emptyMap())
            val limit = pyFloatOrNull(v["quotaValue"])?.let { if (it.isFinite()) it.toLong() else 0L } ?: 0L
            result += QuotaViolation(
                metric = pyStrOr(v["quotaMetric"]),
                limit = limit.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
                model = pyStrOr(dims["model"]),
                quotaId = pyStrOr(v["quotaId"]),
            )
        }
    }
    if (result.isEmpty()) {
        for (m in VIOLATION_IN_MESSAGE.findAll(message)) {
            result += QuotaViolation(
                metric = m.groupValues[1],
                limit = m.groupValues[2].toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE,
                model = m.groupValues[3].trimEnd('.'),
            )
        }
    }
    return result
}

/**
 * Dịch một phản hồi HTTP lỗi thành [GoogleAIException], kèm thời gian nên chờ (nếu Google báo). Port của
 * google_ai.py: `error_from_http`.
 *
 * @param header đọc một header của phản hồi (không phân biệt hoa thường), `null` nếu không có.
 */
fun errorFromHttp(code: Int, body: ByteArray, header: (String) -> String? = { null }): GoogleAIException {
    var message = ""
    var status = ""
    var wait = 0.0
    var detailItems: List<JsonElement> = emptyList()
    val parsed = try {
        val text = body.toString(Charsets.UTF_8)
        JsonSupport.json.parseToJsonElement(text.ifEmpty { "{}" })
    } catch (_: IllegalArgumentException) {
        null
    }
    if (parsed == null) {
        message = body.copyOf(minOf(body.size, 300)).toString(Charsets.UTF_8)
    } else {
        val err: JsonObject = when (parsed) {
            is JsonObject -> when (val e = parsed["error"] ?: parsed) {
                is JsonObject -> e
                else -> JsonObject(mapOf("message" to JsonPrimitive(pyStr(e))))
            }
            else -> JsonObject(emptyMap())
        }
        message = pyStrOr(err["message"])
        status = pyStrOr(err["status"])
        detailItems = (err["details"] as? JsonArray)?.toList() ?: emptyList()
        for (details in detailItems) {
            if (details !is JsonObject) continue
            if (pyTruthy(details["retryDelay"])) wait = maxOf(wait, toSeconds(details["retryDelay"]))
            if (pyTruthy(details["reason"])) status = "$status ${pyStr(details["reason"]!!)}".trim()
        }
    }
    header("Retry-After")?.takeIf { it.isNotEmpty() }?.let { wait = maxOf(wait, toSeconds(it)) }
    // Lỗi 429 của Interactions API không có RetryInfo trong details, chỉ ghi "Please retry in 17.099322891s." trong
    // message.
    RETRY_IN_MESSAGE.find(message)?.let { wait = maxOf(wait, toSeconds(it.groupValues[1])) }

    val describe = "HTTP $code" + (if (status.isNotEmpty()) " $status" else "") +
        (if (message.isNotEmpty()) ": $message" else "")
    var violations = emptyList<QuotaViolation>()
    val text = "$status $message".lowercase()
    val kind = when {
        code == 429 -> {
            violations = parseViolations(message, detailItems)
            ErrorKind.QUOTA
        }
        code >= 500 -> ErrorKind.OVERLOAD
        code == 408 -> ErrorKind.TEMPORARY
        // Lỗi thật 2026-09-17 với gemini-3.8-flash: "Input blocked: This request was blocked by Gemini's filters..."
        // Chỉ riêng yêu cầu đó bị chặn, model vẫn dùng được.
        code == 400 && BLOCKED_WORDS.any { it in text } -> ErrorKind.BLOCKED
        code == 404 && "model" in text -> ErrorKind.MODEL
        code in setOf(400, 401, 403, 404) -> ErrorKind.CONFIG
        else -> ErrorKind.OTHER
    }
    return GoogleAIException(describe, kind, code, wait, violations)
}

/** Giá trị JSON "truthy" theo Python: `None`, `False`, `0`, `""`, `[]`, `{}` là false. */
internal fun pyTruthy(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonObject -> value.isNotEmpty()
    is JsonArray -> value.isNotEmpty()
    is JsonPrimitive -> when {
        value.isString -> value.content.isNotEmpty()
        value.content == "true" -> true
        value.content == "false" -> false
        else -> value.content.toDoubleOrNull()?.let { it != 0.0 } ?: true
    }
}

/** `str(x)` của Python cho chuỗi và số; mảng / object thì lấy dạng JSON (Python in repr, không cần khớp). */
internal fun pyStr(value: JsonElement): String = when (value) {
    JsonNull -> "None"
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.content == "true" -> "True"
        value.content == "false" -> "False"
        else -> value.content
    }
    else -> value.toString()
}

/** `str(x or "")` của Python. */
internal fun pyStrOr(value: JsonElement?): String = if (pyTruthy(value)) pyStr(value!!) else ""

/** Chuỗi trong một [JsonElement] nếu là chuỗi JSON, không thì `null`. */
internal fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

package io.github.cozycode890.guzz.core.gemini

import io.github.cozycode890.guzz.core.common.testing.Golden
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.fail

/** Đọc một tệp golden của Gemini ra cây JSON (cấu trúc lỏng như dict của Python). */
fun goldenJson(name: String): JsonObject =
    Golden.json.parseToJsonElement(Golden.file("gemini/$name").readText(Charsets.UTF_8)).jsonObject

val JsonElement.str: String get() = jsonPrimitive.content
val JsonElement?.strOrNull: String? get() = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

/** Dựng [GeminiConfig] từ các trường của `Config` desktop mà script golden ghi ra (`cfg_json`). */
fun cfgFrom(o: JsonObject): GeminiConfig = GeminiConfig(
    model = o.getValue("model").str,
    transcribeMode = o.getValue("transcribe_mode").str,
    languages = o.getValue("languages").jsonArray.map { it.str },
    vocabulary = o.getValue("vocabulary").jsonArray.map { it.str },
    storeOnServer = o.getValue("store_on_server").jsonPrimitive.boolean,
    timeoutSeconds = o.getValue("timeout_seconds").jsonPrimitive.int,
    chunkRetries = o.getValue("chunk_retries").jsonPrimitive.int,
    deleteRemoteFile = o.getValue("delete_remote_file").jsonPrimitive.boolean,
    tokenRateLimit = o.getValue("token_rate_limit").jsonPrimitive.int,
    autoSwitchModel = o.getValue("auto_switch_model").jsonPrimitive.boolean,
    fallbackModels = o.getValue("fallback_models").jsonArray.map { it.str },
    retriesBeforeSwitch = o.getValue("retries_before_switch").jsonPrimitive.int,
    overloadLockMinutes = o.getValue("overload_lock_minutes").jsonPrimitive.double,
    returnToMainModel = o.getValue("return_to_main_model").jsonPrimitive.boolean,
    switchWaitThresholdSec = o.getValue("switch_wait_threshold_sec").jsonPrimitive.int,
    maxAllLockedWaitMin = o.getValue("max_all_locked_wait_min").jsonPrimitive.double,
    proactiveDailyLock = o.getValue("proactive_daily_lock").jsonPrimitive.boolean,
    modelQuotas = o.getValue("model_quotas").jsonObject.mapValues { (_, q) -> quotaFrom(q) },
    diarizationEnabled = o.getValue("diarization_enabled").jsonPrimitive.boolean,
    diarizationMethod = o.getValue("diarization_method").str,
)

fun quotaFrom(q: JsonElement): Quota {
    val a = q.jsonArray.map { it.jsonPrimitive.int }
    return Quota(a[0], a[1], a[2])
}

/**
 * So hai cây JSON như so dict của Python: số so theo giá trị (`1789660800.0` == `1789660800`), thứ tự khoá không quan
 * trọng. [nullIsAbsent]: khoá có giá trị null coi như không có (tệp hạn mức của Kotlin bỏ trường null, desktop ghi
 * `null`). [ignoreDiacritics]: so chuỗi sau khi bỏ dấu tiếng Việt, vì câu chữ của app viết có dấu ở bản Kotlin còn
 * desktop viết không dấu (vd chi tiết khoá "Đã gửi 2/2 yêu cầu trong ngày.").
 */
fun assertJsonEquals(
    expected: JsonElement,
    actual: JsonElement,
    nullIsAbsent: Boolean = false,
    ignoreDiacritics: Boolean = false,
    path: String = "$",
) {
    fun clean(o: JsonObject) = if (nullIsAbsent) o.filterValues { it !is JsonNull } else o
    when {
        expected is JsonObject && actual is JsonObject -> {
            val e = clean(expected)
            val a = clean(actual)
            if (e.keys != a.keys) fail("$path: khoá khác nhau, mong $e.keys, gặp ${a.keys}")
            for (k in e.keys) assertJsonEquals(e.getValue(k), a.getValue(k), nullIsAbsent, ignoreDiacritics, "$path.$k")
        }
        expected is JsonArray && actual is JsonArray -> {
            if (expected.size != actual.size) fail("$path: mảng dài ${expected.size}, gặp ${actual.size}\n$expected\n$actual")
            expected.indices.forEach { assertJsonEquals(expected[it], actual[it], nullIsAbsent, ignoreDiacritics, "$path[$it]") }
        }
        expected is JsonNull || actual is JsonNull -> if (expected != actual) fail("$path: mong $expected, gặp $actual")
        expected is JsonPrimitive && actual is JsonPrimitive -> {
            val text: (String) -> String = if (ignoreDiacritics) ::withoutDiacritics else { s -> s }
            if (expected.isString || actual.isString) {
                if (expected.isString != actual.isString || text(expected.content) != text(actual.content)) {
                    fail("$path: mong $expected, gặp $actual")
                }
            } else {
                val e = expected.content.toDoubleOrNull()
                val a = actual.content.toDoubleOrNull()
                if (e != null && a != null) {
                    if (e != a) fail("$path: mong $e, gặp $a")
                } else if (expected.content != actual.content) {
                    fail("$path: mong $expected, gặp $actual")
                }
            }
        }
        else -> fail("$path: kiểu khác nhau, mong $expected, gặp $actual")
    }
}

fun withoutDiacritics(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd').replace('Đ', 'D')

/** Đồng hồ giả (giây), dùng chung cho tracker, bộ giãn nhịp và hàm chờ. Server giả đẩy được từ luồng của nó. */
class FakeClock(@Volatile var now: Double = 1000.0) : () -> Double {
    override fun invoke(): Double = now
}

package io.github.cozycode890.guzz.core.gemini

import io.github.cozycode890.guzz.core.common.log.MemorySink
import io.github.cozycode890.guzz.core.common.log.SinkLogger
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * B1: phân loại lỗi trùng desktop. Dữ liệu `gemini/errors.json` do `tools/golden/gen_gemini.py` chạy `error_from_http`,
 * `_to_seconds`, `check_status` của google_ai.py (gồm lỗi thật 17/09).
 */
class ErrorGoldenTest {
    private val golden = goldenJson("errors.json")

    @TestFactory
    fun `error_from_http`(): List<DynamicTest> = golden.getValue("http").jsonArray.map { c ->
        val case = c.jsonObject
        dynamicTest(case.getValue("name").str) {
            val headers = case.getValue("headers").jsonObject.mapValues { it.value.str }
            val e = errorFromHttp(case.getValue("code").jsonPrimitive.int, case.getValue("body").str.toByteArray()) { name ->
                headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            }
            val expected = case.getValue("expected").jsonObject
            assertEquals(expected.getValue("kind").str, e.kind.code, "kind")
            assertEquals(expected.getValue("message").str, e.message, "message")
            assertEquals(expected.getValue("http_status").jsonPrimitive.int, e.httpStatus)
            assertEquals(expected.getValue("retry_after").jsonPrimitive.double, e.retryAfter, "retry_after")
            assertEquals(expected.getValue("is_daily").jsonPrimitive.boolean, e.isDaily)
            assertEquals(expected.getValue("is_retryable").jsonPrimitive.boolean, e.isRetryable, "is_retryable")
            assertEquals(expected.getValue("stop_queue").jsonPrimitive.boolean, e.stopQueue, "stop_queue")
            val violations = expected.getValue("violations").jsonArray.map { it.jsonObject }
            assertEquals(violations.size, e.violations.size, "số vi phạm")
            violations.zip(e.violations).forEach { (v, k) ->
                assertEquals(v.getValue("metric").str, k.metric)
                assertEquals(v.getValue("limit").jsonPrimitive.int, k.limit)
                assertEquals(v.getValue("model").str, k.model)
                assertEquals(v.getValue("quota_id").str, k.quotaId)
                assertEquals(v.getValue("is_token").jsonPrimitive.boolean, k.isToken)
                assertEquals(v.getValue("unit").str, k.unit?.code.orEmpty())
            }
        }
    }

    @TestFactory
    fun `_to_seconds`(): List<DynamicTest> = golden.getValue("to_seconds").jsonArray.mapIndexed { i, c ->
        val case = c.jsonObject
        dynamicTest("$i: ${case["raw"]}") {
            val raw = case["raw"].takeUnless { it is JsonNull }
            assertEquals(case.getValue("expected").jsonPrimitive.double, toSeconds(raw))
        }
    }

    @TestFactory
    fun `check_status`(): List<DynamicTest> = golden.getValue("check_status").jsonArray.map { c ->
        val case = c.jsonObject
        dynamicTest(case.getValue("name").str) {
            val response = case.getValue("response").jsonObject
            val logger = SinkLogger().apply { addSink(MemorySink()) }
            if (case.getValue("ok").jsonPrimitive.boolean) {
                assertEquals(response, checkStatus(response, logger))
            } else {
                val e = assertFailsWith<GoogleAIException> { checkStatus(response, logger) }
                assertEquals(case.getValue("kind").str, e.kind.code)
                // Câu chữ của app có dấu ở bản Kotlin; phần sau tiền tố (trạng thái, chi tiết của Google) phải trùng.
                assertEquals(
                    case.getValue("message").str.removePrefix("Google AI bao viec go chu "),
                    e.message.removePrefix("Google AI báo việc gõ chữ "),
                )
            }
        }
    }
}

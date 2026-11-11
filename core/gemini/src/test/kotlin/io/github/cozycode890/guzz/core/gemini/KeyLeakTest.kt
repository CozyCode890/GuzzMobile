package io.github.cozycode890.guzz.core.gemini

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C4: key không lọt ra log (kể cả mức DEBUG, D53), thông điệp lỗi hay tệp hạn mức. Key giả, dài như key thật nhưng
 * không bắt đầu bằng `AIza`, để lệnh kiểm `git grep AIza` lúc đóng stage chỉ bắt key thật.
 */
class KeyLeakTest {
    private val key = "FAKEKEY-0123456789abcdefghijKLMNOPQ"

    private fun assertNoKey(where: String, text: String) =
        assertFalse(key in text, "$where chứa key: $text")

    @Test
    fun `lỗi của Google trích lại key thì bị che`() = runBlocking {
        val message = "API key not valid. Please pass a valid API key. (key=$key)"
        FakeGemini { _, _ -> Reply.Error(400, String(errorBody(400, message, "INVALID_ARGUMENT"))) }.use { fake ->
            val h = ClientHarness(fake, apiKey = key)
            val e = assertFailsWith<GoogleAIException> { h.client.transcribe(fakeAudio(), makeConfig()) }
            assertEquals(ErrorKind.CONFIG, e.kind)
            assertTrue(maskApiKey(key) in e.message, e.message)
            assertNoKey("thông điệp lỗi", e.message)
            assertTrue(h.log.lines.any { "Yêu cầu:" in it }, "log DEBUG phải có thân yêu cầu (D53)")
            h.log.lines.forEach { assertNoKey("log", it) }
            assertNoKey("tệp hạn mức", QuotaTracker.FILE_JSON.encodeToString(UsageData.serializer(), h.tracker.snapshot().data))
            // Key chỉ đi ở header, không ở URL.
            fake.requests.forEach {
                assertEquals(key, it.header("x-goog-api-key"))
                assertNoKey("URL", it.path)
            }
        }
    }

    @Test
    fun `chuỗi lỗi 17-09 ở mức DEBUG không ghi key`() {
        for (run in goldenJson("replay.json").getValue("runs").jsonArray) {
            val golden = goldenJson("replay.json")
            val o = ReplayGoldenTest.replay(
                run as kotlinx.serialization.json.JsonObject, golden.getValue("start").str.toDouble(),
                golden.getValue("process_sec").str.toDouble(), apiKey = key,
            )
            assertTrue(o.logLines.isNotEmpty())
            o.logLines.forEach { assertNoKey("log", it) }
            assertNoKey("tệp hạn mức", QuotaTracker.FILE_JSON.encodeToString(UsageData.serializer(), o.tracker.snapshot().data))
        }
    }

    @Test
    fun `key dán nhầm vào ô model không phải tên model hợp lệ`() {
        for (name in listOf("gemini-3.5-flash-lite", "gemini-3.5-transcribe", "gemini-2.5-flash-preview-09-2025", "my_model.v2")) {
            assertTrue(isValidModelName(name), name)
        }
        // Key kiểu cũ và kiểu mới (có chữ hoa), chuỗi có khoảng trắng, chuỗi rỗng.
        for (name in listOf(key, "AQ.FAKE-0123456789_abcdefXYZ", "Gemini-3", "gemini 3", "", "-gemini", "models/gemini-3")) {
            assertFalse(isValidModelName(name), name)
        }
    }

    @Test
    fun `mask_api_key chỉ giữ 4 ký tự đầu và cuối`() {
        assertEquals("FAKE…NOPQ", maskApiKey(key))
    }
}

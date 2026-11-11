package io.github.cozycode890.guzz.core.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C1: port `TestRequest`, `TestErrorClassification`, `TestTranscribe`, `TestSpeakersAndAttachments` của
 * tests/test_google_ai.py, HTTP giả bằng MockWebServer (bỏ `TestApiKey`: mobile không đọc tệp key). Tên ca giữ như
 * desktop để đối chiếu.
 */
class GeminiClientTest {

    @Nested
    inner class TestRequest {
        @Test
        fun test_transcribe_model_sends_transcription_config_without_prompt() {
            val cfg = makeConfig().copy(transcribeMode = "verbatim", languages = listOf("vi-VN"), vocabulary = listOf("Data Mining"))
            val req = buildRequest(cfg, "uri://a", "audio/flac", prompt = "bi bo qua")
            assertEquals(
                buildJsonArray { add(buildJsonObject { put("type", "audio"); put("uri", "uri://a"); put("mime_type", "audio/flac") }) },
                req.getValue("input"),
            )
            assertJsonEquals(
                JsonSupport.json.parseToJsonElement(
                    """{"mode": {"type": "verbatim"}, "language_codes": ["vi-VN"], "custom_vocabulary": ["Data Mining"]}""",
                ),
                req.getValue("generation_config").jsonObject.getValue("transcription_config"),
            )
            assertEquals(JsonPrimitive(false), req["store"])
        }

        @Test
        fun test_general_model_sends_prompt_before_audio() {
            val cfg = makeConfig().copy(model = "gemini-3.8-flash", storeOnServer = true)
            val req = buildRequest(cfg, "uri://a", "audio/flac", prompt = "Go chu giup.")
            val input = req.getValue("input").jsonArray
            assertEquals(buildJsonObject { put("type", "text"); put("text", "Go chu giup.") }, input[0])
            assertEquals("audio", input[1].jsonObject.getValue("type").str)
            assertFalse("generation_config" in req)
            assertFalse("store" in req)
        }

        @Test
        fun test_extract_text_only_takes_model_output() {
            assertEquals("Xin chào các bạn.", parseResponse(resultDone()))
        }

        @Test
        fun test_status_failed_is_other_error() {
            val e = assertFailsWith<GoogleAIException> {
                parseResponse(JsonSupport.json.parseToJsonElement("""{"status": "failed", "errors": [{"message": "hong"}]}""").jsonObject)
            }
            assertEquals(ErrorKind.OTHER, e.kind)
            assertTrue("hong" in e.message)
        }
    }

    @Nested
    inner class TestErrorClassification {
        private fun body(json: String) = json.toByteArray()

        @Test
        fun test_429_is_quota_with_retry_delay() {
            val e = errorFromHttp(
                429,
                body(
                    """{"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "message": "quota",
                    "details": [{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "37s"}]}}""",
                ),
            )
            assertEquals(ErrorKind.QUOTA, e.kind)
            assertTrue(e.isRetryable)
            assertEquals(37.0, e.retryAfter)
            assertTrue("RESOURCE_EXHAUSTED" in e.message)
        }

        @Test
        fun test_429_wait_time_in_message() {
            // Dùng nguyên văn lỗi thật lấy từ nhật ký ngày 2026-09-17.
            val message = "You exceeded your current quota, please check your plan and billing details.\n" +
                "* Quota exceeded for metric: generativelanguage.googleapis.com/" +
                "generate_content_free_tier_input_token_count, limit: 10000, " +
                "model: gemini-3.5-transcribe\nPlease retry in 55.981490556s."
            val e = errorFromHttp(429, errorBody(429, message, "RESOURCE_EXHAUSTED"))
            assertEquals(ErrorKind.QUOTA, e.kind)
            assertEquals(55.981490556, e.retryAfter, 1e-9)
            assertEquals(
                listOf(
                    QuotaViolation(
                        "generativelanguage.googleapis.com/generate_content_free_tier_input_token_count", 10000,
                        "gemini-3.5-transcribe",
                    ),
                ),
                e.violations,
            )
            assertTrue(e.violations[0].isToken)
        }

        @Test
        fun test_retry_waits_as_long_as_google_asks() = runBlocking {
            var call = 0
            FakeGemini { _, _ ->
                if (++call == 1) Reply.Error(429, """{"error": {"message": "Please retry in 55.9s."}}""")
                else Reply.Ok(resultDone())
            }.use { fake ->
                val h = ClientHarness(fake)
                h.client.transcribe(fakeAudio(), makeConfig())
                assertEquals(57.9, h.waits.last(), 1e-9)
            }
        }

        /** Lỗi thật 2026-09-17: limit 25 yêu cầu, app gửi mỗi phút một đoạn → hạn mức NGÀY. */
        @Test
        fun test_429_daily_quota_not_retried_and_recorded() = runBlocking {
            val message = "You exceeded your current quota, please check your plan and billing details.\n" +
                "* Quota exceeded for metric: " +
                "generativelanguage.googleapis.com/generate_content_free_tier_requests, " +
                "limit: 25, model: gemini-3.5-transcribe\nPlease retry in 27.888016836s."
            FakeGemini { _, _ -> Reply.error(429, message) }.use { fake ->
                val h = ClientHarness(fake)
                val e = assertFailsWith<GoogleAIException> {
                    h.client.transcribe(
                        fakeAudio(), makeConfig().copy(chunkRetries = 4), options = TranscribeOptions(model = "gemini-3.5-transcribe"),
                    )
                }
                assertEquals(ErrorKind.QUOTA, e.kind)
                assertTrue(e.isDaily)
                assertFalse(e.isRetryable)
                assertTrue(h.waits.isEmpty())
                assertEquals(mapOf("rpd" to 25), h.tracker.learnedLimits("gemini-3.5-transcribe"))
                assertEquals(
                    mapOf("han_muc_ngay" to 1),
                    h.tracker.snapshot().data.models.getValue("gemini-3.5-transcribe").errorCounts,
                )
            }
        }

        @Test
        fun test_fewer_attempts_when_fallback_models_remain() = runBlocking {
            var call = 0
            FakeGemini { _, _ -> if (++call <= 2) Reply.error(500, "high demand") else Reply.Ok(resultDone()) }.use { fake ->
                val h = ClientHarness(fake)
                val e = assertFailsWith<GoogleAIException> {
                    h.client.transcribe(fakeAudio(), makeConfig().copy(chunkRetries = 4), options = TranscribeOptions(maxAttempts = 2))
                }
                assertEquals(ErrorKind.OVERLOAD, e.kind)
                assertEquals(2, fake.interactionModels().size) // còn một kết quả chưa dùng
            }
        }

        @Test
        fun test_bad_key_is_config_error() {
            val e = errorFromHttp(
                400,
                body("""{"error": {"status": "INVALID_ARGUMENT", "message": "API key not valid.", "details": [{"reason": "API_KEY_INVALID"}]}}"""),
            )
            assertEquals(ErrorKind.CONFIG, e.kind)
            assertTrue("API_KEY_INVALID" in e.message)
        }

        @Test
        fun test_503_with_non_json_body() {
            assertEquals(ErrorKind.OVERLOAD, errorFromHttp(503, body("<html>Service Unavailable</html>")).kind)
        }

        @Test
        fun test_500_high_demand_is_overload() {
            // Nguyên văn lỗi thật của gemini-3.8-flash ngày 2026-09-17.
            val e = errorFromHttp(
                500,
                errorBody(
                    500,
                    "gemini-3.8-flash is currently experiencing high demand, spikes in demand are usually temporary. " +
                        "Please try again later.",
                ),
            )
            assertEquals(ErrorKind.OVERLOAD, e.kind)
            assertTrue(e.isRetryable)
            assertTrue(e.kind.switchesModel)
            assertFalse(e.stopQueue)
        }

        @Test
        fun test_input_blocked_does_not_stop_queue() {
            // Lỗi thật ngày 2026-09-17: trước đây bị coi là lỗi cấu hình và dừng cả hàng đợi.
            val e = errorFromHttp(
                400,
                errorBody(
                    400,
                    "Input blocked: This request was blocked by Gemini's filters. They can occasionally trigger by " +
                        "mistake on safe coding, security, or biology-related queries. Please try rephrasing your prompt.",
                ),
            )
            assertEquals(ErrorKind.BLOCKED, e.kind)
            assertFalse(e.isRetryable)
            assertFalse(e.stopQueue)
        }

        @Test
        fun test_quota_failure_in_details() {
            val e = errorFromHttp(
                429,
                body(
                    """{"error": {"status": "RESOURCE_EXHAUSTED", "message": "quota", "details": [
                    {"@type": "type.googleapis.com/google.rpc.QuotaFailure", "violations": [{
                      "quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_requests",
                      "quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier",
                      "quotaDimensions": {"model": "gemini-3.8-flash", "location": "global"},
                      "quotaValue": "20"}]}]}}""",
                ),
            )
            assertEquals(QuotaUnit.DAY, e.violations[0].unit)
            assertEquals(20 to "gemini-3.8-flash", e.violations[0].limit to e.violations[0].model)
            assertFalse(e.violations[0].isToken)
        }

        @Test
        fun test_model_not_found() {
            val e = errorFromHttp(404, errorBody(404, "models/gemini-9 is not found for API version v1beta"))
            assertEquals(ErrorKind.MODEL, e.kind)
        }
    }

    @Nested
    inner class TestTranscribe {
        private val audio = fakeAudio()

        @Test
        fun test_uploads_transcribes_then_deletes() = runBlocking {
            FakeGemini { _, _ -> Reply.Ok(resultDone()) }.use { fake ->
                val h = ClientHarness(fake, apiKey = "KEY123")
                assertEquals("Xin chào các bạn.", h.client.transcribe(audio, makeConfig()))
                assertEquals(listOf("POST", "POST", "POST", "DELETE"), fake.requests.map { it.method })
                val start = fake.requests[0]
                assertEquals("start", start.header("X-Goog-Upload-Command"))
                assertEquals("resumable", start.header("X-Goog-Upload-Protocol"))
                assertEquals("104", start.header("X-Goog-Upload-Header-Content-Length"))
                assertEquals("audio/flac", start.header("X-Goog-Upload-Header-Content-Type"))
                assertEquals("KEY123", start.header("x-goog-api-key"))
                assertEquals("doan_001.flac", start.json.getValue("file").jsonObject.getValue("display_name").str)
                val upload = fake.requests[1]
                assertEquals("upload, finalize", upload.header("X-Goog-Upload-Command"))
                assertEquals("0", upload.header("X-Goog-Upload-Offset"))
                assertEquals(104, upload.body.size)
                val interaction = fake.requests[2]
                assertEquals("/v1beta/interactions", interaction.path)
                assertEquals("application/json", interaction.header("Content-Type"))
                assertEquals("KEY123", interaction.header("x-goog-api-key"))
                assertEquals("https://g/files/f1", interaction.json.getValue("input").jsonArray[0].jsonObject.getValue("uri").str)
                assertTrue(fake.requests[3].path.endsWith("/v1beta/files/f1"))
                fake.requests.forEach { assertFalse("KEY123" in it.path, "key không được nằm trong URL") }
            }
        }

        @Test
        fun test_temporary_error_retried_then_succeeds() = runBlocking {
            var call = 0
            FakeGemini { _, _ -> if (++call == 1) Reply.error(503, "overloaded") else Reply.Ok(resultDone()) }.use { fake ->
                val h = ClientHarness(fake)
                assertEquals("Xin chào các bạn.", h.client.transcribe(audio, makeConfig().copy(chunkRetries = 3)))
                assertEquals(1, h.waits.size)
                // Mỗi lần thử đều xoá tệp đã tải lên.
                assertEquals(2, fake.requests.count { it.method == "DELETE" })
            }
        }

        @Test
        fun test_out_of_attempts_raises_quota_error() = runBlocking {
            FakeGemini { _, _ -> Reply.error(429, "q") }.use { fake ->
                val h = ClientHarness(fake)
                val e = assertFailsWith<GoogleAIException> { h.client.transcribe(audio, makeConfig().copy(chunkRetries = 2)) }
                assertEquals(ErrorKind.QUOTA, e.kind)
                assertFalse(e.isDaily)
            }
        }

        @Test
        fun test_model_not_found_is_not_retried() = runBlocking {
            FakeGemini { _, _ -> Reply.error(404, "model not found") }.use { fake ->
                val h = ClientHarness(fake)
                val e = assertFailsWith<GoogleAIException> { h.client.transcribe(audio, makeConfig().copy(chunkRetries = 4)) }
                assertEquals(ErrorKind.MODEL, e.kind)
                assertTrue(h.waits.isEmpty())
            }
        }

        @Test
        fun test_bad_key_stops_queue() {
            val e = errorFromHttp(403, errorBody(403, "Permission denied"))
            assertEquals(ErrorKind.CONFIG, e.kind)
            assertTrue(e.stopQueue)
        }

        @Test
        fun test_network_down_is_temporary() = runBlocking {
            // Cổng 1 trên máy này không có ai nghe: kết nối bị từ chối ngay.
            val client = GeminiClient("K", apiRoot = "http://127.0.0.1:1", logger = quietLogger())
            val e = assertFailsWith<GoogleAIException> { client.listModels() }
            assertEquals(ErrorKind.TEMPORARY, e.kind)
        }

        /** Nút Dừng (huỷ coroutine) cắt ngang lúc đang chờ thử lại, không phải đợi hết 15 giây. */
        @Test
        fun test_stop_button_interrupts_wait() = runBlocking {
            FakeGemini { _, _ -> Reply.error(503, "overloaded") }.use { fake ->
                val tracker = QuotaTracker(null, logger = quietLogger())
                val client = GeminiClient("K", tracker, apiRoot = fake.root, logger = quietLogger())
                val job = launch(Dispatchers.Default) { client.transcribe(audio, makeConfig()) }
                withTimeout(10_000) { while (tracker.snapshot().waiting == null) delay(10) }
                assertEquals("thu_lai", tracker.snapshot().waiting?.reason)
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertNull(tracker.snapshot().waiting)
                assertEquals(1, fake.requests.count { it.method == "DELETE" })
            }
        }
    }

    @Nested
    inner class TestSpeakersAndAttachments {
        private val audio = fakeAudio(10)
        private val timeMap = File(audio.parentFile, "doan_001.nguoi_noi.txt").apply {
            writeText("00:00.0 - 00:05.0  Giảng viên\n")
            deleteOnExit()
        }

        @Test
        fun test_transcribe_diarize_forces_verbatim_and_drops_vocabulary() {
            val cfg = makeConfig().copy(transcribeMode = "smart", languages = listOf("vi-VN"), vocabulary = listOf("SVM"))
            val req = buildRequest(cfg, "uri://a", "audio/flac", model = "gemini-3.5-transcribe", diarize = true, wordTimestamps = true)
            assertJsonEquals(
                JsonSupport.json.parseToJsonElement(
                    """{"mode": {"type": "verbatim", "diarization_mode": "speaker", "timestamp_granularities": ["word"]},
                    "language_codes": ["vi-VN"]}""",
                ),
                req.getValue("generation_config").jsonObject.getValue("transcription_config"),
            )
        }

        @Test
        fun test_transcribe_word_timestamps_only() {
            val req = buildRequest(
                makeConfig().copy(transcribeMode = "verbatim"), "uri://a", "audio/flac",
                model = "gemini-3.5-transcribe", wordTimestamps = true,
            )
            assertEquals(
                buildJsonObject { put("type", "verbatim"); putJsonArray("timestamp_granularities") { add(JsonPrimitive("word")) } },
                req.getValue("generation_config").jsonObject.getValue("transcription_config").jsonObject.getValue("mode"),
            )
        }

        @Test
        fun test_general_model_sends_prompt_audio_then_document() {
            val req = buildRequest(
                makeConfig(), "uri://a", "audio/flac", "P", model = "gemini-3.8-flash",
                document = Document("uri://b", "text/plain"),
            )
            assertEquals("gemini-3.8-flash", req.getValue("model").str)
            val input = req.getValue("input").jsonArray
            assertEquals(listOf("text", "audio", "document"), input.map { it.jsonObject.getValue("type").str })
            assertEquals(
                buildJsonObject { put("type", "document"); put("uri", "uri://b"); put("mime_type", "text/plain") },
                input[2],
            )
        }

        @Test
        fun test_speaker_map_inlined_into_prompt() {
            val req = buildRequest(makeConfig(), "uri://a", "audio/flac", "P", model = "gemini-3.8-flash", attachedText = "00:00.0 - 00:05.0  A")
            val input = req.getValue("input").jsonArray
            assertTrue(input[0].jsonObject.getValue("text").str.endsWith("SPEAKER MAP:\n00:00.0 - 00:05.0  A"))
            assertEquals(2, input.size)
        }

        @Test
        fun test_extract_words() {
            val response = JsonSupport.json.parseToJsonElement(
                """{"id": "int_2", "status": "completed", "steps": [{"type": "model_output", "content": [{
                  "type": "text", "text": "Hello world", "annotations": [
                    {"type": "word_info", "text": "Hello", "speaker": "spk_1", "start_offset": "0.100s", "end_offset": "0.450s"},
                    {"type": "word_info", "text": "world", "speaker": "spk_2", "start_offset": "0.500s", "end_offset": "0.850s"},
                    {"type": "other", "text": "bo qua"}]}]}]}""",
            ).jsonObject
            assertEquals(
                listOf(Word("Hello", "spk_1", 0.1, 0.45), Word("world", "spk_2", 0.5, 0.85)),
                extractWords(response),
            )
            assertEquals(2.5, toSeconds(buildJsonObject { put("seconds", "2"); put("nanos", 500000000) }))
        }

        @Test
        fun test_uploads_both_files_then_deletes_both() = runBlocking {
            FakeGemini(
                fileNames = listOf("files/au", "files/bd").iterator(),
                fileMime = { if (it.endsWith("bd")) "text/plain" else "audio/flac" },
            ) { _, _ -> Reply.Ok(resultDone()) }.use { fake ->
                val h = ClientHarness(fake)
                val text = h.client.transcribe(
                    audio, makeConfig(), "P", options = TranscribeOptions(model = "gemini-3.8-flash", attachment = timeMap),
                )
                assertEquals("Xin chào các bạn.", text)
                assertEquals("text/plain", fake.requests[2].header("X-Goog-Upload-Header-Content-Type"))
                val body = fake.requests.first { it.path == "/v1beta/interactions" }.json
                assertEquals("https://g/files/bd", body.getValue("input").jsonArray[2].jsonObject.getValue("uri").str)
                assertEquals(
                    listOf("au", "bd"),
                    fake.requests.filter { it.method == "DELETE" }.map { it.path.substringAfterLast('/') }.sorted(),
                )
            }
        }
    }

    @Nested
    inner class Models {
        @Test
        fun `list_models đọc đủ các trang, bỏ tiền tố models`() = runBlocking {
            val pages = ArrayDeque(
                listOf(
                    """{"models": [{"name": "models/gemini-3.5-flash-lite"}, {"name": "models/gemini-3.5-transcribe"}], "nextPageToken": "a/b c"}""",
                    """{"models": [{"name": "gemini-3.8-flash"}, {}]}""",
                ),
            )
            FakeGemini { _, _ -> Reply.Ok(JsonPrimitive("")) }.use { fake ->
                fake.server.dispatcher = object : mockwebserver3.Dispatcher() {
                    override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                        fake.requests += SeenRequest(request.method, request.url.toString(), request.headers, ByteArray(0))
                        return mockwebserver3.MockResponse.Builder().body(pages.removeFirst()).build()
                    }
                }
                val client = GeminiClient("K", apiRoot = fake.root, logger = quietLogger())
                assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.5-transcribe", "gemini-3.8-flash"), client.listModels())
                val urls = fake.requests.map { it.path.toHttpUrl() }
                assertEquals("/v1beta/models", urls[0].encodedPath)
                assertEquals("1000", urls[0].queryParameter("pageSize"))
                assertNull(urls[0].queryParameter("pageToken"))
                assertEquals("a/b c", urls[1].queryParameter("pageToken"))
                assertEquals("K", fake.requests[0].header("x-goog-api-key"))
            }
        }
    }
}

/** Thân lỗi `{"error": {"code", "message", "status"?}}`. */
fun errorBody(code: Int, message: String, status: String? = null): ByteArray = buildJsonObject {
    putJsonObject("error") {
        put("code", code)
        if (status != null) put("status", status)
        put("message", message)
    }
}.toString().toByteArray()

fun quietLogger() = io.github.cozycode890.guzz.core.common.log.SinkLogger().apply {
    addSink(io.github.cozycode890.guzz.core.common.log.MemorySink())
}

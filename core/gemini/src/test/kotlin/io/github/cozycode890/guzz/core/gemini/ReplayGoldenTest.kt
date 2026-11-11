package io.github.cozycode890.guzz.core.gemini

import io.github.cozycode890.guzz.core.common.pyRound
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C3: chuỗi lỗi thật ngày 17/09 (và vài kịch bản thêm) chạy qua [GeminiClient] thật + [ChunkDispatcher], chỉ HTTP là
 * giả (MockWebServer). Golden `gemini/replay.json` do `gen_gemini.py` chạy cùng kịch bản trên `Client` + `ChunkDispatcher`
 * của desktop với cùng đồng hồ giả: phải ra cùng thứ tự yêu cầu HTTP, cùng model từng đoạn, cùng thời gian chờ, cùng
 * sổ hạn mức (gồm sự kiện).
 *
 * Hai kịch bản đầu là `TestReplayRealLog` của tests/test_transcribe.py; [test_daily_quota_then_high_demand_moves_to_flash_lite]
 * và [test_input_blocked_does_not_stop_file] lặp lại đúng các khẳng định của desktop.
 */
class ReplayGoldenTest {
    private val golden = goldenJson("replay.json")

    class Outcome(
        val requests: List<String>,
        val results: List<Pair<String, String>>,
        val error: GoogleAIException?,
        val waits: List<Double>,
        val finalModel: String,
        val clockEnd: Double,
        val tracker: QuotaTracker,
        val logLines: List<String>,
    )

    private fun run(name: String, apiKey: String = "KHONG_PHAI_KEY"): Outcome {
        val run = golden.getValue("runs").jsonArray.map { it.jsonObject }.first { it.getValue("name").str == name }
        return replay(run, golden.getValue("start").jsonPrimitive.double, golden.getValue("process_sec").jsonPrimitive.double, apiKey)
    }

    @TestFactory
    fun `trùng desktop`(): List<DynamicTest> = golden.getValue("runs").jsonArray.map { r ->
        val run = r.jsonObject
        dynamicTest(run.getValue("name").str) {
            val o = replay(run, golden.getValue("start").jsonPrimitive.double, golden.getValue("process_sec").jsonPrimitive.double)
            assertEquals(run.getValue("requests").jsonArray.map { it.str }, o.requests, "thứ tự yêu cầu HTTP")
            assertEquals(
                run.getValue("results").jsonArray.map { it.jsonObject.getValue("model").str to it.jsonObject.getValue("text").str },
                o.results,
            )
            val error = run.getValue("error")
            if (error is JsonNull) {
                assertEquals(null, o.error?.message)
            } else {
                assertEquals(error.jsonObject.getValue("kind").str, o.error?.kind?.code)
            }
            assertEquals(run.getValue("waits").jsonArray.map { it.jsonPrimitive.double }, o.waits.map { pyRound(it, 3) }, "các lần chờ")
            assertEquals(run.getValue("final_model").str, o.finalModel)
            assertEquals(run.getValue("clock_end").jsonPrimitive.double, o.clockEnd, 1e-6)
            val actual = JsonSupport.json.parseToJsonElement(
                QuotaTracker.FILE_JSON.encodeToString(UsageData.serializer(), o.tracker.snapshot().data),
            )
            assertJsonEquals(run.getValue("tracker"), actual, nullIsAbsent = true, ignoreDiacritics = true)
        }
    }

    @Test
    fun test_daily_quota_then_high_demand_moves_to_flash_lite() {
        val o = run("daily_quota_then_high_demand")
        val models = o.requests.filter { it.startsWith("interaction ") }.map { it.removePrefix("interaction ") }
        assertTrue(o.results.size >= 3)
        // Đoạn 1 transcribe; đoạn 2: 429 hạn mức NGÀY → không thử lại; 3.8-flash quá tải thử 2 lần → flash-lite.
        assertEquals(
            listOf("gemini-3.5-transcribe", "gemini-3.5-transcribe", "gemini-3.8-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite"),
            models.take(5),
        )
        // Các đoạn sau không gọi lại model đã khoá.
        assertEquals(setOf("gemini-3.5-flash-lite"), models.drop(5).toSet())
        assertEquals(LockReason.DAILY_QUOTA, o.tracker.lockStatus("gemini-3.5-transcribe").reason)
        assertEquals(LockReason.OVERLOADED, o.tracker.lockStatus("gemini-3.8-flash").reason)
        assertEquals(mapOf("rpd" to 25), o.tracker.learnedLimits("gemini-3.5-transcribe"))
        assertEquals(
            mapOf("gemini-3.5-transcribe" to 1, "gemini-3.5-flash-lite" to o.results.size - 1),
            o.results.groupingBy { it.first }.eachCount(),
        )
        assertTrue(o.tracker.snapshot().data.events.any { it.kind == UsageEventKind.SWITCH_MODEL })
    }

    @Test
    fun test_input_blocked_does_not_stop_file() {
        val o = run("input_blocked")
        val models = o.requests.filter { it.startsWith("interaction ") }.map { it.removePrefix("interaction ") }
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash"), models.take(4))
        assertFalse(o.tracker.isLocked("gemini-3.8-flash"))
        assertTrue(o.results.any { it.second == "gemini-3.5-flash-lite: ok" })
        assertEquals(mapOf("bi_chan" to 1), o.tracker.snapshot().data.models.getValue("gemini-3.8-flash").errorCounts)
    }

    companion object {
        /** Chạy một kịch bản của `replay.json` như `run_replay` trong `gen_gemini.py`. */
        fun replay(run: JsonObject, start: Double, processSec: Double, apiKey: String = "KHONG_PHAI_KEY"): Outcome {
            val cfg = cfgFrom(run.getValue("cfg").jsonObject)
            val script = run.getValue("script").jsonObject.mapValues { (_, items) -> items.jsonArray.toMutableList() }
            val clock = FakeClock(start)
            val fake = FakeGemini(onInteraction = { clock.now += processSec }) { model, _ ->
                val items = script.getValue(model)
                when (val item = if (items.size > 1) items.removeAt(0) else items[0]) {
                    is JsonArray -> Reply.error(item[0].jsonPrimitive.int, item[1].str)
                    else -> Reply.Ok(
                        buildJsonObject {
                            put("status", "completed")
                            putJsonArray("steps") {
                                add(
                                    buildJsonObject {
                                        put("type", "model_output")
                                        putJsonArray("content") {
                                            add(buildJsonObject { put("type", "text"); put("text", "$model: ${item.str}") })
                                        }
                                    },
                                )
                            }
                            putJsonObject("usage") { put("total_input_tokens", 3000) }
                        },
                    )
                }
            }
            fake.use {
                val h = ClientHarness(fake, clock, apiKey = apiKey)
                val dispatcher = ChunkDispatcher(
                    cfg, h.client,
                    sleep = { sec ->
                        h.waits += sec
                        clock.now += sec
                    },
                    logger = h.logger,
                )
                val audio = fakeAudio()
                val results = mutableListOf<Pair<String, String>>()
                var error: GoogleAIException? = null
                runBlocking {
                    for (i in 1..run.getValue("chunks").jsonPrimitive.int) {
                        try {
                            val r = dispatcher.send(audio, 15.0, { m -> (if (isTranscribeModel(m)) null else "PROMPT") to TranscribeOptions() })
                            results += r.model to extractText(r.response)
                        } catch (e: GoogleAIException) {
                            error = e
                            break
                        }
                    }
                }
                return Outcome(fake.kinds(), results, error, h.waits.toList(), dispatcher.currentModel, clock(), h.tracker, h.log.lines)
            }
        }
    }
}

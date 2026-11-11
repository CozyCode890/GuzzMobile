package io.github.cozycode890.guzz.core.gemini

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C2: port các ca của `TestAutoSwitchModel` (tests/test_transcribe.py) thuộc phạm vi [ChunkDispatcher], client giả như
 * `FakeClient` của desktop. Desktop chạy cả `transcribe_single_file` trên bài 60 giây (2–3 đoạn); ở đây [sendChunks]
 * gửi 3 đoạn qua dispatcher như vòng lặp của `transcribe_chunks`.
 *
 * Hai ca "bản chép lời rỗng" (`test_empty_transcript_retries_with_another_model`, `test_empty_result_is_not_cached`)
 * thuộc pipeline → Phát sinh Stage 4.
 */
class ChunkDispatcherTest {

    /** Client giả: [reply] (số thứ tự lần gọi, tham số) → Interaction hoặc lỗi. Port của `FakeClient`. */
    private class FakeClient(
        override val tracker: QuotaTracker,
        val reply: (call: Int, options: TranscribeOptions) -> Any,
    ) : ChunkTranscriber {
        class Call(val file: String, val prompt: String?, val options: TranscribeOptions)

        val calls = mutableListOf<Call>()

        override suspend fun transcribeRaw(
            audio: File,
            cfg: GeminiConfig,
            prompt: String?,
            audioSeconds: Double?,
            options: TranscribeOptions,
        ): JsonObject {
            calls += Call(audio.name, prompt, options)
            return when (val result = reply(calls.size, options)) {
                is GoogleAIException -> throw result
                is JsonObject -> result
                else -> error("kết quả lạ: $result")
            }
        }
    }

    private val clock = FakeClock(epoch(2026, 9, 17, 16))
    private val tracker = QuotaTracker(null, clock, quietLogger())
    private val sleeps = mutableListOf<Double>()

    /** Cấu hình của `TestTranscribe.setUp`: ghim model và thứ tự dự phòng, hạn mức mặc định của desktop. */
    private var cfg = GeminiConfig(
        model = "gemini-3.5-transcribe",
        fallbackModels = listOf("gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.5-transcribe"),
        tokenRateLimit = 0,
    )

    private fun client(reply: (Int, TranscribeOptions) -> Any) = FakeClient(tracker, reply)

    private fun dispatcher(client: FakeClient, onSleep: (Double) -> Unit = {}) = ChunkDispatcher(
        cfg, client,
        sleep = { sec ->
            sleeps += sec
            onSleep(sec)
            clock.now += sec
        },
        logger = quietLogger(),
    )

    /** Gửi [chunks] đoạn như `transcribe_chunks`: model đa năng đọc prompt, `*-transcribe` thì không. */
    private suspend fun sendChunks(d: ChunkDispatcher, chunks: Int = 3): List<String> {
        val method = cfg.currentDiarizationMethod()
        return (1..chunks).map {
            d.send(File("doan_00$it.flac"), 24.0, { model ->
                when (method) {
                    DiarizationMethod.GEMINI -> null to TranscribeOptions(diarize = true, wordTimestamps = true)
                    DiarizationMethod.HYBRID -> null to TranscribeOptions(wordTimestamps = true)
                    else -> (if (isTranscribeModel(model)) null else "PROMPT") to TranscribeOptions()
                }
            }).model
        }
    }

    private fun text(s: String) = resultText(s)

    @Test
    fun test_daily_quota_locks_model_and_switches_to_fallback() = runBlocking {
        val error = GoogleAIException("HTTP 429: Quota exceeded ... limit: 25", ErrorKind.QUOTA, 429).apply { isDaily = true }
        val c = client { i, o -> if (o.model == "gemini-3.5-transcribe" && i >= 2) error else text("${o.model} $i") }
        val used = sendChunks(dispatcher(c))
        val models = c.calls.map { it.options.model }
        assertEquals(listOf("gemini-3.5-transcribe", "gemini-3.5-transcribe", "gemini-3.5-flash"), models.take(3))
        assertTrue(models.drop(3).all { it == "gemini-3.5-flash" }) // đã khoá: không thử lại
        val status = tracker.lockStatus("gemini-3.5-transcribe")
        assertEquals(true to LockReason.DAILY_QUOTA, status.locked to status.reason)
        assertEquals(nextDailyReset(clock()), status.until)
        assertEquals(1, used.count { it == "gemini-3.5-transcribe" })
        // Model dự phòng đa năng nhận prompt, model *-transcribe thì không.
        assertNull(c.calls[0].prompt)
        assertNotNull(c.calls[2].prompt)
        assertEquals(cfg.retriesBeforeSwitch, c.calls[2].options.maxAttempts)
    }

    @Test
    fun test_filter_blocking_one_chunk_switches_model_for_that_chunk_only() = runBlocking {
        cfg = cfg.copy(model = "gemini-3.8-flash")
        val blocked = GoogleAIException("HTTP 400: Input blocked: This request was blocked by Gemini's filters.", ErrorKind.BLOCKED, 400)
        val c = client { i, o -> if (o.model == "gemini-3.8-flash" && i == 1) blocked else text(o.model!!) }
        val used = sendChunks(dispatcher(c))
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.8-flash"), c.calls.map { it.options.model }.take(3))
        assertFalse(tracker.isLocked("gemini-3.8-flash"))
        assertEquals(listOf("gemini-3.5-flash", "gemini-3.8-flash"), used.take(2))
    }

    @Test
    fun test_overload_locks_briefly_and_keeps_fallback() = runBlocking {
        cfg = cfg.copy(model = "gemini-3.8-flash", returnToMainModel = false)
        val overloaded = GoogleAIException("HTTP 500: gemini-3.8-flash is currently experiencing high demand", ErrorKind.OVERLOAD, 500)
        val c = client { _, o -> if (o.model == "gemini-3.8-flash") overloaded else text("ok") }
        sendChunks(dispatcher(c))
        val models = c.calls.map { it.options.model }
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash"), models.take(2))
        assertTrue(models.drop(1).all { it == "gemini-3.5-flash" })
        assertEquals(LockReason.OVERLOADED, tracker.lockStatus("gemini-3.8-flash").reason)
    }

    @Test
    fun test_overload_lock_off_still_switches_for_current_chunk() = runBlocking {
        cfg = cfg.copy(model = "gemini-3.8-flash", overloadLockMinutes = 0.0)
        val overloaded = GoogleAIException("HTTP 500: high demand", ErrorKind.OVERLOAD, 500)
        val c = client { i, o -> if (o.model == "gemini-3.8-flash" && i == 1) overloaded else text("ok") }
        sendChunks(dispatcher(c))
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.8-flash"), c.calls.map { it.options.model }.take(3))
        assertFalse(tracker.isLocked("gemini-3.8-flash"))
    }

    @Test
    fun test_filter_blocks_every_model_fails_file_but_not_queue() = runBlocking {
        cfg = cfg.copy(model = "gemini-3.8-flash", fallbackModels = listOf("gemini-3.5-flash"))
        val blocked = GoogleAIException("HTTP 400: Input blocked", ErrorKind.BLOCKED, 400)
        val c = client { _, _ -> blocked }
        val e = assertFailsWith<GoogleAIException> { sendChunks(dispatcher(c)) }
        assertEquals(ErrorKind.BLOCKED, e.kind)
        assertFalse(e.stopQueue)
        assertEquals(2, c.calls.size)
    }

    @Test
    fun test_all_models_locked_long_stops_queue() = runBlocking {
        cfg = cfg.copy(diarizationEnabled = true, diarizationMethod = DiarizationMethod.GEMINI) // chỉ có một model *-transcribe
        val c = client { _, _ -> text("x") }
        tracker.lock("gemini-3.5-transcribe", clock() + 5 * 3600, LockReason.DAILY_QUOTA, "429")
        val e = assertFailsWith<GoogleAIException> { sendChunks(dispatcher(c)) }
        assertTrue(e.stopQueue)
        assertTrue("gemini-3.5-transcribe" in e.message)
        assertEquals(emptyList(), c.calls)
    }

    @Test
    fun test_short_lock_waits_then_continues() = runBlocking {
        cfg = cfg.copy(diarizationEnabled = true, diarizationMethod = DiarizationMethod.GEMINI)
        val c = client { _, _ -> text("x") }
        tracker.lock("gemini-3.5-transcribe", clock() + 90, LockReason.OVERLOADED, "500")
        sendChunks(dispatcher(c) { tracker.unlock("gemini-3.5-transcribe") })
        assertTrue(sleeps.single() >= 90)
        assertTrue(c.calls.isNotEmpty())
        assertTrue(c.calls.all { it.options.diarize && it.options.wordTimestamps })
    }

    @Test
    fun test_reaching_daily_quota_locks_before_sending() = runBlocking {
        cfg = cfg.copy(modelQuotas = mapOf("gemini-3.5-transcribe" to Quota(0, 0, 1)))
        val c = client { _, o -> text(o.model!!) }
        tracker.recordRequest("gemini-3.5-transcribe") // client thật tự ghi; client giả thì ghi tay
        sendChunks(dispatcher(c))
        assertTrue(c.calls.all { it.options.model == "gemini-3.5-flash" })
        assertEquals(LockReason.REACHED_DAILY, tracker.lockStatus("gemini-3.5-transcribe").reason)
    }

    @Test
    fun test_model_method_mismatch_fails_immediately() {
        cfg = cfg.copy(diarizationEnabled = true, diarizationMethod = DiarizationMethod.PYANNOTE) // model là *-transcribe
        val c = client { _, _ -> text("x") }
        val e = assertFailsWith<ConstraintException> { dispatcher(c) }
        assertTrue(e.stopQueue)
        assertEquals(emptyList(), c.calls)
    }

    @Test
    fun `đổi model ghi sự kiện với mã lý do như desktop`() = runBlocking {
        val error = GoogleAIException("HTTP 429: limit: 25", ErrorKind.QUOTA, 429).apply { isDaily = true }
        val c = client { i, o -> if (o.model == "gemini-3.5-transcribe" && i == 2) error else text("ok") }
        sendChunks(dispatcher(c))
        val switches = tracker.snapshot().data.events.filter { it.kind == UsageEventKind.SWITCH_MODEL }
        assertEquals(listOf(Triple("gemini-3.5-transcribe", "gemini-3.5-flash", LockReason.DAILY_QUOTA)), switches.map {
            Triple(it.fromModel, it.model, it.content)
        })
    }
}

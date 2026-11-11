package io.github.cozycode890.guzz.core.gemini

import io.github.cozycode890.guzz.core.common.log.MemorySink
import io.github.cozycode890.guzz.core.common.log.SinkLogger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B5: [QuotaTracker]. Golden `gemini/tracker.json`: cùng một chuỗi thao tác (đồng hồ giả) chạy trên `QuotaTracker`
 * của desktop và bản Kotlin phải cho cùng kết quả đọc và cùng tệp `su_dung.json` (so như cây JSON; trường null coi như
 * không có vì bản Kotlin bỏ trường null). Thêm port `TestQuotaTracker` của tests/test_quota.py.
 */
class QuotaTrackerTest {
    private val quiet = SinkLogger().apply { addSink(MemorySink()) }

    @TestFactory
    fun `chuỗi thao tác cho cùng tệp như desktop`(@TempDir dir: File): List<DynamicTest> {
        val golden = goldenJson("tracker.json")
        val cfg = cfgFrom(golden.getValue("cfg").jsonObject)
        val steps = golden.getValue("steps").jsonArray.map { it.jsonObject }
        return golden.getValue("runs").jsonArray.mapIndexed { i, r ->
            val run = r.jsonObject
            dynamicTest("lần chạy $i, bắt đầu ${run.getValue("start")}") {
                val file = File(dir, "run$i/${QuotaTracker.FILENAME}")
                val (reads, finalFile) = replay(run.getValue("start").jsonPrimitive.double, steps, cfg, file)
                val expectedReads = run.getValue("reads").jsonArray
                assertEquals(expectedReads.size, reads.size)
                expectedReads.zip(reads).forEachIndexed { k, (e, a) -> assertJsonEquals(e, a, path = "read[$k]") }
                assertJsonEquals(run.getValue("file"), finalFile, nullIsAbsent = true)
            }
        }
    }

    private fun replay(start: Double, steps: List<JsonObject>, cfg: GeminiConfig, file: File): Pair<List<JsonElement>, JsonElement> {
        val clock = FakeClock(start)
        var tracker = QuotaTracker(file, clock, quiet)
        val reads = mutableListOf<JsonElement>()
        for (step in steps) {
            val model = step["model"].strOrNull
            when (step.getValue("op").str) {
                "advance" -> clock.now += step.getValue("sec").jsonPrimitive.double
                "advance_to_reset" -> clock.now = nextDailyReset(clock()) + step.getValue("plus").jsonPrimitive.double
                "record_request" -> tracker.recordRequest(model!!, step.getValue("tokens").jsonPrimitive.long)
                "record_error" -> tracker.recordError(model!!, step.getValue("kind").str, step.getValue("message").str)
                "errors" -> repeat(step.getValue("count").jsonPrimitive.int) {
                    tracker.recordError(model!!, step.getValue("kind").str, "lỗi $it")
                }
                "learn_quota" -> tracker.learnQuota(model!!, step.getValue("field").str, step.getValue("value").jsonPrimitive.int)
                "forget_quota" -> tracker.forgetQuota(model)
                "lock" -> {
                    val until = when {
                        "until_in" in step -> clock() + step.getValue("until_in").jsonPrimitive.double
                        step["until"].strOrNull == "reset" -> nextDailyReset(clock())
                        else -> null
                    }
                    tracker.lock(model!!, until, step.getValue("reason").str, step.getValue("details").str)
                }
                "unlock" -> tracker.unlock(model!!)
                "record_model_switch" ->
                    tracker.recordModelSwitch(step.getValue("from").str, step.getValue("to").str, step.getValue("reason").str)
                "set_waiting" -> tracker.setWaiting(
                    model, step.getValue("reason").str, clock() + step.getValue("in").jsonPrimitive.double,
                    step.getValue("details").str,
                )
                "clear_waiting" -> tracker.clearWaiting()
                "reset_counters" -> tracker.resetCounters(model)
                "reopen" -> tracker = QuotaTracker(file, clock, quiet)
                "read" -> reads += reads(tracker, clock, cfg)
                else -> error("bước lạ: $step")
            }
        }
        return reads to JsonSupport.json.parseToJsonElement(file.readText())
    }

    private fun reads(tracker: QuotaTracker, clock: FakeClock, cfg: GeminiConfig): JsonElement {
        val snapshot = tracker.snapshot()
        return buildJsonObject {
            put("clock", clock())
            putJsonObject("models") {
                for (m in READ_MODELS) {
                    putJsonObject(m) {
                        val s = tracker.lockStatus(m)
                        put(
                            "lock_status",
                            buildJsonArray {
                                add(JsonPrimitive(s.locked))
                                add(s.until?.let(::JsonPrimitive) ?: JsonNull)
                                add(JsonPrimitive(s.reason))
                                add(JsonPrimitive(s.details))
                            },
                        )
                        put("requests_today", tracker.requestsToday(m))
                        put("recent_minute_locks", tracker.recentMinuteLocks(m))
                        put("learned", JsonObject(tracker.learnedLimits(m).mapValues { JsonPrimitive(it.value) }))
                        val q = tracker.limit(cfg, m)
                        put("limit", buildJsonArray { add(JsonPrimitive(q.tpm)); add(JsonPrimitive(q.rpm)); add(JsonPrimitive(q.rpd)) })
                    }
                }
            }
            put("locked", buildJsonArray { tracker.revision().second.forEach { add(JsonPrimitive(it)) } })
            put(
                "waiting",
                snapshot.waiting?.let {
                    buildJsonObject {
                        put("model", it.model)
                        put("ly_do", it.reason)
                        put("den", it.until)
                        put("chi_tiet", it.details)
                        put("tu", it.since)
                    }
                } ?: JsonNull,
            )
            put("event_count", snapshot.data.events.size)
        }
    }

    // ---- Port của tests/test_quota.py: TestQuotaTracker

    private val clock = FakeClock(epoch(2026, 9, 17, 16))
    private val tracker = QuotaTracker(null, clock, quiet)

    @Test
    fun test_expired_lock_unlocks_itself() {
        tracker.lock("m", clock() + 600, LockReason.OVERLOADED, "HTTP 500: high demand")
        val status = tracker.lockStatus("m")
        assertEquals(Triple(true, clock() + 600, LockReason.OVERLOADED), Triple(status.locked, status.until, status.reason))
        val head = tracker.revision()
        clock.now += 601
        assertFalse(tracker.isLocked("m"))
        assertNotEquals(head, tracker.revision())
        assertEquals(listOf(UsageEventKind.LOCK, UsageEventKind.LOCK_EXPIRED), tracker.snapshot().data.events.map { it.kind })
    }

    @Test
    fun test_daily_lock_not_shortened_permanent_lock_needs_manual_unlock() {
        val reopenAt = nextDailyReset(clock())
        tracker.lock("m", reopenAt, LockReason.DAILY_QUOTA)
        tracker.lock("m", clock() + 600, LockReason.OVERLOADED)
        val status = tracker.lockStatus("m")
        assertEquals(reopenAt to LockReason.DAILY_QUOTA, status.until to status.reason)
        tracker.lock("x", null, LockReason.NOT_FOUND)
        clock.now += 1e7
        assertTrue(tracker.isLocked("x"))
        tracker.unlock("x")
        assertFalse(tracker.isLocked("x"))
    }

    @Test
    fun test_new_day_resets_counters_and_saves_file(@TempDir dir: File) {
        val p = File(dir, QuotaTracker.FILENAME)
        val t = QuotaTracker(p, clock, quiet)
        t.recordRequest("m", 15000)
        t.recordRequest("m", 15000)
        t.learnQuota("m", "rpd", 25)
        t.lock("m", nextDailyReset(clock()), LockReason.DAILY_QUOTA, "429")

        val reopened = QuotaTracker(p, clock, quiet) // như mở lại app
        assertEquals(2, reopened.requestsToday("m"))
        assertEquals(mapOf("rpd" to 25), reopened.learnedLimits("m"))
        assertTrue(reopened.isLocked("m"))

        clock.now = nextDailyReset(clock()) + 1
        assertEquals(0, reopened.requestsToday("m"))
        assertFalse(reopened.isLocked("m"))
        assertEquals(30000, reopened.snapshot().data.models.getValue("m").tokensTotal)
        assertFalse(File(dir, QuotaTracker.FILENAME + ".tmp").exists(), "tệp tạm phải đã đổi tên")
    }

    @Test
    fun test_declared_learned_and_default_limits() {
        val cfg = GeminiConfig(tokenRateLimit = 10000, modelQuotas = mapOf("a" to Quota(0, 5, 0)))
        tracker.learnQuota("a", "rpd", 25)
        tracker.learnQuota("b", "tpm", 250000)
        assertEquals(Quota(0, 5, 25), tracker.limit(cfg, "a"))
        assertEquals(Quota(250000, 0, 0), tracker.limit(cfg, "b"))
        assertEquals(Quota(10000, 0, 0), tracker.limit(cfg, "c"))
    }

    @Test
    fun test_waiting() {
        tracker.setWaiting("m", "tpm", clock() + 39, "10000")
        assertEquals("tpm", tracker.snapshot().waiting?.reason)
        tracker.clearWaiting()
        assertNull(tracker.snapshot().waiting)
    }

    @Test
    fun `tệp hỏng thì bắt đầu sổ trắng`(@TempDir dir: File) {
        val p = File(dir, QuotaTracker.FILENAME).apply { writeText("{hỏng") }
        val t = QuotaTracker(p, clock, quiet)
        assertEquals(emptyList(), t.models())
        t.recordRequest("m", 1)
        assertEquals(1, QuotaTracker(p, clock, quiet).requestsToday("m"))
    }

    companion object {
        private val READ_MODELS = listOf("m", "t", "a", "b", "x", "o", "q", "e", "khong_co")
    }
}

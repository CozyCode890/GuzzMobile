package io.github.cozycode890.guzz.core.gemini

import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phần tính toán của trang Hạn mức (Stage 8 D1). */
class QuotaViewTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")

    // 2026-11-17 10:00:00 giờ Việt Nam.
    private val now = 1_794_884_400.0

    private fun snapshot(waiting: Waiting? = null): UsageSnapshot {
        val data = UsageData(date = "2026-11-16")
        data.models["gemini-a"] = ModelUsage(
            requestsToday = 20, tokensToday = 12_345, lockedUntil = now + 3_725, lockReason = LockReason.DAILY_QUOTA,
            lastError = "429\nhết", lastErrorAt = now - 60, errorCounts = linkedMapOf("han_muc_ngay" to 2, "khac" to 0),
            learned = linkedMapOf("rpd" to 20),
        )
        data.models["gemini-b"] = ModelUsage(lockedPermanently = true, lockReason = LockReason.NOT_FOUND)
        data.models["gemini-c"] = ModelUsage(lockedUntil = now - 1, lockReason = LockReason.OVERLOADED)
        data.events += UsageEvent(now - 100, "gemini-a", UsageEventKind.LOCK, "x", reason = LockReason.DAILY_QUOTA)
        data.events += UsageEvent(now - 50, "gemini-b", UsageEventKind.SWITCH_MODEL, "uu_tien", fromModel = "gemini-a")
        return UsageSnapshot(data, waiting)
    }

    private fun build(s: UsageSnapshot) = QuotaView.build(
        s, listOf("gemini-a", "gemini-b", "gemini-c", "gemini-d"), listOf("gemini-a", "gemini-b"),
        { m -> if (m == "gemini-a") Quota(1000, 5, 20) else Quota(0, 0, 0) }, now, zone,
    )

    @Test
    fun `khoá có hạn, khoá tay, khoá đã hết hạn`() {
        val rows = build(snapshot()).models.associateBy { it.model }
        val a = rows.getValue("gemini-a")
        assertTrue(a.locked)
        assertEquals("11:02", a.lockedUntilText)
        assertEquals("1:02:05", a.remaining)
        assertEquals(LockReason.DAILY_QUOTA, a.lockReason)
        assertTrue(a.overDaily)
        assertEquals(listOf("han_muc_ngay" to 2), a.errors)
        assertEquals("429 hết", a.lastError)
        assertEquals("09:59", a.lastErrorAt)
        assertEquals(mapOf("rpd" to 20), a.learned)

        val b = rows.getValue("gemini-b")
        assertTrue(b.locked)
        assertNull(b.lockedUntilText)

        assertFalse(rows.getValue("gemini-c").locked)
        assertEquals(0, rows.getValue("gemini-d").requestsToday)
    }

    @Test
    fun `chờ, giờ đặt lại, lịch sử mới nhất trước`() {
        val v = build(snapshot(Waiting("gemini-a", "tpm", now + 65, "250000", now)))
        assertEquals(QuotaView.Wait("gemini-a", "tpm", "0:01:05", "10:01", "250000"), v.wait)
        // 0 giờ Thái Bình Dương ngày 18/11 = 15:00 giờ Việt Nam (giờ chuẩn PST).
        assertEquals("15:00", v.resetAtText)
        assertEquals("5:00:00", v.resetIn)
        assertEquals(listOf(UsageEventKind.SWITCH_MODEL, UsageEventKind.LOCK), v.events.map { it.kind })
        assertEquals("gemini-a", v.events[0].fromModel)
        assertNull(build(snapshot()).wait)
    }

    @Test
    fun `danh sách model bỏ trùng, có gợi ý và model trong sổ`() {
        val cfg = GeminiConfig(model = "gemini-a", fallbackModels = listOf("gemini-3.5-flash", "gemini-z"))
        val models = QuotaView.knownModels(cfg, snapshot())
        assertEquals(models.distinct(), models)
        assertTrue(models.containsAll(listOf("gemini-a", "gemini-z", "gemini-b", "gemini-c") + QuotaView.SUGGESTED_MODELS))
    }
}

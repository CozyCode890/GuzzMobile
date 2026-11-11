package io.github.cozycode890.guzz.core.gemini

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals

fun epoch(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Double =
    LocalDateTime.of(year, month, day, hour, minute).toEpochSecond(ZoneOffset.UTC).toDouble()

/** B3: giờ Thái Bình Dương. Golden `gemini/pacific.json` (quota.py tự tính DST) và port `TestPacificTime`. */
class PacificTimeTest {

    @TestFactory
    fun `pacific_date và next_daily_reset trùng desktop`(): List<DynamicTest> {
        val golden = goldenJson("pacific.json")
        return golden.getValue("points").jsonArray.map { p ->
            val o = p.jsonObject
            val t = o.getValue("epoch").jsonPrimitive.double
            dynamicTest("$t") {
                assertEquals(o.getValue("date").str, pacificDate(t), "pacific_date")
                assertEquals(o.getValue("next_reset").jsonPrimitive.double, nextDailyReset(t), "next_daily_reset")
            }
        } + golden.getValue("countdown").jsonArray.map { c ->
            val sec = c.jsonObject.getValue("sec").jsonPrimitive.double
            dynamicTest("format_countdown $sec") { assertEquals(c.jsonObject.getValue("expected").str, formatCountdown(sec)) }
        }
    }

    // ---- Port của tests/test_quota.py: TestPacificTime

    @Test
    fun test_summer() {
        // 16:00 UTC = 09:00 PDT = 23:00 giờ Việt Nam: vẫn là ngày 17 ở Mỹ.
        val t = epoch(2026, 9, 17, 16)
        assertEquals("2026-09-17", pacificDate(t))
        // Google tính lại lúc 0h PDT ngày 18 = 07:00 UTC = 14:00 giờ Việt Nam.
        assertEquals(epoch(2026, 9, 18, 7), nextDailyReset(t))
        assertEquals("2026-09-16", pacificDate(epoch(2026, 9, 17, 6, 30)))
    }

    @Test
    fun test_winter_and_both_dst_switches() {
        assertEquals(epoch(2026, 1, 16, 8), nextDailyReset(epoch(2026, 1, 15, 12)))
        // pacific_offset của desktop: kiểm qua ngày đổi giờ, sát hai phía của mốc đổi.
        assertEquals("2026-03-08", pacificDate(epoch(2026, 3, 8, 9, 59)))
        assertEquals("2026-11-01", pacificDate(epoch(2026, 11, 1, 8, 59)))
        assertEquals(epoch(2026, 3, 8, 8), nextDailyReset(epoch(2026, 3, 7, 20)))
        assertEquals(epoch(2026, 3, 9, 7), nextDailyReset(epoch(2026, 3, 8, 20)))
    }

    @Test
    fun test_countdown() {
        assertEquals("3:05:09", formatCountdown(3 * 3600 + 5 * 60 + 9.4))
        assertEquals("0:00:00", formatCountdown(-5.0))
    }

    @Test
    fun `format_local_time cùng ngày chỉ giờ, khác ngày có ngày tháng`() {
        val hcm = ZoneId.of("Asia/Ho_Chi_Minh")
        val now = epoch(2026, 9, 17, 16) // 23:00 giờ Việt Nam
        assertEquals("23:30", formatLocalTime(now + 1800, now, hcm))
        assertEquals("18/09 14:00", formatLocalTime(epoch(2026, 9, 18, 7), now, hcm))
    }
}

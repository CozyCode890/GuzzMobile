package io.github.cozycode890.guzz.core.gemini

import io.github.cozycode890.guzz.core.common.pyRoundToLong
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor

/**
 * Giờ Mỹ - Thái Bình Dương: Google tính lại hạn mức theo ngày lúc 0 giờ giờ này (14 giờ hoặc 15 giờ giờ Việt Nam).
 *
 * Desktop tự tính quy tắc giờ mùa hè vì Python embeddable trên Windows không có tzdata; ở đây dùng `java.time` với
 * múi giờ thật, golden test so với desktop qua các lần đổi giờ 2025–2030.
 */
object PacificTime {
    val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

    /** Epoch (giây, có phần lẻ) → [Instant], làm tròn tới micro giây như `datetime.fromtimestamp` của Python. */
    internal fun instant(epoch: Double): Instant {
        var seconds = floor(epoch)
        var micros = Math.rint((epoch - seconds) * 1e6)
        if (micros >= 1e6) {
            seconds += 1
            micros -= 1e6
        }
        return Instant.ofEpochSecond(seconds.toLong(), micros.toLong() * 1000)
    }
}

/** Ngày (`YYYY-MM-DD`) theo giờ Thái Bình Dương tại [epoch]. Port của quota.py: `pacific_date`. */
fun pacificDate(epoch: Double): String = PacificTime.instant(epoch).atZone(PacificTime.ZONE).toLocalDate().toString()

/** Epoch của 0 giờ (giờ Thái Bình Dương) ngày kế tiếp: lúc Google tính lại hạn mức theo ngày. Port của `next_daily_reset`. */
fun nextDailyReset(epoch: Double): Double {
    val date = PacificTime.instant(epoch).atZone(PacificTime.ZONE).toLocalDate()
    return date.plusDays(1).atStartOfDay(PacificTime.ZONE).toEpochSecond().toDouble()
}

/** `3:05:09`; số âm thành `0:00:00`. Port của quota.py: `format_countdown`. */
fun formatCountdown(sec: Double): String {
    val s = maxOf(0L, pyRoundToLong(sec))
    return "${s / 3600}:${(s % 3600 / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
}

private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_MONTH_HOUR_MINUTE = DateTimeFormatter.ofPattern("dd/MM HH:mm")

/**
 * `14:00` nếu cùng ngày với [now] (theo giờ máy, [zone]), không thì `18/09 14:00`. Port của quota.py:
 * `format_local_time`.
 */
fun formatLocalTime(epoch: Double, now: Double, zone: ZoneId = ZoneId.systemDefault()): String {
    val moment = PacificTime.instant(epoch).atZone(zone)
    val today = PacificTime.instant(now).atZone(zone).toLocalDate()
    return moment.format(if (moment.toLocalDate() == today) HOUR_MINUTE else DAY_MONTH_HOUR_MINUTE)
}

package io.github.cozycode890.guzz.core.gemini

import java.util.concurrent.ConcurrentHashMap

/** Đồng hồ đơn điệu tính bằng giây, như `time.monotonic` của Python. */
val monotonicSeconds: () -> Double = { System.nanoTime() / 1e9 }

/**
 * Giữ nhịp gửi để không vượt hạn mức token đầu vào và số yêu cầu MỖI PHÚT của một model. Port của google_ai.py:
 * `RateLimiter`.
 *
 * Cách làm: nhớ (lúc gửi, số token) của các lần gửi trong 60 giây qua. Số token lấy từ `usage` trong phản hồi, và suy
 * ra số token mỗi giây âm thanh để ước lượng đoạn sau. Chưa biết (đoạn đầu, hoặc phản hồi không có usage) thì coi như
 * lần gửi đó đã dùng hết hạn mức cả phút, tức hai lần gửi cách nhau ≥ 60 giây: chậm hơn một chút nhưng không bao giờ
 * dính 429.
 */
class RateLimiter(val clock: () -> Double = monotonicSeconds) {
    private val lock = Any()
    private var history = mutableListOf<Pair<Double, Long>>()

    @Volatile
    var tokensPerAudioSec: Double? = null
        private set

    private fun inWindow(now: Double): List<Pair<Double, Long>> {
        history = history.filterTo(mutableListOf()) { (t, _) -> now - t < WINDOW_SEC }
        return history
    }

    fun tokensUsed(): Long = synchronized(lock) { inWindow(clock()).sumOf { it.second } }

    /** Số yêu cầu đã gửi trong 60 giây qua (kể cả yêu cầu bị lỗi). */
    fun requestCount(): Int = synchronized(lock) { inWindow(clock()).size }

    fun estimate(audioSeconds: Double?, limit: Int): Long {
        val tps = tokensPerAudioSec
        if (tps != null && tps != 0.0 && audioSeconds != null && audioSeconds != 0.0) {
            // Thêm 10% phòng khi đoạn này nhiều tiếng nói hơn đoạn trước.
            return (tps * audioSeconds * 1.1).toLong()
        }
        return limit.toLong()
    }

    /** Chờ bao lâu để gửi thêm [expected] token mà không vượt [limit] token / phút. */
    fun waitTime(limit: Int, expected: Long): Double {
        if (limit <= 0) return 0.0
        synchronized(lock) {
            val now = clock()
            val window = inWindow(now)
            // Một đoạn to hơn cả hạn mức thì chỉ cần cửa sổ trống là gửi (Google vẫn nhận).
            val excess = window.sumOf { it.second } + minOf(expected, limit.toLong()) - limit
            if (window.isEmpty() || excess <= 0) return 0.0
            var freed = 0L
            for ((t, n) in window) {
                freed += n
                if (freed >= excess) return maxOf(0.0, t + WINDOW_SEC + MARGIN_SEC - now)
            }
            return 0.0
        }
    }

    /** Chờ bao lâu để gửi thêm một yêu cầu mà không vượt [limit] yêu cầu / phút. */
    fun requestWaitTime(limit: Int): Double {
        if (limit <= 0) return 0.0
        synchronized(lock) {
            val now = clock()
            val window = inWindow(now)
            val surplus = window.size - limit + 1
            if (surplus <= 0) return 0.0
            return maxOf(0.0, window[surplus - 1].first + WINDOW_SEC + MARGIN_SEC - now)
        }
    }

    fun record(sentAt: Double, tokenCount: Long, audioSeconds: Double? = null, actual: Boolean = true) {
        synchronized(lock) {
            history.add(sentAt to tokenCount)
            history.sortWith(compareBy<Pair<Double, Long>> { it.first }.thenBy { it.second })
            if (actual && tokenCount > 0 && audioSeconds != null && audioSeconds != 0.0) {
                tokensPerAudioSec = tokenCount / audioSeconds
            }
        }
    }

    companion object {
        const val WINDOW_SEC = 60.0
        const val MARGIN_SEC = 1.0
    }
}

/**
 * Mỗi model một [RateLimiter], dùng chung cho cả app. Port của google_ai.py: `model_rate_limiter`.
 *
 * Google tính hạn mức theo project và theo model, nên đổi sang model dự phòng thì không phải chờ hạn mức của model cũ.
 * App giữ một bản trong `AppContainer`; test truyền [single] để mọi model dùng một bộ đếm với đồng hồ giả.
 */
class RateLimiters(private val clock: () -> Double = monotonicSeconds, private val single: RateLimiter? = null) {
    private val byModel = ConcurrentHashMap<String, RateLimiter>()

    fun forModel(model: String): RateLimiter = single ?: byModel.getOrPut(model) { RateLimiter(clock) }

    companion object {
        /** Một [RateLimiter] cho mọi model (như tham số `limiter` của `Client` desktop, chỉ dùng trong test). */
        fun single(limiter: RateLimiter) = RateLimiters(limiter.clock, limiter)
    }
}

package io.github.cozycode890.guzz.core.gemini

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** B4: port `TestRateLimiter` của tests/test_google_ai.py (7 ca), đồng hồ giả. */
class RateLimiterTest {

    @Test
    fun test_estimates_from_previous_chunk_and_waits_for_quota() {
        val clock = FakeClock()
        val limiter = RateLimiter(clock)
        assertEquals(0.0, limiter.waitTime(10000, limiter.estimate(600.0, 10000)))
        limiter.record(clock(), 4000, audioSeconds = 600.0)
        clock.now += 20
        val expected = limiter.estimate(600.0, 10000)
        assertEquals(4400, expected)
        assertEquals(0.0, limiter.waitTime(10000, expected)) // 4000 + 4400 <= 10000
        limiter.record(clock(), 4000, audioSeconds = 600.0)
        clock.now += 20
        // 8000 + 4400 > 10000: chờ lần gửi đầu tiên ra khỏi cửa sổ 60 giây.
        assertEquals(21.0, limiter.waitTime(10000, 4400), 1e-9)
        clock.now += 21
        assertEquals(0.0, limiter.waitTime(10000, 4400))
    }

    @Test
    fun test_unknown_token_count_spaces_requests_one_minute() {
        val clock = FakeClock()
        val limiter = RateLimiter(clock)
        limiter.record(clock(), 10000, actual = false)
        assertNull(limiter.tokensPerAudioSec)
        clock.now += 15
        assertEquals(46.0, limiter.waitTime(10000, limiter.estimate(600.0, 10000)), 1e-9)
    }

    @Test
    fun test_chunk_larger_than_limit_only_needs_empty_window() {
        val clock = FakeClock()
        val limiter = RateLimiter(clock)
        assertEquals(0.0, limiter.waitTime(10000, 25000))
        limiter.record(clock(), 2000, audioSeconds = 60.0)
        clock.now += 30
        assertEquals(31.0, limiter.waitTime(10000, 25000), 1e-9)
    }

    @Test
    fun test_zero_limit_means_no_wait() {
        val limiter = RateLimiter(FakeClock())
        limiter.record(0.0, 1_000_000_000)
        assertEquals(0.0, limiter.waitTime(0, 1_000_000_000))
    }

    /** Tái hiện lần chạy thật 2026-09-17: đoạn 3 phải tự chờ, không được gửi ngay. */
    @Test
    fun test_three_consecutive_chunks_avoid_429() = runBlocking {
        val clock = FakeClock()
        val limiter = RateLimiter(clock)
        FakeGemini { _, _ -> Reply.Ok(resultDone(tokens = 4000)) }.use { fake ->
            val h = ClientHarness(fake, clock, limiter)
            val cfg = makeConfig().copy(tokenRateLimit = 10000)
            val audio = fakeAudio()
            repeat(3) {
                h.client.transcribe(audio, cfg, audioSeconds = 600.0)
                assertEquals(4000, h.client.lastTokenCount)
                clock.now += 15 // mỗi đoạn Google xử lý mất ~15 giây
            }
            // Đoạn 1, 2 gửi ngay (tổng 8000); đoạn 3 (ước 4400) chờ đoạn 1 ra khỏi cửa sổ.
            assertEquals(listOf(31L), h.waits.map { Math.round(it) })
        }
    }

    @Test
    fun test_requests_per_minute_limit() {
        val clock = FakeClock()
        val limiter = RateLimiter(clock)
        assertEquals(0.0, limiter.requestWaitTime(2))
        limiter.record(clock(), 0)
        clock.now += 10
        limiter.record(clock(), 0)
        clock.now += 10
        assertEquals(41.0, limiter.requestWaitTime(2), 1e-9) // lần đầu (t=0) ra khỏi cửa sổ lúc 61
        assertEquals(0.0, limiter.requestWaitTime(3))
        assertEquals(2, limiter.requestCount())
    }

    @Test
    fun test_429_marks_whole_minute_as_full() = runBlocking {
        val clock = FakeClock()
        val limiter = RateLimiter(clock)
        FakeGemini { _, _ -> Reply.error(429, "quota") }.use { fake ->
            val h = ClientHarness(fake, clock, limiter)
            assertFailsWith<GoogleAIException> {
                h.client.transcribe(fakeAudio(), makeConfig().copy(tokenRateLimit = 10000, chunkRetries = 1))
            }
            assertEquals(10000, limiter.tokensUsed())
        }
    }
}

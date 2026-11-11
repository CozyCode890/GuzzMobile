package io.github.cozycode890.guzz.core.gemini

import io.github.cozycode890.guzz.core.common.log.LogLevel
import io.github.cozycode890.guzz.core.common.log.MemorySink
import io.github.cozycode890.guzz.core.common.log.SinkLogger
import java.io.File
import java.nio.file.Files

/** Như `make_config` của tests/test_google_ai.py: tắt giãn nhịp ở các test không nói về nó. */
fun makeConfig(): GeminiConfig = GeminiConfig(model = "gemini-3.5-transcribe", tokenRateLimit = 0, modelQuotas = emptyMap())

/** Tệp âm thanh giả `doan_001.flac` (`fLaC` + 100 byte 0 = 104 byte), như setUp của test desktop. */
fun fakeAudio(size: Int = 100): File {
    val dir = Files.createTempDirectory("guzz-gemini").toFile().apply { deleteOnExit() }
    return File(dir, "doan_001.flac").apply {
        writeBytes("fLaC".toByteArray() + ByteArray(size))
        deleteOnExit()
    }
}

/**
 * [GeminiClient] nói chuyện với [fake], đồng hồ giả: hàm chờ không ngủ thật mà ghi lại số giây vào [waits] rồi đẩy
 * đồng hồ (như `mock.patch.object(ga, "sleep")` của desktop). Log mức DEBUG ghi vào [log] để test C4 quét.
 */
class ClientHarness(
    fake: FakeGemini,
    val clock: FakeClock = FakeClock(),
    limiter: RateLimiter? = null,
    tracker: QuotaTracker? = null,
    val apiKey: String = "K",
) {
    val log = MemorySink()
    val logger = SinkLogger().apply {
        level = LogLevel.DEBUG
        addSink(log)
    }
    val waits = mutableListOf<Double>()
    val tracker: QuotaTracker = tracker ?: QuotaTracker(null, clock, logger)
    val client = GeminiClient(
        apiKey = apiKey,
        tracker = this.tracker,
        limiters = limiter?.let(RateLimiters::single) ?: RateLimiters(clock),
        apiRoot = fake.root,
        clock = clock,
        sleep = { sec ->
            waits += sec
            clock.now += sec
        },
        logger = logger,
    )
}

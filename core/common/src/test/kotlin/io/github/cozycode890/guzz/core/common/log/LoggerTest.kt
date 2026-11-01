package io.github.cozycode890.guzz.core.common.log

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggerTest {
    private val zone: ZoneId = ZoneOffset.ofHours(7)
    private val clock = Clock.fixed(LocalDateTime.of(2026, 10, 31, 14, 5, 0).atZone(zone).toInstant(), zone)
    private val sink = MemorySink(LogFormatter(zone))
    private val logger = SinkLogger(clock).apply { addSink(sink) }

    @Test
    fun `dòng log đúng mẫu của desktop`() {
        logger.info("nội dung")
        logger.warning("cảnh báo")
        logger.error("hỏng")
        logger.level = LogLevel.DEBUG
        logger.debug("chi tiết")
        // "%(asctime)s | %(levelname)-7s | %(message)s": tên mức căn trái, rộng 7 ký tự.
        assertEquals(
            listOf(
                "2026-10-31 14:05:00 | INFO    | nội dung",
                "2026-10-31 14:05:00 | WARNING | cảnh báo",
                "2026-10-31 14:05:00 | ERROR   | hỏng",
                "2026-10-31 14:05:00 | DEBUG   | chi tiết",
            ),
            sink.lines,
        )
    }

    @Test
    fun `bản ghi dưới mức đang đặt bị bỏ`() {
        logger.debug("không thấy")
        logger.info("thấy")
        logger.level = LogLevel.WARNING
        logger.info("không thấy nữa")
        logger.warning("vẫn thấy")
        assertEquals(listOf("thấy", "vẫn thấy"), sink.records.map { it.message })
    }

    @Test
    fun `lỗi kèm stack trace ở các dòng sau`() {
        logger.error("tải lên thất bại", IllegalStateException("mạng rớt"))
        val text = sink.lines.single()
        val lines = text.lines()
        assertEquals("2026-10-31 14:05:00 | ERROR   | tải lên thất bại", lines[0])
        assertEquals("java.lang.IllegalStateException: mạng rớt", lines[1])
        assertTrue(lines[2].trimStart().startsWith("at "), lines[2])
        assertTrue(lines.any { "LoggerTest" in it }, text)
    }

    @Test
    fun `sink lỗi không làm hỏng sink khác`() {
        val broken = LogSink { error("sink hỏng") }
        val after = MemorySink()
        logger.addSink(broken)
        logger.addSink(after)
        logger.info("vẫn tới")
        assertEquals(listOf("vẫn tới"), after.records.map { it.message })
        assertEquals(listOf("vẫn tới"), sink.records.map { it.message })
    }

    @Test
    fun `parse mức log như config_py`() {
        assertEquals(LogLevel.DEBUG, LogLevel.parse("debug"))
        assertEquals(LogLevel.INFO, LogLevel.parse("INFO"))
        assertEquals(LogLevel.INFO, LogLevel.parse("lạ"))
    }
}

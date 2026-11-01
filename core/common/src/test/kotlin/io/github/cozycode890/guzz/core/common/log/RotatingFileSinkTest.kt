package io.github.cozycode890.guzz.core.common.log

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RotatingFileSinkTest {

    @TempDir
    lateinit var dir: File

    private val formatter = LogFormatter(ZoneOffset.UTC)
    private val time = Instant.parse("2026-10-31T07:05:00Z")

    private val log get() = File(dir, "guzz.log")

    private fun record(message: String) = LogRecord(time, LogLevel.INFO, message)

    /** Số byte của một dòng trong tệp, kể cả `\n`. */
    private fun lineBytes(message: String) = (formatter.format(record(message)) + "\n").toByteArray().size

    private fun files() = dir.listFiles()!!.map { it.name }.sorted()

    private fun messages(file: File) = file.readLines().map { it.substringAfterLast(" | ") }

    @Test
    fun `xoay đúng số tệp, tệp 1 mới nhất`() {
        // Mỗi dòng cùng độ dài; vừa 2 dòng một tệp: dòng thứ ba làm cỡ + dòng >= maxBytes nên xoay.
        val one = lineBytes("dòng 0")
        val sink = RotatingFileSink(log, maxBytes = 2L * one + 1, backupCount = 2, formatter = formatter)
        (0..6).forEach { sink.write(record("dòng $it")) }
        sink.close()

        assertEquals(listOf("guzz.log", "guzz.log.1", "guzz.log.2"), files())
        assertEquals(listOf("dòng 6"), messages(log))
        assertEquals(listOf("dòng 4", "dòng 5"), messages(File(dir, "guzz.log.1")))
        assertEquals(listOf("dòng 2", "dòng 3"), messages(File(dir, "guzz.log.2")))
    }

    @Test
    fun `xoay khi cỡ cộng bản ghi bằng đúng maxBytes, như Python`() {
        val one = lineBytes("a")
        val sink = RotatingFileSink(log, maxBytes = 2L * one, backupCount = 1, formatter = formatter)
        sink.write(record("a"))
        sink.write(record("b")) // one + one >= 2 * one: xoay
        sink.close()
        assertEquals(listOf("b"), messages(log))
        assertEquals(listOf("a"), messages(File(dir, "guzz.log.1")))
    }

    @Test
    fun `tệp rỗng không xoay dù bản ghi dài hơn maxBytes, như Python`() {
        val sink = RotatingFileSink(log, maxBytes = 10, backupCount = 1, formatter = formatter)
        sink.write(record("x".repeat(40)))
        sink.close()
        assertEquals(listOf("guzz.log"), files())
    }

    @Test
    fun `tiếng Việt có dấu đọc lại đúng, so theo byte`() {
        val text = "Chuyển âm thanh thành văn bản: ễ ữ ỷ đ"
        val sink = RotatingFileSink(log, maxBytes = 1024, backupCount = 1, formatter = formatter)
        sink.write(record(text))
        sink.close()
        assertEquals(listOf(text), messages(log))
        assertEquals(lineBytes(text).toLong(), log.length())
    }

    @Test
    fun `old_log_count 0 thì chỉ còn một tệp và không bao giờ vượt maxBytes`() {
        val maxBytes = 300L
        val sink = RotatingFileSink(log, maxBytes = maxBytes, backupCount = 0, formatter = formatter)
        repeat(200) {
            sink.write(record("dòng số $it có dấu ữ"))
            assertTrue(log.length() < maxBytes, "lần $it: ${log.length()} byte")
        }
        sink.close()
        assertEquals(listOf("guzz.log"), files())
        assertEquals("dòng số 199 có dấu ữ", messages(log).last())
    }

    @Test
    fun `mở lại tệp đã có thì tính tiếp cỡ cũ`() {
        val one = lineBytes("a")
        RotatingFileSink(log, maxBytes = 2L * one + 1, backupCount = 1, formatter = formatter).use {
            it.write(record("a"))
            it.write(record("b"))
        }
        RotatingFileSink(log, maxBytes = 2L * one + 1, backupCount = 1, formatter = formatter).use {
            it.write(record("c"))
        }
        assertEquals(listOf("c"), messages(log))
        assertEquals(listOf("a", "b"), messages(File(dir, "guzz.log.1")))
    }

    @Test
    fun `configure đổi dung lượng từ bản ghi tiếp theo`() {
        val sink = RotatingFileSink(log, maxBytes = 1_000_000, backupCount = 3, formatter = formatter)
        repeat(5) { sink.write(record("dòng $it")) }
        sink.configure(maxBytes = 10, backupCount = 3)
        sink.write(record("sau khi đổi"))
        sink.close()
        assertEquals(listOf("sau khi đổi"), messages(log))
        assertEquals(5, messages(File(dir, "guzz.log.1")).size)
    }

    @Test
    fun `nhiều luồng ghi song song không mất và không lẫn dòng`() {
        val threads = 8
        val perThread = 500
        val sink = RotatingFileSink(log, maxBytes = 64L * 1024, backupCount = 50, formatter = formatter)
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> sink.write(record("luồng $t bản ghi $i")) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        sink.close()

        val all = dir.listFiles()!!.flatMap { it.readLines() }
        val prefix = formatter.format(record("")) // "<ngày giờ> | INFO    | "
        assertTrue(all.all { it.startsWith(prefix) && it.indexOf(prefix, 1) < 0 }, "có dòng bị lẫn")
        val got = all.map { it.substringAfterLast(" | ") }.toSet()
        val expected = (0 until threads).flatMap { t -> (0 until perThread).map { "luồng $t bản ghi $it" } }.toSet()
        assertEquals(threads * perThread, all.size)
        assertEquals(expected, got)
    }
}

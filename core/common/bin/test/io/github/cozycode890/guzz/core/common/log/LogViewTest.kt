package io.github.cozycode890.guzz.core.common.log

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/** Lọc nhật ký cho trang Nhật ký (Stage 8 D3). */
class LogViewTest {

    @TempDir
    lateinit var dir: File

    private val text = """
        2026-11-17 09:00:00 | INFO    | Bắt đầu
        2026-11-17 09:00:01 | DEBUG   | chi tiết | có thêm dấu |
        2026-11-17 09:00:02 | WARNING | Mạng chậm
        2026-11-17 09:00:03 | ERROR   | Hỏng rồi
        java.lang.IllegalStateException: x
            at a.b.C(C.kt:1)
        2026-11-17 09:00:04 | INFO    | Xong
    """.trimIndent()

    @Test
    fun `mức của dòng như _line_level`() {
        assertEquals(LogLevel.WARNING, LogView.lineLevel("t | WARNING | x"))
        assertEquals(LogLevel.ERROR, LogView.lineLevel("t | CRITICAL | x"))
        assertEquals(LogLevel.INFO, LogView.lineLevel("t | DEBUG   | x"))
        assertEquals(LogLevel.INFO, LogView.lineLevel("không có cột"))
    }

    @Test
    fun `stack trace đi theo bản ghi trước, lọc theo mức và chữ`() {
        val entries = LogView.entries(text)
        assertEquals(5, entries.size)
        assertEquals(3, entries[3].text.lines().size)
        assertEquals(listOf("Mạng chậm", "Hỏng rồi"), LogView.filter(entries, LogLevel.WARNING, "").map { it.text.lines()[0].substringAfterLast("| ") })
        assertEquals(1, LogView.filter(entries, LogLevel.ERROR, "").size)
        assertEquals(1, LogView.filter(entries, LogLevel.DEBUG, "ILLEGALSTATE").size)
        assertEquals(5, LogView.filter(entries, LogLevel.DEBUG, " ").size)
    }

    @Test
    fun `đọc đuôi tệp bỏ dòng dở`() {
        val f = File(dir, "guzz.log").apply { writeText("dòng một\ndòng hai\ndòng ba\n") }
        assertEquals("dòng ba\n", LogView.readTail(f, 9))
        assertEquals("dòng một\ndòng hai\ndòng ba\n", LogView.readTail(f, 1000))
        assertEquals("", LogView.readTail(File(dir, "khong_co.log"), 10))
    }
}

package io.github.cozycode890.guzz.feature.automation

import io.github.cozycode890.guzz.core.pipeline.ItemOverrides
import io.github.cozycode890.guzz.feature.automation.GuzzIntents as G
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 6 B1: đọc extra của lệnh, nhận cả dạng chuỗi như MacroDroid gửi. */
class ExtrasReaderTest {
    private fun transcribe(vararg extras: Pair<String, Any?>, data: List<String> = emptyList()) =
        ExtrasReader.read(G.ACTION_TRANSCRIBE, mapOf(*extras), data) as Command.Transcribe

    private fun bad(vararg extras: Pair<String, Any?>): String =
        assertThrows(BadExtraException::class.java) { transcribe(*extras) }.message!!

    @Test
    fun `tên API đều bắt đầu bằng gốc cố định và không trùng`() {
        val names = G.actions + G.events + listOf(G.RECEIVER_CLASS, G.ACTIVITY_CLASS)
        names.forEach { assertTrue(it, it.startsWith("io.github.cozycode890.guzz.")) }
        assertEquals(names.size, names.toSet().size)
        assertEquals(G.commandExtras.size, G.commandExtras.toSet().size)
        assertEquals(G.eventExtras.size, G.eventExtras.toSet().size)
        assertEquals("TRANSCRIBE", G.shortName(G.ACTION_TRANSCRIBE))
    }

    @Test
    fun `path, paths nhiều dòng, uri và data gộp lại, bỏ trùng và dòng trống`() {
        val c = transcribe(
            G.EXTRA_PATH to " /sdcard/a.m4a ",
            G.EXTRA_PATHS to "/sdcard/a.m4a\n\n /sdcard/b.m4a \n",
            G.EXTRA_URI to "content://x/1",
            data = listOf("content://x/1", "content://x/2"),
        )
        assertEquals(listOf("/sdcard/a.m4a", "/sdcard/b.m4a"), c.paths)
        assertEquals(listOf("content://x/1", "content://x/2"), c.uris)
        assertTrue(c.start)
        assertNull(c.overrides)
        assertNull(c.requestId)
    }

    @Test
    fun `paths là mảng hoặc danh sách, file URI thành đường dẫn`() {
        assertEquals(listOf("/a", "/b"), transcribe(G.EXTRA_PATHS to arrayOf("/a", "", "/b")).paths)
        assertEquals(listOf("/a"), transcribe(G.EXTRA_PATHS to arrayListOf("/a")).paths)
        assertEquals(listOf("/sdcard/bài 1.m4a"), transcribe(G.EXTRA_PATH to "file:///sdcard/b%C3%A0i%201.m4a").paths)
    }

    @Test
    fun `thiếu tệp, đường dẫn tương đối, kiểu lạ đều báo lỗi có tên extra`() {
        assertTrue(bad().contains("thiếu tệp"))
        assertTrue(bad(G.EXTRA_PATH to "Recordings/a.m4a").contains("Recordings/a.m4a"))
        assertTrue(bad(G.EXTRA_PATH to listOf("/a")).contains("`path`"))
        assertTrue(bad(G.EXTRA_PATHS to 5).contains("`paths`"))
        assertThrows(BadExtraException::class.java) { ExtrasReader.read("x.y.LAM_GI", emptyMap()) }
        assertThrows(BadExtraException::class.java) { ExtrasReader.read(null, emptyMap()) }
    }

    @Test
    fun `đường dẫn có dấu hai chấm chấm bị từ chối, cả dạng file URI`() {
        assertTrue(bad(G.EXTRA_PATH to "/sdcard/../../data/data/x/files/a.m4a").contains(".."))
        assertTrue(bad(G.EXTRA_PATHS to "/sdcard/a.m4a\n/sdcard/Music/../b.m4a").contains(".."))
        assertTrue(bad(G.EXTRA_PATH to "file:///sdcard/%2E%2E/b.m4a").contains(".."))
        assertTrue(bad(G.EXTRA_PATH to "/sdcard/a.m4a", G.EXTRA_OUTPUT_DIR to "/sdcard/x/../../data").contains(".."))
        // Tên tệp chứa ".." nhưng không phải cả một đoạn thì vẫn nhận.
        assertEquals(listOf("/sdcard/bai..1.m4a"), transcribe(G.EXTRA_PATH to "/sdcard/bai..1.m4a").paths)
    }

    @Test
    fun `ghi đè nhận chuỗi, số, boolean, device là pyannote`() {
        val c = transcribe(
            G.EXTRA_PATH to "/a", G.EXTRA_REQUEST_ID to 42, G.EXTRA_START to "false",
            G.EXTRA_SPEAKERS to "Device", G.EXTRA_NUM_SPEAKERS to "3", G.EXTRA_PRESET to "SPEAKERS_AROUND",
            G.EXTRA_DENOISE to "off", G.EXTRA_OUTPUT_DIR to "source",
        )
        assertEquals("42", c.requestId)
        assertFalse(c.start)
        assertEquals(
            ItemOverrides(speakers = "pyannote", numSpeakers = 3, preset = "speakers_around", denoise = false, outputDir = "source"),
            c.overrides,
        )
        val typed = transcribe(G.EXTRA_PATH to "/a", G.EXTRA_NUM_SPEAKERS to 2L, G.EXTRA_DENOISE to true, G.EXTRA_SPEAKERS to "off")
        assertEquals(ItemOverrides(speakers = "off", numSpeakers = 2, denoise = true), typed.overrides)
        assertEquals(4, ExtrasReader.int(mapOf("n" to 4.0), "n"))
        assertNull(ExtrasReader.bool(mapOf("b" to " "), "b"))
    }

    @Test
    fun `giá trị ghi đè lạ thì báo lỗi`() {
        assertTrue(bad(G.EXTRA_PATH to "/a", G.EXTRA_SPEAKERS to "abc").contains("`speakers`"))
        assertTrue(bad(G.EXTRA_PATH to "/a", G.EXTRA_NUM_SPEAKERS to "-1").contains("`num_speakers`"))
        assertTrue(bad(G.EXTRA_PATH to "/a", G.EXTRA_NUM_SPEAKERS to "hai").contains("`num_speakers`"))
        assertTrue(bad(G.EXTRA_PATH to "/a", G.EXTRA_NUM_SPEAKERS to 2.5).contains("`num_speakers`"))
        assertTrue(bad(G.EXTRA_PATH to "/a", G.EXTRA_PRESET to "abc").contains("`preset`"))
        assertTrue(bad(G.EXTRA_PATH to "/a", G.EXTRA_START to "maybe").contains("`start`"))
        assertTrue(bad(G.EXTRA_PATH to "/a", G.EXTRA_OUTPUT_DIR to "Documents").contains("`output_dir`"))
    }

    @Test
    fun `lệnh không cần tệp`() {
        assertEquals(Command.Start("r"), ExtrasReader.read(G.ACTION_START, mapOf(G.EXTRA_REQUEST_ID to " r ")))
        assertEquals(Command.Stop(null), ExtrasReader.read(G.ACTION_STOP, emptyMap()))
        assertEquals(Command.Status(null), ExtrasReader.read(G.ACTION_STATUS, mapOf(G.EXTRA_REQUEST_ID to "")))
    }
}

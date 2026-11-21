package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.pipeline.OutputChoice.Directory
import io.github.cozycode890.guzz.core.pipeline.OutputChoice.Saf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Stage 6 A5 (D96): chọn chỗ lưu theo `output_dir`, "Lưu cạnh tệp nguồn" và quyền "Truy cập mọi tệp". */
class OutputChoiceTest {
    private val path = "/storage/emulated/0/Recordings/bai giang.m4a"
    private val uri = "content://com.android.providers.media.documents/document/audio%3A12"

    private fun target(source: String, dir: String?, beside: Boolean, all: Boolean) =
        OutputChoice.choose(source, dir, beside, all).target

    @Test
    fun `không yêu cầu gì thì luôn vào thư mục SAF, không ghi chú`() {
        for (source in listOf(path, uri)) for (all in listOf(true, false)) {
            val c = OutputChoice.choose(source, null, besideSource = false, allFilesAccess = all)
            assertEquals(Saf, c.target)
            assertNull(c.note)
        }
        assertEquals(Saf, target(path, "  ", beside = false, all = true))
    }

    @Test
    fun `cạnh tệp nguồn cần quyền và nguồn là đường dẫn`() {
        assertEquals(Directory("/storage/emulated/0/Recordings"), target(path, null, beside = true, all = true))
        assertEquals(Directory("/storage/emulated/0/Recordings"), target(path, "source", beside = false, all = true))
        val noAccess = OutputChoice.choose(path, null, besideSource = true, allFilesAccess = false)
        assertEquals(Saf, noAccess.target)
        assertNotNull(noAccess.note)
        val content = OutputChoice.choose(uri, "source", besideSource = false, allFilesAccess = true)
        assertEquals(Saf, content.target)
        assertNotNull(content.note)
    }

    @Test
    fun `output_dir đường dẫn tuyệt đối thắng cài đặt cạnh tệp nguồn, đường dẫn tương đối thì lùi về SAF`() {
        assertEquals(Directory("/sdcard/Guzz"), target(path, "/sdcard/Guzz/", beside = true, all = true))
        assertEquals(Directory("/sdcard/Guzz"), target(uri, "/sdcard/Guzz", beside = false, all = true))
        assertEquals(Saf, target(path, "/sdcard/Guzz", beside = false, all = false))
        val relative = OutputChoice.choose(path, "Guzz", besideSource = false, allFilesAccess = true)
        assertEquals(Saf, relative.target)
        assertNotNull(relative.note)
    }
}

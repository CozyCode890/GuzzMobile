package io.github.cozycode890.guzz.feature.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stage 6 B3 (D104): đường dẫn → mã tài liệu trong Thư mục nguồn SAF. */
class PathMappingTest {
    @Test
    fun `tệp trong thư mục nguồn và thư mục con, tên có dấu và khoảng trắng`() {
        assertEquals(
            "primary:Recordings/bài giảng 1.m4a",
            PathMapping.documentIdFor("/storage/emulated/0/Recordings/bài giảng 1.m4a", "primary:Recordings"),
        )
        assertEquals(
            "primary:Recordings/Lop A/b.m4a",
            PathMapping.documentIdFor("/sdcard/Recordings/Lop A/b.m4a", "primary:Recordings"),
        )
        // Cây là gốc bộ nhớ trong.
        assertEquals("primary:Music/c.mp3", PathMapping.documentIdFor("/storage/self/primary/Music/c.mp3", "primary:"))
        // Đường dẫn có // và /./ vẫn nhận ra.
        assertEquals("primary:Recordings/d.m4a", PathMapping.documentIdFor("/sdcard//Recordings/./d.m4a", "primary:Recordings"))
    }

    @Test
    fun `ngoài thư mục, tiền tố trùng một phần, chính thư mục, có dấu chấm chấm thì không nhận`() {
        assertNull(PathMapping.documentIdFor("/sdcard/Download/a.m4a", "primary:Recordings"))
        assertNull(PathMapping.documentIdFor("/sdcard/Recordings2/a.m4a", "primary:Recordings"))
        assertNull(PathMapping.documentIdFor("/sdcard/Recordings", "primary:Recordings"))
        assertNull(PathMapping.documentIdFor("/sdcard/Recordings/../Download/a.m4a", "primary:Recordings"))
        assertNull(PathMapping.documentIdFor("/data/data/x/a.m4a", "primary:"))
        assertNull(PathMapping.documentIdFor("Recordings/a.m4a", "primary:Recordings"))
    }

    @Test
    fun `thẻ nhớ ngoài dùng mã ổ, không lẫn với bộ nhớ trong`() {
        assertEquals("1A2B-3C4D:Rec/a.m4a", PathMapping.documentIdFor("/storage/1A2B-3C4D/Rec/a.m4a", "1A2B-3C4D:Rec"))
        assertNull(PathMapping.documentIdFor("/storage/1A2B-3C4D/Rec/a.m4a", "primary:Rec"))
        assertNull(PathMapping.documentIdFor("/sdcard/Rec/a.m4a", "1A2B-3C4D:Rec"))
    }

    @Test
    fun `hiện thư mục nguồn bằng đường dẫn`() {
        assertEquals("/storage/emulated/0/Recordings", PathMapping.pathOf("primary:Recordings"))
        assertEquals("/storage/emulated/0", PathMapping.pathOf("primary:"))
        assertEquals("/storage/1A2B-3C4D/Rec", PathMapping.pathOf("1A2B-3C4D:Rec"))
    }
}

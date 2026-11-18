package io.github.cozycode890.guzz.feature.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D106: tệp chia sẻ với kiểu chung (Zalo) được nhận theo đuôi, tệp không phải âm thanh / video thì bỏ. */
class ShareFilterTest {
    @Test
    fun `kieu MIME am thanh hoac video thi nhan, khong can duoi`() {
        assertTrue(ShareFilter.isMedia("audio/mp4", null))
        assertTrue(ShareFilter.isMedia("Video/MP4; codecs=avc1", "khong_duoi"))
    }

    @Test
    fun `octet-stream thi xem duoi tep`() {
        assertTrue(ShareFilter.isMedia("application/octet-stream", "Trường Đại Học TP.HCM 2.m4a"))
        assertTrue(ShareFilter.isMedia("application/octet-stream", "ghi_am.AMR"))
        assertTrue(ShareFilter.isMedia(null, "bai.giang.mkv"))
        assertFalse(ShareFilter.isMedia("application/octet-stream", "tai_lieu.pdf"))
        assertFalse(ShareFilter.isMedia("image/jpeg", "anh.jpg"))
    }

    @Test
    fun `khong co duoi hoac ten thi bo`() {
        assertFalse(ShareFilter.isMedia("application/octet-stream", null))
        assertFalse(ShareFilter.isMedia("application/octet-stream", "m4a"))
        assertFalse(ShareFilter.isMedia("application/octet-stream", ".m4a"))
        assertFalse(ShareFilter.isMedia(null, "bai."))
    }
}

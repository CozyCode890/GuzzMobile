package io.github.cozycode890.guzz.core.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Phần thuần (không gọi Android) của `:core:media`, chạy trên JVM. */
class MediaPureTest {

    private val tracks = listOf(
        AudioTrackInfo(0, 1, "audio/mp4a-latm"),
        AudioTrackInfo(1, 2, "audio/mp4a-latm"),
    )

    // Port của tests/test_video.py: TestSelectTrack.
    @Test
    fun `auto và rỗng chọn luồng đầu`() {
        assertEquals(0, AudioTracks.select(tracks, "auto")!!.index)
        assertEquals(0, AudioTracks.select(tracks, " AUTO ")!!.index)
        assertEquals(0, AudioTracks.select(tracks, "")!!.index)
        assertEquals(0, AudioTracks.select(tracks, null)!!.index)
    }

    @Test
    fun `số hợp lệ chọn đúng luồng`() {
        assertEquals(1, AudioTracks.select(tracks, "1")!!.index)
        assertEquals(2, AudioTracks.select(tracks, " 1 ")!!.trackIndex)
    }

    @Test
    fun `số ngoài khoảng hoặc chữ linh tinh lùi về luồng đầu`() {
        assertEquals(0, AudioTracks.select(tracks, "5")!!.index)
        assertEquals(0, AudioTracks.select(tracks, "-1")!!.index)
        assertEquals(0, AudioTracks.select(tracks, "abc")!!.index)
    }

    @Test
    fun `không có luồng nào thì null`() {
        assertNull(AudioTracks.select(emptyList(), "auto"))
    }

    @Test
    fun `ước lượng dung lượng tạm và báo thiếu chỗ`() {
        // 1 giờ: 3600 × 84 000 × 1,1 = 332,64 MB.
        assertEquals(332_640_000L, StorageCheck.estimateTempBytes(3_600_000_000L))
        assertEquals(StorageCheck.estimateTempBytes(1_000_000L), StorageCheck.estimateTempBytes(1L))
        StorageCheck.ensureSpace(File("."), 3_600_000_000L, freeBytes = 400_000_000L)
        val e = assertThrows(InsufficientStorageException::class.java) {
            StorageCheck.ensureSpace(File("."), 7_200_000_000L, freeBytes = 400_000_000L)
        }
        assertEquals(665_280_000L, e.neededBytes)
    }

    @Test
    fun `trộn stereo 16-bit về mono bằng trung bình`() {
        val b = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
        for (v in shortArrayOf(1000, 3000, -32768, -32768, 32767, -32767, 0, 1)) b.putShort(v)
        val out = FloatArray(4)
        assertEquals(4, Downmix.pcm16(b, 0, 16, 2, out))
        assertArrayEquals(floatArrayOf(2000f / 32768f, -1f, 0f, 0.5f / 32768f), out, 0f)
    }

    @Test
    fun `trộn float nhiều kênh, có offset`() {
        val b = ByteBuffer.allocate(4 + 24).order(ByteOrder.nativeOrder())
        b.putFloat(9f)
        for (v in floatArrayOf(0.3f, 0.6f, -0.3f, 1f, 1f, 1f)) b.putFloat(v)
        val out = FloatArray(2)
        assertEquals(2, Downmix.pcmFloat(b, 4, 24, 3, out))
        assertEquals(0.2f, out[0], 1e-7f)
        assertEquals(1f, out[1], 0f)
    }

    @Test
    fun `điền tổng số mẫu và MD5 vào STREAMINFO`() {
        val head = ByteArray(FlacHeader.HEAD_BYTES)
        FlacHeader.MAGIC.copyInto(head)
        head[4] = 0x80.toByte() // khối cuối, kiểu 0 = STREAMINFO
        head[7] = 34
        head[8 + 13] = 0xA0.toByte() // 4 bit cao là số bit/mẫu, phải giữ nguyên
        val md5 = MessageDigest.getInstance("MD5").digest(byteArrayOf(1, 2, 3))
        val total = 0x1_2345_6789L
        FlacHeader.patchStreamInfo(head, total, md5)
        assertEquals(total, FlacHeader.totalSamples(head))
        assertEquals(0xA0, head[8 + 13].toInt() and 0xF0)
        assertArrayEquals(md5, FlacHeader.md5(head))
        // Gọi lại với cùng số mẫu: không đổi gì; số mẫu khác thì lỗi.
        FlacHeader.patchStreamInfo(head, total, ByteArray(16))
        assertArrayEquals(md5, FlacHeader.md5(head))
        assertThrows(IllegalStateException::class.java) { FlacHeader.patchStreamInfo(head, 5, md5) }
    }
}

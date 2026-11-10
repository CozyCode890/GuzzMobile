package io.github.cozycode890.guzz.core.media

import java.io.File

/** Không đủ chỗ trống cho thư mục tạm của bài. */
class InsufficientStorageException(val neededBytes: Long, val freeBytes: Long) : RuntimeException(
    "Không đủ chỗ trống: cần khoảng ${neededBytes / 1_000_000} MB cho tệp tạm, chỉ còn ${freeBytes / 1_000_000} MB.",
)

/**
 * Kiểm dung lượng trước khi giải mã (D44). Không giới hạn độ dài bài; giới hạn duy nhất là chỗ trống.
 *
 * Ước lượng mỗi giây âm thanh: 32 KB `goc.pcm` + 32 KB `sach.pcm` (16 kHz × 2 byte, sạch không dài hơn gốc) + 20 KB FLAC
 * (Stage 1 đo 9,6 MB / 10 phút ≈ 16 KB/s), cộng 10% dư: khoảng 330 MB mỗi giờ.
 */
object StorageCheck {
    const val BYTES_PER_SECOND = 32_000L + 32_000L + 20_000L

    fun estimateTempBytes(durationUs: Long): Long {
        val seconds = (durationUs + 999_999) / 1_000_000
        return seconds * BYTES_PER_SECOND * 11 / 10
    }

    /** Ném [InsufficientStorageException] nếu [freeBytes] (mặc định chỗ trống thật của [dir]) không đủ. */
    fun ensureSpace(dir: File, durationUs: Long, freeBytes: Long = usableSpace(dir)) {
        val need = estimateTempBytes(durationUs)
        if (freeBytes < need) throw InsufficientStorageException(need, freeBytes)
    }

    private fun usableSpace(dir: File): Long {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        return d?.usableSpace ?: 0L
    }
}

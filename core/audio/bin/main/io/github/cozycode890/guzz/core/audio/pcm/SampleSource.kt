package io.github.cozycode890.guzz.core.audio.pcm

/**
 * Nguồn mẫu âm thanh mono, đọc theo khoảng (D43). Giá trị trong [-1, 1], thang int16 / 32768 như `read_audio` của
 * desktop. Bản chạy thật đọc tệp PCM 16-bit ([Pcm16File]); test dùng mảng ([FloatArraySource]).
 *
 * Vì sao không truyền cả bài trong một `FloatArray` như desktop: bài 2 giờ ở 16 kHz là 460 MB float, quá heap Java của
 * điện thoại. Mọi bước của Stage 2 chỉ cần đọc lần lượt từng khối.
 */
interface SampleSource {
    /** Tổng số mẫu. */
    val size: Long

    /** Đọc đúng [count] mẫu bắt đầu từ mẫu [start] vào `dst[offset until offset + count]`. */
    fun read(start: Long, dst: FloatArray, offset: Int = 0, count: Int)
}

/** Cỡ khối mặc định khi duyệt: 64 Ki mẫu (4 giây ở 16 kHz, 256 KB float). */
const val DEFAULT_BLOCK_SAMPLES = 1 shl 16

/**
 * Duyệt cả nguồn theo từng khối liền nhau, gọi [action] với (bộ đệm, số mẫu hợp lệ, chỉ số mẫu đầu khối). Bộ đệm dùng lại
 * giữa các lần gọi: [action] không được giữ nó lại.
 */
inline fun SampleSource.forEachBlock(
    blockSamples: Int = DEFAULT_BLOCK_SAMPLES,
    action: (buffer: FloatArray, count: Int, start: Long) -> Unit,
) {
    val buffer = FloatArray(blockSamples)
    var start = 0L
    while (start < size) {
        val count = minOf(blockSamples.toLong(), size - start).toInt()
        read(start, buffer, 0, count)
        action(buffer, count, start)
        start += count
    }
}

/** Nguồn từ mảng có sẵn, cho test và cho bài ngắn. */
class FloatArraySource(private val samples: FloatArray) : SampleSource {
    override val size: Long get() = samples.size.toLong()

    override fun read(start: Long, dst: FloatArray, offset: Int, count: Int) {
        require(start >= 0 && start + count <= samples.size) { "đọc [$start, ${start + count}) ngoài [0, ${samples.size})" }
        samples.copyInto(dst, offset, start.toInt(), start.toInt() + count)
    }
}

/**
 * Nhân hệ số khuếch đại rồi kẹp vào [-1, 1], tính lại mỗi lần đọc thay vì lưu tín hiệu đã khuếch đại.
 *
 * Nhân bằng float32 như `y *= np.float32(gain)` rồi `np.clip(y, -1.0, 1.0)` trong `boost_and_normalize` của desktop,
 * nên từng giá trị ra trùng từng bit với numpy.
 */
class GainSource(private val inner: SampleSource, val gain: Float) : SampleSource {
    override val size: Long get() = inner.size

    override fun read(start: Long, dst: FloatArray, offset: Int, count: Int) {
        inner.read(start, dst, offset, count)
        for (i in offset until offset + count) {
            val v = dst[i] * gain
            dst[i] = if (v > 1f) 1f else if (v < -1f) -1f else v
        }
    }
}

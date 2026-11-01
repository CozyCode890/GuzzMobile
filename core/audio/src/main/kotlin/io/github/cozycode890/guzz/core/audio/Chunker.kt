package io.github.cozycode890.guzz.core.audio

/**
 * Cắt audio đã làm sạch thành các đoạn ~N phút để gửi lên Google AI từng đoạn. Port của chunker.py.
 *
 * Không cắt đúng phút thứ 10: cắt ngang giữa một từ thì cả hai đoạn đều nghe được nửa từ, model đoán bậy hoặc bỏ đi,
 * nối lại sẽ sai. Thay vào đó tìm chỗ LẶNG NHẤT (năng lượng trung bình 400 ms thấp nhất) trong khoảng
 * ±searchSeconds quanh mốc, rồi cắt giữa chỗ đó.
 *
 * Mốc của đoạn sau tính từ điểm cắt THẬT của đoạn trước, nên độ dài các đoạn luôn nằm trong
 * [N - searchSeconds, N + searchSeconds], không bị trôi dần. Đoạn cuối ngắn hơn minLastChunkSeconds thì gộp vào đoạn
 * trước.
 */
object Chunker {
    /** Độ phân giải khi đo năng lượng (chunker.py: `_BLOCK_MS`). */
    const val BLOCK_MS = 50

    /** "Chỗ lặng" phải lặng liên tục chừng này (chunker.py: `_SILENCE_WINDOW_MS`). */
    const val SILENCE_WINDOW_MS = 400

    /**
     * Trả về danh sách chỉ số mẫu `[0, c1, c2, ..., y.size]`; đoạn i là `y[points[i] until points[i + 1]]`.
     *
     * Port của chunker.py: find_split_points (kèm `_block_energy`, `_moving_average`). Những chỗ phải giữ đúng như
     * Python để khớp golden test:
     * - `int(round(x))` làm tròn nửa về số chẵn → [Math.rint], không dùng `roundToInt`;
     * - `int(x)` cắt về 0 → `toLong()`;
     * - `np.argmin` lấy chỉ số nhỏ nhất khi bằng nhau → so sánh `<`.
     *
     * Python tính năng lượng khối bằng float32, ở đây bằng double: chỉ lệch khi hai khối gần như bằng nhau; dữ liệu
     * golden được dựng sao cho không có trường hợp đó.
     */
    fun findSplitPoints(
        y: FloatArray,
        sr: Int,
        chunkMinutes: Double = 10.0,
        searchSeconds: Double = 20.0,
        minLastChunkSeconds: Double = 60.0,
    ): List<Int> {
        val n = y.size
        val length = Math.rint(chunkMinutes * 60 * sr).toLong()
        val minLast = (maxOf(0.0, minLastChunkSeconds) * sr).toLong()
        val search = (maxOf(0.0, searchSeconds) * sr).toLong()
        // So `n - length <= minLast` thay cho `n <= length + minLast` để không tràn số khi length rất lớn.
        if (length <= 0 || n - length <= minLast) return listOf(0, n)

        val block = maxOf(1, sr * BLOCK_MS / 1000)
        val smoothed = movingAverage(blockEnergy(y, block), maxOf(1, SILENCE_WINDOW_MS / BLOCK_MS))

        val points = mutableListOf(0)
        while (n - points.last() > length + minLast) {
            val prev = points.last().toLong()
            val target = prev + length
            // Không lùi quá nửa đoạn (đoạn quá ngắn), không tiến sát cuối file (đoạn cuối quá ngắn). Vì
            // n - prev > length + minLast nên luôn có a <= target < b.
            val a = maxOf(prev + length / 2, target - search)
            val b = minOf(target + search, n - minLast)

            val ka = (a / block).toInt()
            val kb = minOf(smoothed.size.toLong(), b / block).toInt()
            val cut = if (search == 0L || ka >= kb) {
                target
            } else {
                var k = ka
                for (i in ka + 1 until kb) {
                    if (smoothed[i] < smoothed[k]) k = i
                }
                k.toLong() * block + block / 2
            }
            points += minOf(maxOf(cut, prev + 1), n - 1L).toInt()
        }
        points += n
        return points
    }

    /** Năng lượng trung bình (bình phương) của từng khối `block` mẫu; phần lẻ cuối bị bỏ. Port của `_block_energy`. */
    internal fun blockEnergy(y: FloatArray, block: Int): DoubleArray {
        val numBlocks = y.size / block
        return DoubleArray(numBlocks) { k ->
            var sum = 0.0
            val start = k * block
            for (i in start until start + block) {
                val v = y[i].toDouble()
                sum += v * v
            }
            sum / block
        }
    }

    /**
     * Trung bình trượt cửa sổ [window] khối, cửa sổ ở mép bị cắt bớt (chia cho số khối thật có).
     * Port của `_moving_average`: khối i lấy trung bình `[i - window / 2, i + window - window / 2)`.
     */
    internal fun movingAverage(x: DoubleArray, window: Int): DoubleArray {
        if (window <= 1 || x.isEmpty()) return x
        val cumsum = DoubleArray(x.size + 1)
        for (i in x.indices) cumsum[i + 1] = cumsum[i] + x[i]
        return DoubleArray(x.size) { i ->
            val head = maxOf(0, i - window / 2)
            val tail = minOf(x.size, i + window - window / 2)
            (cumsum[tail] - cumsum[head]) / (tail - head)
        }
    }
}

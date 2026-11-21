package io.github.cozycode890.guzz.core.audio

/**
 * Gom năng lượng trung bình (bình phương) của từng khối [block] mẫu khi mẫu được nạp dần theo khối bất kỳ; phần lẻ cuối
 * bị bỏ. Bản chạy dần của chunker.py: `_block_energy`, để [Chunker] không cần cả bài trong một mảng.
 *
 * Python tính bằng float32, ở đây bằng double (xem KDoc của [Chunker.findSplitPoints]). Mảng kết quả nhỏ: một số mỗi
 * 50 ms, bài 2 giờ là 144 000 số.
 */
class BlockEnergyAccumulator(val block: Int) {
    init {
        require(block >= 1) { "block phải ≥ 1" }
    }

    private var energy = DoubleArray(1024)
    private var blocks = 0
    private var sum = 0.0
    private var filled = 0

    fun add(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset) {
        for (i in offset until offset + count) {
            val v = samples[i].toDouble()
            sum += v * v
            if (++filled == block) {
                if (blocks == energy.size) energy = energy.copyOf(energy.size * 2)
                energy[blocks++] = sum / block
                sum = 0.0
                filled = 0
            }
        }
    }

    /** Năng lượng các khối đã đủ mẫu. */
    fun result(): DoubleArray = energy.copyOf(blocks)
}

package io.github.cozycode890.guzz.core.audio

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Bản chunker theo khối ([BlockEnergyAccumulator] + [Chunker.findSplitPoints] trên năng lượng) phải trùng bản mảng. */
class ChunkerStreamingTest {

    @Test
    fun `nạp theo khối độ dài bất kỳ cho cùng năng lượng và cùng điểm cắt`() {
        val r = Random(7)
        val sr = 1000
        // 3 phút "tiếng nói" có vài chỗ lặng, tham số thu nhỏ để có nhiều điểm cắt.
        val y = FloatArray(180_000) { (r.nextFloat() - 0.5f) * (if ((it / 7_000) % 5 == 4) 0.01f else 0.3f) }
        val expected = Chunker.findSplitPoints(y, sr, chunkMinutes = 0.5, searchSeconds = 5.0, minLastChunkSeconds = 3.0)

        for (seed in 1..5) {
            val acc = BlockEnergyAccumulator(Chunker.blockSize(sr))
            val rr = Random(seed)
            var i = 0
            while (i < y.size) {
                val n = minOf(y.size - i, 1 + rr.nextInt(3_000))
                acc.add(y, i, n)
                i += n
            }
            val energy = acc.result()
            assertContentEquals(Chunker.blockEnergy(y, Chunker.blockSize(sr)), energy)
            val points = Chunker.findSplitPoints(energy, y.size.toLong(), sr, 0.5, 5.0, 3.0)
            assertEquals(expected.map { it.toLong() }, points)
        }
        assert(expected.size > 4) { "ca thử phải có nhiều điểm cắt: $expected" }
    }
}

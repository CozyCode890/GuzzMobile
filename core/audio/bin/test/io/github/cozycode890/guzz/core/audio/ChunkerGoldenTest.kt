package io.github.cozycode890.guzz.core.audio

import io.github.cozycode890.guzz.core.common.testing.Golden
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * So [Chunker.findSplitPoints] với chính `chunker.find_split_points` của desktop, khớp tuyệt đối.
 *
 * Dữ liệu: `testdata/golden/chunker/`, do `tools/golden/gen_chunker.py` tạo. Mỗi ca trong JSON là một test, tên test =
 * tên ca.
 */
class ChunkerGoldenTest {

    @Serializable
    private class GoldenCase(
        val name: String,
        val wav: String,
        val sr: Int,
        @SerialName("chunk_minutes") val chunkMinutes: Double,
        @SerialName("search_seconds") val searchSeconds: Double,
        @SerialName("min_last_chunk_seconds") val minLastChunkSeconds: Double,
        val expected: List<Int>,
    )

    @Serializable
    private class GoldenFile(val cases: List<GoldenCase>)

    @TestFactory
    fun findSplitPoints(): List<DynamicTest> =
        Golden.readJson<GoldenFile>("chunker/find_split_points.json").cases.map { case ->
            dynamicTest(case.name) {
                val wav = Golden.readWav("chunker/${case.wav}")
                assertEquals(case.sr, wav.sampleRate, "tần số lấy mẫu của ${case.wav}")
                val points = Chunker.findSplitPoints(
                    wav.samples,
                    wav.sampleRate,
                    chunkMinutes = case.chunkMinutes,
                    searchSeconds = case.searchSeconds,
                    minLastChunkSeconds = case.minLastChunkSeconds,
                )
                assertEquals(case.expected, points)
            }
        }
}

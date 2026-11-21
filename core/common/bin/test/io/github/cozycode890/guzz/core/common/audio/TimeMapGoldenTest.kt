package io.github.cozycode890.guzz.core.common.audio

import io.github.cozycode890.guzz.core.common.testing.Golden
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * So [TimeMap] với lớp `TimeMap` của audio_processing.py, khớp từng bit.
 *
 * Dữ liệu: `testdata/golden/timemap/timemap.json`, do `tools/golden/gen_timemap.py` tạo.
 */
class TimeMapGoldenTest {

    @Serializable
    private class GoldenCase(
        val name: String,
        val segments: List<List<Double>>,
        @SerialName("orig_duration") val origDuration: Double?,
        @SerialName("expected_orig_duration") val expectedOrigDuration: Double,
        @SerialName("expected_clean_duration") val expectedCleanDuration: Double,
        @SerialName("expected_json") val expectedJson: List<List<Double>>,
        val probes: List<Double>,
        @SerialName("to_original") val toOriginal: List<Double>,
        @SerialName("to_clean") val toClean: List<Double>,
    )

    @Serializable
    private class GoldenFile(val cases: List<GoldenCase>)

    @TestFactory
    fun timeMap(): List<DynamicTest> =
        Golden.readJson<GoldenFile>("timemap/timemap.json").cases.map { case ->
            dynamicTest(case.name) {
                val tm = TimeMap(case.segments.map { it[0] to it[1] }, case.origDuration)
                assertEquals(case.expectedOrigDuration, tm.origDuration, "orig_duration")
                assertEquals(case.expectedCleanDuration, tm.cleanDuration, "clean_duration")
                assertEquals(case.expectedJson, tm.toJson(), "to_json")
                for ((i, t) in case.probes.withIndex()) {
                    assertEquals(case.toOriginal[i], tm.toOriginal(t), "to_original($t)")
                    assertEquals(case.toClean[i], tm.toClean(t), "to_clean($t)")
                }
            }
        }
}

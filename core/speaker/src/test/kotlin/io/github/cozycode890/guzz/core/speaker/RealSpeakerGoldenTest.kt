package io.github.cozycode890.guzz.core.speaker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals

/**
 * Golden speaker.py trên lượt nói của bản ghi thật B1–B5 (Stage 5, C2): đầu vào và kết quả desktop do
 * `tools/golden/real_speaker.py` ghi ở `$GUZZ_SPIKE_DATA/golden-stage-05/` (ngoài repo). Thiếu dữ liệu thì bỏ qua.
 */
class RealSpeakerGoldenTest {
    private val dataDir: File? = System.getenv("GUZZ_SPIKE_DATA")?.let(::File)?.takeIf { it.isDirectory }

    @TestFactory
    fun realRecordings(): List<DynamicTest> {
        assumeTrue(dataDir != null, "không có GUZZ_SPIKE_DATA: bỏ qua golden trên bản ghi thật")
        val files = File(dataDir, "golden-stage-05").listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }
        assumeTrue(!files.isNullOrEmpty(), "chưa chạy tools/golden/real_speaker.py")
        // Mặc định của config.default.txt: trùng mặc định của SpeakerOptions.
        val opts = SpeakerOptions()
        return files!!.map { f ->
            dynamicTest(f.nameWithoutExtension) {
                val o = Json.parseToJsonElement(f.readText()).jsonObject
                val input = File(o["input"]!!.jsonPrimitive.content)
                assumeTrue(input.isFile, "thiếu $input")
                val raw = loadSpeakerTurns(input)
                val (processed, n) = Speakers.processTurns(raw, opts)
                val expected = o["processed"]!!.jsonArray.map {
                    val a = it.jsonArray
                    SpeakerTurn(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double, a[2].jsonPrimitive.content)
                }
                assertEquals(expected, processed, "process_turns")
                assertEquals(o["num_changed"]!!.jsonPrimitive.int, n)
                val primary = Speakers.findMainSpeaker(raw)
                assertEquals(o["primary"]!!.takeUnless { it is JsonNull }?.jsonPrimitive?.content, primary)
                val names = Speakers.buildNameMap(processed, opts, primary)
                assertEquals(o["name_map"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }, names)
                val minutes = o["minutes"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.double }
                assertEquals(minutes, Speakers.minutesPerSpeaker(processed, names).toList())
            }
        }
    }
}

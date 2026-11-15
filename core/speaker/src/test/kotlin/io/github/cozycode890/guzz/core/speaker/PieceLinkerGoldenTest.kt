package io.github.cozycode890.guzz.core.speaker

import io.github.cozycode890.guzz.core.common.testing.Golden
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * So [PieceLinker] với `spikes/diarization/linking.py` (`piece_bounds`, `link_pieces`, `merge_to_max`): mốc ±1 ms,
 * nhãn và cách nối của từng phần trùng hẳn.
 *
 * - Tổng hợp: `testdata/golden/speaker/link.json` (`tools/golden/gen_link.py`).
 * - Bản ghi thật B1–B5: `$GUZZ_SPIKE_DATA/golden-stage-05/link__*.json` (`tools/golden/real_speaker.py`), thiếu thì bỏ qua.
 */
class PieceLinkerGoldenTest {
    private fun pieces(e: JsonElement): List<Piece> =
        e.jsonArray.map { Golden.json.decodeFromJsonElement(PieceJson.serializer(), it).toPiece() }

    private fun turns(e: JsonElement): List<LabeledTurn> = e.jsonArray.map {
        val a = it.jsonArray
        LabeledTurn(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double, a[2].jsonPrimitive.double.toInt())
    }

    private fun assertTurns(expected: List<LabeledTurn>, actual: List<LabeledTurn>, what: String) {
        assertEquals(expected.size, actual.size, "$what: số lượt")
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            assertEquals(e.label, a.label, "$what: nhãn lượt $i")
            assertTrue(abs(e.start - a.start) <= 1e-3 && abs(e.end - a.end) <= 1e-3, "$what: mốc lượt $i: $e ≠ $a")
        }
    }

    /** Nhật ký nối: so nhãn chung mà mỗi nhãn cục bộ được nối vào (chuỗi "cách" có số làm tròn, so riêng phần chữ). */
    private fun assertLog(expected: JsonElement, actual: List<JsonObject>) {
        val exp = expected.jsonArray
        assertEquals(exp.size, actual.size, "số phần trong nhật ký")
        for (k in exp.indices) {
            val e = exp[k].jsonObject["noi"]!!.jsonObject
            val a = actual[k]["noi"]!!.jsonObject
            assertEquals(e.keys, a.keys, "phần $k: nhãn cục bộ")
            for (l in e.keys) {
                val eo = e[l]!!.jsonObject
                val ao = a[l]!!.jsonObject
                assertEquals(eo["vao"]!!.jsonPrimitive.int, ao["vao"]!!.jsonPrimitive.int, "phần $k nhãn $l: vào")
                val kind = { s: String -> s.substringBefore(' ') }
                assertEquals(kind(eo["cach"]!!.jsonPrimitive.content), kind(ao["cach"]!!.jsonPrimitive.content), "phần $k nhãn $l: cách")
            }
        }
    }

    private fun check(c: JsonObject, input: JsonObject) {
        input["bounds"]?.let { b ->
            val expected = b.jsonArray.map { it.jsonArray[0].jsonPrimitive.long to it.jsonArray[1].jsonPrimitive.long }
            assertEquals(
                expected,
                PieceLinker.pieceBounds(
                    input["duration_samples"]!!.jsonPrimitive.long, input["piece_samples"]!!.jsonPrimitive.long,
                    input["overlap_samples"]!!.jsonPrimitive.long,
                ),
                "piece_bounds",
            )
        }
        val ps = pieces(input["pieces"]!!)
        val r = PieceLinker.link(ps, c["threshold"]!!.jsonPrimitive.double)
        assertTurns(turns(c["linked"]!!), r.turns, "link_pieces")
        assertLog(c["log"]!!, r.log)
        for ((m, exp) in c["max"]!!.jsonObject) {
            assertTurns(turns(exp), PieceLinker.mergeToMax(r.turns, r.globalEmb, m.toInt()), "merge_to_max($m)")
        }
    }

    @TestFactory
    fun synthetic(): List<DynamicTest> {
        val root = Golden.json.parseToJsonElement(Golden.file("speaker/link.json").readText()).jsonObject
        val inputs = root["inputs"]!!.jsonObject
        return root["cases"]!!.jsonArray.map { it.jsonObject }.map { c ->
            dynamicTest(c["name"]!!.jsonPrimitive.content) { check(c, inputs[c["input"]!!.jsonPrimitive.content]!!.jsonObject) }
        }
    }

    @TestFactory
    fun exclusive(): List<DynamicTest> {
        val root = Golden.json.parseToJsonElement(Golden.file("speaker/link.json").readText()).jsonObject
        return root["exclusive"]!!.jsonArray.mapIndexed { i, e ->
            val c = e.jsonObject
            dynamicTest("to_exclusive $i") {
                val segs = turns(c["segs"]!!)
                val ex = Exclusive.toExclusive(segs)
                assertEquals(turns(c["exclusive"]!!), ex, "to_exclusive")
                val (ov, sp) = Exclusive.overlapSeconds(segs)
                assertEquals(c["overlap"]!!.jsonPrimitive.double, ov, 1e-9)
                assertEquals(c["speech"]!!.jsonPrimitive.double, sp, 1e-9)
                val ranges = Exclusive.embeddingRanges(ex.map { it.start to it.end }, 16_000, 60 * 16_000)
                assertEquals(
                    c["ranges"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.int until it.jsonArray[1].jsonPrimitive.int },
                    ranges,
                )
            }
        }
    }

    @TestFactory
    fun realRecordings(): List<DynamicTest> {
        val dataDir = System.getenv("GUZZ_SPIKE_DATA")?.let(::File)?.takeIf { it.isDirectory }
        assumeTrue(dataDir != null, "không có GUZZ_SPIKE_DATA: bỏ qua")
        val files = File(dataDir, "golden-stage-05").listFiles { f -> f.name.startsWith("link__") }?.sortedBy { it.name }
        assumeTrue(!files.isNullOrEmpty(), "chưa chạy tools/golden/real_speaker.py")
        return files!!.map { f ->
            dynamicTest(f.nameWithoutExtension) {
                val c = Golden.json.parseToJsonElement(f.readText()).jsonObject
                val src = File(c["pieces_file"]!!.jsonPrimitive.content)
                assumeTrue(src.isFile, "thiếu $src")
                val input = JsonObject(mapOf("pieces" to Golden.json.parseToJsonElement(src.readText())))
                check(c, input)
            }
        }
    }
}

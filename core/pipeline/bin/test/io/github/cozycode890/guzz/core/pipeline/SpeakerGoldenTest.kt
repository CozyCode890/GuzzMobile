package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.audio.ChunkInfo
import io.github.cozycode890.guzz.core.common.audio.TimeMap
import io.github.cozycode890.guzz.core.common.testing.Golden
import io.github.cozycode890.guzz.core.gemini.DefaultPrompt
import io.github.cozycode890.guzz.core.gemini.GeminiConfig
import io.github.cozycode890.guzz.core.gemini.closestAlternativeMethod
import io.github.cozycode890.guzz.core.speaker.SpeakerTurn
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * Phần người nói của pipeline so với transcribe.py / config.py của desktop: `speaker_hash`, `shared_key` + tên tệp
 * kết quả, prompt người nói, `assemble_transcript` + `format_header` (3 cách), `full_speaker_map`,
 * `closest_alternative_method`. Dữ liệu: `testdata/golden/pipeline/speaker.json` (`tools/golden/gen_pipeline.py`).
 */
class SpeakerGoldenTest {
    private val root: JsonObject by lazy {
        Golden.json.parseToJsonElement(Golden.file("pipeline/speaker.json").readText()).jsonObject
    }

    private fun JsonObject.str(k: String) = this[k]!!.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    @Test
    fun speakerHash() {
        for (e in root["speaker_hash"]!!.jsonArray) {
            val c = e.jsonObject
            assertEquals(
                c.str("hash"),
                CacheKeys.speakerHash(
                    c.str("audio_key")!!, c.str("run_on")!!, c.str("model")!!, c["num"]!!.jsonPrimitive.int,
                    c["min"]!!.jsonPrimitive.int, c["max"]!!.jsonPrimitive.int,
                ),
            )
        }
    }

    @Test
    fun diarizationPrompt() = assertEquals(root.str("diarization_prompt"), DefaultPrompt.diarization)

    @Test
    fun sharedKeysAndResultFiles() {
        for (e in root["shared_keys"]!!.jsonArray) {
            val c = e.jsonObject
            val method = c.str("method")
            val model = c.str("model")!!
            val cfg = GeminiConfig(model = model, languages = listOf("vi"), vocabulary = listOf("ESG"), transcribeMode = "verbatim")
            val plain = if ("transcribe" in model) null else DefaultPrompt.text
            val key = CacheKeys.sharedKey(
                "0123456789", plain, cfg, method = method,
                speakerPrompt = if (method == "pyannote") DefaultPrompt.diarization else null,
                mapDelivery = c.str("map_delivery")!!, timestamps = c["timestamps"]!!.jsonPrimitive.boolean,
            )
            assertEquals(c.str("shared_key"), key, "$method ${c.str("map_delivery")}")
            assertEquals(c.str("result_file"), CacheKeys.resultFileName("doan_002.flac", key, c.str("map_text")))
        }
    }

    @TestFactory
    fun assemble(): List<DynamicTest> {
        val tm = TimeMap(
            root["time_map"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.double to it.jsonArray[1].jsonPrimitive.double },
            root["orig_duration"]!!.jsonPrimitive.double,
        )
        val chunks = root["chunks"]!!.jsonArray.map {
            val o = it.jsonObject
            ChunkInfo(o.str("file")!!, o["bat_dau_giay"]!!.jsonPrimitive.double, o["ket_thuc_giay"]!!.jsonPrimitive.double)
        }
        val turns = root["turns"]!!.jsonArray.map {
            val a = it.jsonArray
            SpeakerTurn(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double, a[2].jsonPrimitive.content)
        }
        val nameMap = root["name_map"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val results = root["results"]!!.jsonObject
        return root["assemble"]!!.jsonArray.map { it.jsonObject }.map { c ->
            val method = c.str("method")
            dynamicTest("$method mark=${c["mark_chunks"]} ts=${c["timestamps"]}") {
                val rs = results[method ?: "None"]!!.jsonArray.map {
                    val o = it.jsonObject
                    ChunkResult(o.str("van_ban")!!, o["tu"]!!)
                }
                val sp = SpeakerConfig(
                    timestamps = c["timestamps"]!!.jsonPrimitive.boolean,
                    paragraphTemplate = c.str("template")!!,
                    options = SpeakerConfig().options.copy(smooth = c["smooth"]!!.jsonPrimitive.boolean, densityMinSec = 5.0),
                )
                val (body, minutes) = SpeakerTranscript.assemble(
                    method, sp, c["mark_chunks"]!!.jsonPrimitive.boolean, chunks, rs, turns,
                    if (method == "pyannote") nameMap else emptyMap(), tm,
                )
                assertEquals(c.str("body"), body)
                val expMinutes = c["minutes"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.doubleOrNull }
                assertEquals(expMinutes, minutes.toList())
                val header = Transcript.formatHeader(
                    false, "bai giang.m4a", LocalDateTime.of(2026, 11, 12, 21, 4, 5), tm.origDuration,
                    mapOf("gemini-3.8-flash" to 3), "gemini-3.8-flash", minutes,
                )
                assertEquals(c.str("header"), header)
            }
        }
    }

    @Test
    fun fullSpeakerMap() {
        val tm = TimeMap(
            root["time_map"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.double to it.jsonArray[1].jsonPrimitive.double },
            root["orig_duration"]!!.jsonPrimitive.double,
        )
        val turns = root["turns"]!!.jsonArray.map {
            val a = it.jsonArray
            SpeakerTurn(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double, a[2].jsonPrimitive.content)
        }
        val nameMap = root["name_map"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(root.str("full_speaker_map"), SpeakerTranscript.fullSpeakerMap(turns, nameMap, tm))
    }

    @Test
    fun closestAlternative() {
        for (e in root["alternatives"]!!.jsonArray) {
            val c = e.jsonObject
            assertEquals(c.str("closest"), closestAlternativeMethod(c.str("model")!!, c.str("method")!!), "${c.str("model")} ${c.str("method")}")
        }
    }
}

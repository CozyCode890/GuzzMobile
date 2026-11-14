package io.github.cozycode890.guzz.core.speaker

import io.github.cozycode890.guzz.core.common.audio.TimeMap
import io.github.cozycode890.guzz.core.common.testing.Golden
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import kotlin.test.assertEquals

/**
 * So [Speakers] với speaker.py của desktop trên lượt nói và từ tổng hợp. Dữ liệu: `testdata/golden/speaker/speaker.json`,
 * do `tools/golden/gen_speaker.py` tạo. Số thực so đúng từng bit (cùng phép tính, cùng thứ tự).
 */
class SpeakerGoldenTest {
    private val root: JsonObject by lazy { Golden.json.parseToJsonElement(Golden.file("speaker/speaker.json").readText()).jsonObject }

    private val turnSets: Map<String, List<SpeakerTurn>> by lazy {
        root["turn_sets"]!!.jsonObject.mapValues { (_, v) -> turns(v) }
    }

    private fun str(e: JsonElement?): String? = if (e == null || e is JsonNull) null else e.jsonPrimitive.content

    private fun turns(e: JsonElement): List<SpeakerTurn> = e.jsonArray.map {
        val a = it.jsonArray
        SpeakerTurn(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double, str(a[2]))
    }

    private fun stringMap(e: JsonElement): Map<String, String> = e.jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun options(e: JsonElement): SpeakerOptions {
        val o = e.jsonObject
        fun d(k: String) = o[k]!!.jsonPrimitive.double
        fun b(k: String) = o[k]!!.jsonPrimitive.boolean
        fun s(k: String) = o[k]!!.jsonPrimitive.content
        return SpeakerOptions(
            smooth = b("smooth"), sandwichMaxSec = d("sandwich_max_sec"), sandwichGapSec = d("sandwich_gap_sec"),
            densityWindowSec = d("density_window_sec"), densityMinSec = d("density_min_sec"),
            mergeGapSec = d("merge_gap_sec"), labelMain = b("label_main"), mainLabel = s("main_label"),
            otherLabel = s("other_label"), genericLabel = s("generic_label"),
        )
    }

    private fun timeMap(o: JsonObject): TimeMap = TimeMap(
        o["segments"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.double to it.jsonArray[1].jsonPrimitive.double },
        o["orig_duration"]?.jsonPrimitive?.doubleOrNull,
    )

    private fun paragraphs(e: JsonElement): List<Paragraph> = e.jsonArray.map {
        val a = it.jsonArray
        Paragraph(a[0].jsonPrimitive.content, str(a[1]), a[2].jsonPrimitive.doubleOrNull, a[3].jsonPrimitive.boolean)
    }

    private fun spans(e: JsonElement): List<Span> = e.jsonArray.map {
        val a = it.jsonArray
        Span(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double, a[2].jsonPrimitive.content, str(a[3]),
            a[4].jsonPrimitive.boolean)
    }

    private fun cases(key: String): List<JsonObject> = root[key]!!.jsonArray.map { it.jsonObject }

    @TestFactory
    fun processTurns(): List<DynamicTest> = cases("process").map { c ->
        dynamicTest(c["name"]!!.jsonPrimitive.content) {
            val raw = turnSets.getValue(c["set"]!!.jsonPrimitive.content)
            val opts = options(c["options"]!!)
            val (processed, n) = Speakers.processTurns(raw, opts)
            assertEquals(turns(c["processed"]!!), processed, "process_turns")
            assertEquals(c["num_changed"]!!.jsonPrimitive.int, n, "số lượt đổi nhãn")
            val primary = Speakers.findMainSpeaker(raw)
            assertEquals(str(c["primary"]), primary, "find_main_speaker")
            val names = Speakers.buildNameMap(processed, opts, primary)
            assertEquals(stringMap(c["name_map"]!!), names, "build_name_map")
            assertEquals(stringMap(c["name_map_self"]!!), Speakers.buildNameMap(processed, opts), "build_name_map tự tính")
            val minutes = Speakers.minutesPerSpeaker(processed, names)
            assertEquals(c["minutes"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double }, minutes, "minutes")
            assertEquals(c["minutes_order"]!!.jsonArray.map { it.jsonPrimitive.content }, minutes.keys.toList())
        }
    }

    @TestFactory
    fun chunks(): List<DynamicTest> = cases("chunks").map { c ->
        dynamicTest(c["name"]!!.jsonPrimitive.content) {
            val raw = turnSets.getValue(c["set"]!!.jsonPrimitive.content)
            val names = stringMap(c["name_map"]!!)
            val chunk = Speakers.splitTurnsByChunk(
                raw, c["start"]!!.jsonPrimitive.double, c["end"]!!.jsonPrimitive.double, c["min_sec"]!!.jsonPrimitive.double,
            )
            assertEquals(turns(c["chunk"]!!), chunk, "split_turns_by_chunk")
            assertEquals(c["map_text"]!!.jsonPrimitive.content, Speakers.speakerMapText(chunk, names), "speaker_map_text")
            assertEquals(c["speakers"]!!.jsonArray.map { it.jsonPrimitive.content }, Speakers.speakersInChunk(chunk, names))
        }
    }

    @TestFactory
    fun parsePrompt(): List<DynamicTest> = cases("prompt").map { c ->
        dynamicTest(c["name"]!!.jsonPrimitive.content) {
            val tm = timeMap(c)
            val got = Speakers.parsePromptParagraphs(
                c["text"]!!.jsonPrimitive.content,
                c["names"]!!.jsonArray.map { it.jsonPrimitive.content },
                c["chunk_start"]!!.jsonPrimitive.double,
                tm::toOriginal,
            )
            assertEquals(paragraphs(c["paragraphs"]!!), got)
        }
    }

    @TestFactory
    fun words(): List<DynamicTest> = cases("words").map { c ->
        dynamicTest(c["name"]!!.jsonPrimitive.content) {
            val set = c["set"]!!.jsonPrimitive.content
            val turnsIn = turnSets.getValue(set)
            val words = root["word_lists"]!!.jsonObject[set]!!.jsonArray.map {
                val o = it.jsonObject
                Speakers.Word(o["tu"]!!.jsonPrimitive.content, o["bat_dau"]!!.jsonPrimitive.double,
                    o["ket_thuc"]!!.jsonPrimitive.double, str(o["nguoi_noi"]))
            }
            val opts = options(c["options"]!!)
            val spans = Speakers.wordsToSpans(words, c["offset"]!!.jsonPrimitive.double)
            Speakers.assignSpeakers(spans, turnsIn)
            assertEquals(spans(c["assigned"]!!), spans, "assign_speakers")
            val changed = Speakers.smooth(
                spans, turnsIn, opts.sandwichMaxSec, opts.sandwichGapSec, opts.densityWindowSec, opts.densityMinSec,
                preferPrimary = opts.labelMain,
            )
            assertEquals(c["smooth_changed"]!!.jsonPrimitive.int, changed, "smooth")
            val primary = Speakers.findMainSpeaker(turnsIn)
            assertEquals(str(c["primary"]), primary)
            val names = Speakers.buildNameMap(spans, opts, primary)
            assertEquals(stringMap(c["name_map"]!!), names, "build_name_map")
            Speakers.renameSpeakers(spans, names)
            val merged = Speakers.mergeSpans(spans, c["max_seconds"]!!.jsonPrimitive.double, c["gap"]!!.jsonPrimitive.double)
            assertEquals(spans(c["merged"]!!), merged, "merge_spans")
            assertEquals(paragraphs(c["paragraphs"]!!), Speakers.spansToParagraphs(merged, timeMap(c)::toOriginal))
        }
    }

    @TestFactory
    fun formatParagraph(): List<DynamicTest> = cases("format").mapIndexed { i, c ->
        val p = paragraphs(JsonArray(listOf(c["paragraph"]!!)))[0]
        val template = c["template"]!!.jsonPrimitive.content
        val ts = c["timestamps"]!!.jsonPrimitive.boolean
        val mr = c["mark_review"]!!.jsonPrimitive.boolean
        dynamicTest("$i: $template ts=$ts review=$mr") {
            assertEquals(c["result"]!!.jsonPrimitive.content, Speakers.formatParagraph(p, template, ts, mr))
        }
    }

    @Test
    fun timeFormats() {
        for (e in root["mm_ss"]!!.jsonArray) {
            val a = e.jsonArray
            assertEquals(a[1].jsonPrimitive.content, Speakers.mmSs(a[0].jsonPrimitive.double), "_mm_ss(${a[0]})")
        }
        for (e in root["hh_mm_ss"]!!.jsonArray) {
            val a = e.jsonArray
            assertEquals(a[1].jsonPrimitive.content, Speakers.hhMmSs(a[0].jsonPrimitive.double), "hh_mm_ss(${a[0]})")
        }
        for (e in root["join_words"]!!.jsonArray) {
            val a = e.jsonArray
            assertEquals(a[2].jsonPrimitive.content, Speakers.joinWords(a[0].jsonPrimitive.content, a[1].jsonPrimitive.content))
        }
    }
}

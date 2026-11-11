package io.github.cozycode890.guzz.core.gemini

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * B2: thân yêu cầu và cách đọc phản hồi trùng desktop. Dữ liệu `gemini/requests.json` do `gen_gemini.py` chạy
 * `build_request` (ma trận cài đặt × tham số), `extract_text`, `extract_words`, `parse_input_tokens`, `guess_mime`,
 * `mask_api_key`, `is_transcribe_model` của google_ai.py.
 */
class RequestGoldenTest {
    private val golden = goldenJson("requests.json")

    @TestFactory
    fun `build_request`(): List<DynamicTest> = golden.getValue("build_request").jsonArray.mapIndexed { i, c ->
        val case = c.jsonObject
        val cfg = cfgFrom(case.getValue("cfg").jsonObject)
        val args = case.getValue("args").jsonObject
        dynamicTest("$i: ${cfg.model} ${cfg.transcribeMode} $args") {
            val document = args["document"]?.jsonArray?.let { Document(it[0].str, it[1].str) }
            val request = buildRequest(
                cfg, "https://g/files/f1", "audio/flac",
                prompt = args["prompt"].strOrNull,
                model = args["model"].strOrNull,
                diarize = args["diarize"]?.jsonPrimitive?.boolean ?: false,
                wordTimestamps = args["word_timestamps"]?.jsonPrimitive?.boolean ?: false,
                document = document,
                attachedText = args["attached_text"].strOrNull,
            )
            assertJsonEquals(case.getValue("expected"), request)
        }
    }

    @TestFactory
    fun `đọc phản hồi`(): List<DynamicTest> = golden.getValue("responses").jsonArray.map { c ->
        val case = c.jsonObject
        dynamicTest(case.getValue("name").str) {
            val response = case.getValue("response").jsonObject
            assertEquals(case.getValue("text").str, extractText(response))
            val words = buildJsonArray {
                for (w in extractWords(response)) {
                    add(
                        buildJsonObject {
                            put("tu", w.text)
                            put("nguoi_noi", w.speaker?.let(::JsonPrimitive) ?: JsonNull)
                            put("bat_dau", w.start)
                            put("ket_thuc", w.end)
                        },
                    )
                }
            }
            assertJsonEquals(case.getValue("words"), words)
            assertEquals(case.getValue("input_tokens").jsonPrimitive.long, parseInputTokens(response))
        }
    }

    @TestFactory
    fun `tiện ích`(): List<DynamicTest> =
        golden.getValue("guess_mime").jsonArray.map { c ->
            val path = c.jsonObject.getValue("path").str
            dynamicTest("guess_mime $path") { assertEquals(c.jsonObject.getValue("expected").str, guessMime(path)) }
        } + golden.getValue("mask_api_key").jsonArray.mapIndexed { i, c ->
            val key = c.jsonObject["key"].strOrNull
            dynamicTest("mask_api_key $i") { assertEquals(c.jsonObject.getValue("expected").str, maskApiKey(key)) }
        } + golden.getValue("is_transcribe_model").jsonArray.mapIndexed { i, c ->
            val model = c.jsonObject["model"].strOrNull
            dynamicTest("is_transcribe_model $i $model") {
                assertEquals(c.jsonObject.getValue("expected").jsonPrimitive.boolean, isTranscribeModel(model))
            }
        }
}

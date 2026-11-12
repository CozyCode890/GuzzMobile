package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.audio.ChunkSettings
import io.github.cozycode890.guzz.core.common.testing.Golden
import io.github.cozycode890.guzz.core.gemini.GeminiConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** So các hàm thuần của pipeline với chính transcribe.py / config.py (`tools/golden/gen_pipeline.py`). */
class PipelineGoldenTest {
    private fun golden(name: String): JsonObject =
        Golden.json.parseToJsonElement(Golden.file("pipeline/$name").readText(Charsets.UTF_8)).jsonObject

    private val JsonElement.str: String get() = jsonPrimitive.content
    private val JsonElement.strOrNull: String? get() = if (this is JsonNull) null else jsonPrimitive.content
    private fun JsonObject.arr(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    /** JSON của Python → giá trị Kotlin cùng kiểu: số có `.`/`e` là số thực, còn lại là số nguyên. */
    private fun toKotlin(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonArray -> e.map(::toKotlin)
        is JsonObject -> e.mapValues { toKotlin(it.value) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.boolean
            e.content.any { it in ".eE" } || e.content in listOf("NaN", "Infinity", "-Infinity") -> e.double
            else -> e.content.toLong()
        }
    }

    // ---------------------------------------------------------------- B1

    @Test
    fun `json dumps và mã băm trùng desktop`() {
        for (c in golden("hash.json").arr("hash_cases")) {
            val parts = toKotlin(c.getValue("parts")) as List<*>
            assertEquals(c.getValue("dumps").str, pyJsonDumps(parts))
            assertEquals(c.getValue("hash").str, pyHash(*parts.toTypedArray()))
        }
    }

    @Test
    fun `tên thư mục tạm trùng temp_dir_for`() {
        for (c in golden("hash.json").arr("temp_dirs")) {
            assertEquals(c.getValue("dir").str, CacheKeys.tempDirName(c.getValue("name").str, c.getValue("fingerprint").str))
        }
    }

    @Test
    fun `mã âm thanh theo đúng thứ tự tham số, chữ ký video băm chồng`() {
        for (c in golden("hash.json").arr("audio_hash")) {
            val p = toKotlin(c.getValue("params")) as List<*>
            val chunk = ChunkSettings(
                targetDbfs = p[1] as Double, trimSilence = p[2] as Boolean, minSilenceLen = (p[3] as Long).toInt(),
                silenceThreshOffset = (p[4] as Long).toInt(), keepSilence = (p[5] as Long).toInt(),
                chunkMinutes = p[6] as Double, searchSeconds = p[7] as Double, minLastChunkSeconds = p[8] as Double,
            )
            val sig = toKotlin(c.getValue("video_signature")) as List<*>?
            val hash = CacheKeys.audioHash(
                c.getValue("fingerprint").str, (p[0] as Long).toInt(), chunk, p[9] as String, sig,
            )
            assertEquals(c.getValue("hash").str, hash)
            // Đổi một tham số âm thanh thì mã đổi.
            assertNotEquals(
                hash,
                CacheKeys.audioHash(
                    c.getValue("fingerprint").str, (p[0] as Long).toInt(),
                    chunk.copy(chunkMinutes = chunk.chunkMinutes + 1), p[9] as String, sig,
                ),
            )
        }
    }

    @Test
    fun `khoá chung và tên tệp kết quả đoạn trùng transcribe_chunks`() {
        for (c in golden("hash.json").arr("shared_keys")) {
            val cfg = GeminiConfig(
                model = c.getValue("model").str,
                languages = c.getValue("languages").jsonArray.map { it.str },
                vocabulary = c.getValue("vocabulary").jsonArray.map { it.str },
                transcribeMode = c.getValue("transcribe_mode").str,
            )
            val key = CacheKeys.sharedKey(c.getValue("audio_key").str, c.getValue("prompt").strOrNull, cfg)
            assertEquals(c.getValue("shared_key").str, key)
            assertEquals(c.getValue("result_file").str, CacheKeys.resultFileName("doan_003.flac", key))
        }
    }

    @Test
    fun `vân tay lấy giây nguyên của giờ sửa`() {
        assertEquals("/a/b.m4a|10|1789660800", CacheKeys.fingerprint("/a/b.m4a", 10, 1_789_660_800_999))
        assertEquals("content://x/1|5|0", CacheKeys.fingerprint("content://x/1", 5, 0))
    }

    // ---------------------------------------------------------------- B2

    @Test
    fun `tên tệp ra trùng output_filename, mẫu sai thì báo lỗi`() {
        val g = golden("naming.json")
        val recorded = LocalDateTime.parse(g.getValue("recorded").str)
        val now = LocalDateTime.parse(g.getValue("now").str)
        for (c in g.arr("filenames")) {
            val template = c.getValue("template").str
            val source = c.getValue("source").str
            val expected = c.getValue("name").strOrNull
            if (expected == null) {
                assertFailsWith<TemplateException>("$template | $source") {
                    OutputNaming.outputFilename(template, source, recorded, now, g.getValue("model").str, ".txt")
                }
                assertNotEquals(null, OutputNaming.templateError(template))
            } else {
                assertEquals(
                    expected,
                    OutputNaming.outputFilename(template, source, recorded, now, g.getValue("model").str, ".txt"),
                    "$template | $source",
                )
                assertNull(OutputNaming.templateError(template))
            }
        }
    }

    @Test
    fun `mobile không nhận định dạng, chỉ số, thuộc tính trong mẫu tên`() {
        for (t in listOf("{name!r}", "{name:>12}", "{name[0]}", "{name.upper}")) {
            assertNotEquals(null, OutputNaming.templateError(t), t)
        }
    }

    @Test
    fun `sanitize_filename, unique_path, write_text trùng desktop`() {
        val g = golden("naming.json")
        for (c in g.arr("sanitize")) {
            assertEquals(c.getValue("output").str, OutputNaming.sanitizeFilename(c.getValue("input").str))
        }
        for (c in g.arr("unique")) {
            val existing = c.getValue("existing").jsonArray.map { it.str }
            assertEquals(c.getValue("result").str, OutputNaming.uniqueName(c.getValue("name").str, existing))
        }
        for (c in g.arr("encode")) {
            val bytes = OutputNaming.encodeText(c.getValue("text").str, c.getValue("encoding").str, c.getValue("newline").str)
            assertEquals(c.getValue("base64").str, Base64.getEncoder().encodeToString(bytes), c.toString())
        }
    }

    // ---------------------------------------------------------------- B3

    @Test
    fun `hms, phần đầu, thân bản chép lời trùng desktop từng ký tự`() {
        val g = golden("transcript.json")
        for (c in g.arr("hms")) {
            assertEquals(c.getValue("hms").str, Transcript.hms(c.getValue("sec").jsonPrimitive.double))
        }
        val now = LocalDateTime.parse(g.getValue("now").str)
        for (c in g.arr("headers")) {
            val path = c.getValue("path").str
            val models = LinkedHashMap<String, Int>()
            c.getValue("models").jsonArray.forEach { models[it.jsonArray[0].str] = it.jsonArray[1].jsonPrimitive.int }
            val header = Transcript.formatHeader(
                Transcript.isVideo(path, null), path, now, c.getValue("duration").jsonPrimitive.double, models,
                g.getValue("default_model").str,
            )
            assertEquals(c.getValue("header").str, header)
        }
        for (c in g.arr("assemble")) {
            val results = c.getValue("texts").jsonArray.map { ChunkResult(it.str, model = "m") }
            assertEquals(c.getValue("body").str, Transcript.assemble(results, c.getValue("mark_chunks").jsonPrimitive.boolean))
        }
        val cm = g.getValue("count_models").jsonObject
        val results = cm.getValue("models").jsonArray.map { ChunkResult("x", model = it.strOrNull) }
        val counts = Transcript.countModels(results, cm.getValue("default_model").str)
        assertEquals(cm.getValue("counts").jsonArray.map { it.jsonArray[0].str to it.jsonArray[1].jsonPrimitive.int }, counts.toList())
        for (c in g.arr("has_speech")) {
            assertEquals(c.getValue("has_speech").jsonPrimitive.boolean, Transcript.hasSpeech(c.getValue("map").strOrNull))
        }
    }
}

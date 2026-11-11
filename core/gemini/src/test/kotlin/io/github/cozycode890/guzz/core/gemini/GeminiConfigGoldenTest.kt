package io.github.cozycode890.guzz.core.gemini

import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * A2 + B6: cài đặt Gemini trùng desktop. Dữ liệu `gemini/config.json` do `gen_gemini.py` đọc `config.default.txt` bằng
 * `load_config` và chạy `model_chain`, `quota_for`, `valid_diarization_methods`, `constraint_error` của config.py.
 */
class GeminiConfigGoldenTest {
    private val golden = goldenJson("config.json")

    @Test
    fun `QUOTAS_lines mặc định đọc ra đúng model_quotas của desktop`() {
        val expected = golden.getValue("default_model_quotas").jsonObject.mapValues { quotaFrom(it.value) }
        assertEquals(expected, parseQuotaLines(SettingKeys.QUOTAS_LINES.default))
        assertEquals(expected, GeminiConfig().modelQuotas)
    }

    @Test
    fun `model và chuỗi dự phòng mặc định trùng config_default_txt`() {
        assertEquals(golden.getValue("default_model").str, GeminiConfig().model)
        assertEquals(golden.getValue("default_fallback_models").jsonArray.map { it.str }, GeminiConfig().fallbackModels)
    }

    @TestFactory
    fun `section QUOTAS đọc như configparser`(): List<DynamicTest> =
        golden.getValue("quota_sections").jsonObject.map { (name, c) ->
            dynamicTest(name) {
                val o = c.jsonObject
                val expected = o.getValue("expected").jsonObject.mapValues { quotaFrom(it.value) }
                assertEquals(expected, parseQuotaLines(o.getValue("text").str))
            }
        } + golden.getValue("quota_specs").jsonArray.map { c ->
            val o = c.jsonObject
            val spec = o.getValue("spec").str
            dynamicTest("read_quota_spec '$spec'") {
                val expected = o.getValue("expected").takeUnless { it is JsonNull }?.let(::quotaFrom)
                assertEquals(expected, readQuotaSpec(spec))
            }
        }

    @TestFactory
    fun `model_chain và ràng buộc`(): List<DynamicTest> = golden.getValue("chains").jsonArray.mapIndexed { i, c ->
        val o = c.jsonObject
        val cfg = cfgFrom(o.getValue("cfg").jsonObject)
        dynamicTest("$i: ${cfg.model} ${cfg.currentDiarizationMethod()} auto=${cfg.autoSwitchModel} ${cfg.fallbackModels}") {
            assertEquals(o.getValue("chain").jsonArray.map { it.str }, cfg.modelChain())
            assertEquals(o.getValue("constraint_ok").jsonPrimitive.boolean, constraintError(cfg) == null)
            assertEquals(o.getValue("valid_methods").jsonArray.map { it.str }, validDiarizationMethods(cfg.model))
            for ((model, quota) in o.getValue("quota_for").jsonObject) {
                assertEquals(quotaFrom(quota), cfg.quotaFor(model), "quota_for($model)")
            }
        }
    }
}

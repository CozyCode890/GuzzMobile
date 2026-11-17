package io.github.cozycode890.guzz.core.common.settings

import io.github.cozycode890.guzz.core.common.testing.Golden
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * Preset âm thanh. Port các ca của tests/test_preset.py (phần không đọc config.txt) và golden bảng preset so với
 * gui/presets.py (`testdata/golden/config/presets.json`, do `tools/golden/gen_config_keys.py` ghi).
 */
class AudioPresetsTest {

    @TempDir
    lateinit var dir: File

    @Serializable
    private class Desktop(
        val fields: List<String>,
        val presets: Map<String, Map<String, JsonPrimitive>>,
        val labels: List<String>,
        @Suppress("PropertyName") val default_preset: String,
    )

    private val desktop = Golden.readJson<Desktop>("config/presets.json")

    @Test
    fun `bảng preset trùng desktop`() {
        // write_json sắp khoá theo chữ cái: thứ tự lấy từ PRESET_LABELS (đúng thứ tự PRESETS, thêm "custom" cuối).
        assertEquals(desktop.presets.keys, AudioPresets.all.keys)
        assertEquals(desktop.labels, AudioPresets.all.keys.toList() + AudioPresets.CUSTOM)
        for ((name, values) in AudioPresets.all) {
            val entries = values.entries()
            assertEquals(desktop.fields, entries.map { it.first.key }, name)
            for ((key, value) in entries) {
                val expected = desktop.presets.getValue(name).getValue(key.key)
                assertEquals(expected.double, (value as Number).toDouble(), "$name.${key.key}")
            }
        }
    }

    @Test
    fun test_every_preset_has_all_fields_and_a_label() {
        // Không hai preset nào trùng nhau, không thì trang Âm thanh chỉ nhận ra preset đầu.
        assertEquals(AudioPresets.all.size, AudioPresets.all.values.toSet().size)
    }

    @Test
    fun `mặc định của app là preset của config default desktop (D87)`() = runBlocking {
        assertEquals("moving_lecturer", desktop.default_preset)
        withSettings { settings ->
            assertEquals(AudioPresets.MOVING_LECTURER, AudioPresets.detect(AudioPresetValues.read(settings)))
        }
    }

    @Test
    fun test_config_saved_from_preset_reads_back_as_that_preset() = runBlocking {
        withSettings { settings ->
            for ((name, values) in AudioPresets.all) {
                values.applyTo(settings)
                assertEquals(name, AudioPresets.detect(AudioPresetValues.read(settings)))
            }
            AudioPresets.all.getValue(AudioPresets.SPEAKERS_AROUND).applyTo(settings)
            assertEquals(15.0, settings.get(SettingKeys.TRANSIENT_CLAMP_DB))
            assertEquals(120, settings.get(SettingKeys.BANDPASS_LOW))
            // Đổi một số bất kỳ thì thành Tuỳ chỉnh.
            settings.set(SettingKeys.BANDPASS_LOW, 100)
            assertEquals(AudioPresets.CUSTOM, AudioPresets.detect(AudioPresetValues.read(settings)))
        }
    }

    @Test
    fun test_default_config_has_clamp_off_and_audio_values_at_zero() = runBlocking {
        withSettings { settings ->
            assertEquals(0.0, settings.get(SettingKeys.TRANSIENT_CLAMP_DB))
            settings.set(SettingKeys.TRANSIENT_CLAMP_DB, -4.0)
            assertEquals(0.0, settings.get(SettingKeys.TRANSIENT_CLAMP_DB))
        }
    }

    private suspend fun withSettings(block: suspend (Settings) -> Unit) {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            block(Settings.create(File(dir, "settings.preferences_pb"), scope))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}

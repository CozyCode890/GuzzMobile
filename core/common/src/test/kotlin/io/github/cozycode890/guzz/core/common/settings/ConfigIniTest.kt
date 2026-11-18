package io.github.cozycode890.guzz.core.common.settings

import io.github.cozycode890.guzz.core.common.testing.Golden
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Đọc / ghi INI như config.txt (Stage 8 F2, D112). */
class ConfigIniTest {

    @TempDir
    lateinit var dir: File

    @Serializable
    private class IniGolden(val files: Map<String, Map<String, Map<String, String>>>)

    /** Golden: cùng hai tệp, `ConfigIni.parse` phải ra đúng như `config._read_parser` của desktop (configparser). */
    @Test
    fun `đọc giống configparser của desktop`() {
        val golden = Golden.readJson<IniGolden>("config/ini_parse.json").files
        for ((name, expected) in golden) {
            val parsed = ConfigIni.parse(Golden.file("config/$name").readText(Charsets.UTF_8))
            assertEquals(expected, parsed.sections, name)
            assertTrue(parsed.problems.isEmpty(), "$name: ${parsed.problems}")
        }
    }

    @Test
    fun `nhập config txt của desktop không có lỗi, bỏ qua mục chỉ có trên desktop`() {
        val text = Golden.file("config/desktop_config.default.txt").readText(Charsets.UTF_8)
        val plan = ConfigIni.plan(ConfigIni.parse(text), SettingKeys.all, setOf(SettingKeys.OUTPUT_DIR.id))
        assertEquals(emptyList(), plan.report.errors)
        assertTrue("AUDIO.sample_rate" in plan.report.skipped)
        assertTrue("GOOGLE_AI.prompt_file" in plan.report.skipped)
        assertTrue(SettingKeys.OUTPUT_DIR.id !in plan.report.applied)
        // GUI.language của desktop (vi) được nhập; mặc định mobile là auto.
        assertEquals("vi", plan.assignments.single { it.key == SettingKeys.LANGUAGE }.value)
    }

    @Test
    fun `chuyển kiểu như load_config, sai thì báo lỗi`() {
        val text = """
            [SYSTEM]
            log_size_mb = 3.0
            old_log_count = %5
            log_level = debug
            [GOOGLE_AI]
            transcribe_mode = VERBATIM
            store_on_server = co
            delete_remote_file = maybe
            [AUDIO]
            prop_decrease = 1.5
            [QUOTAS]
            gemini-x = 1, 2, 3
        """.trimIndent()
        val plan = ConfigIni.plan(ConfigIni.parse(text), SettingKeys.all)
        val values = plan.assignments.associate { it.key.id to it.value }
        assertEquals(3, values["SYSTEM.log_size_mb"])
        assertEquals("DEBUG", values["SYSTEM.log_level"])
        assertEquals("verbatim", values["GOOGLE_AI.transcribe_mode"])
        assertEquals(true, values["GOOGLE_AI.store_on_server"])
        assertEquals("gemini-x = 1, 2, 3", values["QUOTAS.lines"])
        assertEquals(
            listOf("SYSTEM.old_log_count = %5", "AUDIO.prop_decrease = 1.5", "GOOGLE_AI.delete_remote_file = maybe"),
            plan.report.errors,
        )
    }

    @Test
    fun `xuất rồi nhập lại ra đúng giá trị cũ`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val a = Settings.create(File(dir, "a.preferences_pb"), scope)
            a.set(SettingKeys.VOCABULARY, "C#, F#")
            a.set(SettingKeys.FALLBACK_MODELS, "gemini-a,\ngemini-b")
            a.set(SettingKeys.PROP_DECREASE, 0.55)
            a.set(SettingKeys.THEME, "dark")
            a.set(SettingKeys.QUOTAS_LINES, "gemini-a = 1, 2, 3\ngemini-b = 4, 5, 6")
            a.set(SettingKeys.OUTPUT_DIR, "content://khong-xuat")
            val excluded = setOf(SettingKeys.OUTPUT_DIR.id)
            val text = ConfigIni.export(a, SettingKeys.all, excluded, listOf("thử"))
            assertTrue("output_dir" !in text, text)

            val b = Settings.create(File(dir, "b.preferences_pb"), scope)
            val plan = ConfigIni.plan(ConfigIni.parse(text), SettingKeys.all, excluded)
            assertEquals(emptyList(), plan.report.errors)
            assertEquals(emptyList(), plan.report.skipped)
            ConfigIni.apply(b, plan)
            for (key in SettingKeys.all.filter { it.id !in excluded }) {
                @Suppress("UNCHECKED_CAST")
                val k = key as SettingKey<Any>
                assertEquals(a.get(k), b.get(k), key.id)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `mục AUTOMATION xuất không có secret và source_dirs, nhập giữ hai mục này của máy`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val a = Settings.create(File(dir, "a.preferences_pb"), scope)
            a.set(SettingKeys.AUTOMATION_ENABLED, true)
            a.set(SettingKeys.AUTOMATION_SECRET, "ma-bi-mat-khong-xuat")
            a.set(SettingKeys.AUTOMATION_SOURCE_DIRS, "content://may-a/tree")
            val text = ConfigIni.export(a, SettingKeys.all, emptySet(), emptyList())
            assertTrue("[AUTOMATION]" in text && "enabled = true" in text, text)
            assertTrue("secret" !in text && "ma-bi-mat" !in text && "source_dirs" !in text, text)

            // Tệp sửa tay có secret / source_dirs: bỏ qua, không ghi đè giá trị của máy nhập.
            val b = Settings.create(File(dir, "b.preferences_pb"), scope)
            b.set(SettingKeys.AUTOMATION_SECRET, "ma-cua-may-b")
            b.set(SettingKeys.AUTOMATION_SOURCE_DIRS, "content://may-b/tree")
            val edited = text.replace("[AUTOMATION]\n", "[AUTOMATION]\nsecret = ma-khac\nsource_dirs = content://may-a/tree\n")
            assertTrue(edited != text)
            val plan = ConfigIni.plan(ConfigIni.parse(edited), SettingKeys.all, emptySet())
            assertTrue(plan.report.skipped.containsAll(listOf("AUTOMATION.secret", "AUTOMATION.source_dirs")), plan.report.toString())
            ConfigIni.apply(b, plan)
            assertEquals(true, b.get(SettingKeys.AUTOMATION_ENABLED))
            assertEquals("ma-cua-may-b", b.get(SettingKeys.AUTOMATION_SECRET))
            assertEquals("content://may-b/tree", b.get(SettingKeys.AUTOMATION_SOURCE_DIRS))
        } finally {
            scope.cancel()
        }
    }
}

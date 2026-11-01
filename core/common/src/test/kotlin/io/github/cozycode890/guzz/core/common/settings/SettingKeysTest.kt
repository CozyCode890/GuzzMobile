package io.github.cozycode890.guzz.core.common.settings

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingKeysTest {

    @Test
    fun `id có dạng SECTION chấm key như config_default_txt`() {
        val pattern = Regex("[A-Z][A-Z0-9_]*\\.[a-z][a-z0-9_]*")
        for (key in SettingKeys.all) {
            assertTrue(pattern.matches(key.id), "id sai dạng: ${key.id}")
            assertEquals("${key.section}.${key.key}", key.id)
        }
    }

    @Test
    fun `không có hai mục trùng id`() {
        val duplicates = SettingKeys.all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "trùng id: $duplicates")
    }

    @Test
    fun `log_level không phân biệt hoa thường, giá trị lạ lấy mặc định`() {
        val key = SettingKeys.LOG_LEVEL
        assertEquals("DEBUG", key.sanitize("debug"))
        assertEquals("INFO", key.sanitize(" Info "))
        assertEquals("INFO", key.sanitize("TRACE"))
        assertEquals("INFO", key.sanitize(""))
    }

    @Test
    fun `số nguyên bị chặn như config_py`() {
        assertEquals(1, SettingKeys.LOG_SIZE_MB.sanitize(0))
        assertEquals(1, SettingKeys.LOG_SIZE_MB.sanitize(-5))
        assertEquals(7, SettingKeys.LOG_SIZE_MB.sanitize(7))
        assertEquals(0, SettingKeys.OLD_LOG_COUNT.sanitize(-1))
        assertEquals(0, SettingKeys.OLD_LOG_COUNT.sanitize(0))
    }

    @Test
    fun `double NaN lấy mặc định, ngoài khoảng bị chặn`() {
        val key = DoubleSetting("TEST", "ratio", default = 0.5, min = 0.0, max = 1.0)
        assertEquals(0.5, key.sanitize(Double.NaN))
        assertEquals(1.0, key.sanitize(3.0))
        assertEquals(0.0, key.sanitize(-0.1))
    }

    @Test
    fun `viết giá trị theo cú pháp config_txt`() {
        assertEquals("true", BoolSetting("TEST", "flag", default = true).toConfigText(true))
        assertEquals("false", BoolSetting("TEST", "flag", default = true).toConfigText(false))
        assertEquals("5", SettingKeys.OLD_LOG_COUNT.toConfigText(5))
        assertEquals("INFO", SettingKeys.LOG_LEVEL.toConfigText("INFO"))
    }

    /** Đáp án lấy từ `format(x, 'g')` của Python 3.12 trong runtime desktop. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "2.0, 2",
        "0.85, 0.85",
        "-16.0, -16",
        "1e-5, 1e-05",
        "1234567.0, 1.23457e+06",
        "999999.5, 1e+06",
        "0.0001, 0.0001",
        "100000.0, 100000",
        "60.0, 60",
        "0.30000000000000004, 0.3",
        "123456.5, 123456",
        "2.5e-5, 2.5e-05",
        "-0.0, -0",
        "1e16, 1e+16",
        "0.000123456789, 0.000123457",
    )
    fun `formatPythonG khớp Python`(value: Double, expected: String) {
        assertEquals(expected, formatPythonG(value))
    }
}

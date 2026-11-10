package io.github.cozycode890.guzz.core.common.settings

import io.github.cozycode890.guzz.core.common.testing.Golden
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Mặc định của mọi [SettingKey] có `sameAsDesktop = true` phải trùng `config.default.txt` của desktop.
 *
 * Dữ liệu: `testdata/golden/config/default_config.json`, do `tools/golden/gen_config_keys.py` đọc bằng chính
 * `config._read_parser` của desktop.
 */
class DesktopDefaultsTest {

    @Serializable
    private class DesktopConfig(val sections: Map<String, Map<String, String>>)

    @TestFactory
    fun `mặc định trùng desktop`(): List<DynamicTest> {
        val desktop = Golden.readJson<DesktopConfig>("config/default_config.json").sections
        return SettingKeys.all.filter { it.sameAsDesktop }.map { key ->
            dynamicTest(key.id) {
                val section = assertNotNull(desktop[key.section], "desktop không có section [${key.section}]")
                val value = assertNotNull(section[key.key], "desktop không có ${key.id}")
                if (key is DoubleSetting) {
                    // config.default.txt viết "-16.0" còn _format_setting ghi lại thành "-16": so giá trị số.
                    assertEquals(value.trim().toDouble(), key.default, key.id)
                } else {
                    assertEquals(value, configText(key), key.id)
                }
            }
        }
    }

    private fun <T : Any> configText(key: SettingKey<T>) = key.toConfigText(key.default)
}

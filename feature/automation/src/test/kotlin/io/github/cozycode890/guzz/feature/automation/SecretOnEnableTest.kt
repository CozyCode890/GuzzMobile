package io.github.cozycode890.guzz.feature.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 9 B1 (D138): bật lệnh từ app khác mà chưa có mã bí mật thì tự tạo mã. */
class SecretOnEnableTest {
    @Test
    fun `bật khi mã trống thì tạo mã, còn lại giữ nguyên`() {
        assertEquals("moi", AutomationSettingsModel.secretOnEnable(true, "") { "moi" })
        assertEquals("moi", AutomationSettingsModel.secretOnEnable(true, "  ") { "moi" })
        assertNull(AutomationSettingsModel.secretOnEnable(true, "cu") { "moi" })
        assertNull(AutomationSettingsModel.secretOnEnable(false, "") { "moi" })
    }

    @Test
    fun `mã ngẫu nhiên 16 ký tự, không trùng`() {
        val a = AutomationSettingsModel.randomSecret()
        assertEquals(16, a.length)
        assertTrue(a.all { it in "abcdefghijkmnpqrstuvwxyz23456789" })
        assertTrue(a != AutomationSettingsModel.randomSecret())
    }
}

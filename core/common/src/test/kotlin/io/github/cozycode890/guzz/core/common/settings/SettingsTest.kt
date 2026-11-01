package io.github.cozycode890.guzz.core.common.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {

    @TempDir
    lateinit var dir: File

    private val scopes = mutableListOf<CoroutineScope>()

    private val file get() = File(dir, "settings.preferences_pb")

    private fun newScope() = CoroutineScope(Dispatchers.IO + Job()).also { scopes += it }

    private fun open() = Settings.create(file, newScope())

    /** DataStore chỉ cho một instance mở một tệp: phải huỷ scope trước khi mở lại. */
    private suspend fun closeAll() {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        scopes.clear()
    }

    @AfterEach
    fun tearDown() = runBlocking { closeAll() }

    @Test
    fun `chưa có gì thì ra mặc định`() = runBlocking {
        val settings = open()
        for (key in SettingKeys.all) {
            assertEquals(key.default, settings.get(key), key.id)
        }
    }

    @Test
    fun `set rồi get`() = runBlocking {
        val settings = open()
        settings.set(SettingKeys.LOG_LEVEL, "DEBUG")
        settings.set(SettingKeys.LOG_SIZE_MB, 8)
        assertEquals("DEBUG", settings.get(SettingKeys.LOG_LEVEL))
        assertEquals(8, settings.get(SettingKeys.LOG_SIZE_MB))
        assertEquals(5, settings.get(SettingKeys.OLD_LOG_COUNT))
    }

    @Test
    fun `đóng rồi mở lại trên cùng tệp vẫn còn giá trị`() = runBlocking {
        open().set(SettingKeys.OLD_LOG_COUNT, 9)
        closeAll()

        val reopened = open()
        assertEquals(9, reopened.get(SettingKeys.OLD_LOG_COUNT))
    }

    @Test
    fun `giá trị lưu vi phạm ràng buộc thì đọc ra giá trị đã chặn`() = runBlocking {
        // Ghi thẳng vào DataStore, giả như tệp do bản cũ hoặc do nhập cài đặt từ ngoài.
        val raw = PreferenceDataStoreFactory.create(scope = newScope(), produceFile = { file })
        raw.edit {
            it[intPreferencesKey("SYSTEM.log_size_mb")] = 0
            it[intPreferencesKey("SYSTEM.old_log_count")] = -2
            it[stringPreferencesKey("SYSTEM.log_level")] = "verbose"
        }
        val settings = Settings(raw)
        assertEquals(1, settings.get(SettingKeys.LOG_SIZE_MB))
        assertEquals(0, settings.get(SettingKeys.OLD_LOG_COUNT))
        assertEquals("INFO", settings.get(SettingKeys.LOG_LEVEL))
    }

    @Test
    fun `set giá trị sai thì lưu giá trị đã chặn`() = runBlocking {
        val settings = open()
        settings.set(SettingKeys.LOG_LEVEL, "debug")
        settings.set(SettingKeys.LOG_SIZE_MB, 0)
        assertEquals("DEBUG", settings.get(SettingKeys.LOG_LEVEL))
        assertEquals(1, settings.get(SettingKeys.LOG_SIZE_MB))
    }

    @Test
    fun `cùng id nhưng lưu khác kiểu thì coi như chưa đặt`() = runBlocking {
        val raw = PreferenceDataStoreFactory.create(scope = newScope(), produceFile = { file })
        raw.edit { it[stringPreferencesKey("SYSTEM.log_size_mb")] = "3" }
        assertEquals(2, Settings(raw).get(SettingKeys.LOG_SIZE_MB))
    }

    @Test
    fun `tệp hỏng thì mọi mục về mặc định, ghi tiếp được`() = runBlocking {
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x55))
        val settings = open()
        assertEquals(2, settings.get(SettingKeys.LOG_SIZE_MB))
        settings.set(SettingKeys.LOG_SIZE_MB, 3)
        assertEquals(3, settings.get(SettingKeys.LOG_SIZE_MB))
    }

    @Test
    fun `reset về mặc định`() = runBlocking {
        val settings = open()
        settings.set(SettingKeys.LOG_SIZE_MB, 4)
        settings.reset(SettingKeys.LOG_SIZE_MB)
        assertEquals(2, settings.get(SettingKeys.LOG_SIZE_MB))
    }

    @Test
    fun `recordAppVersion tạo tệp ở lần mở đầu, trả về bản của lần mở trước`() = runBlocking {
        val settings = open()
        assertFalse(file.exists())

        assertEquals(0, settings.recordAppVersion(1))
        assertTrue(file.exists(), "lần mở đầu phải tạo tệp cài đặt")
        assertEquals(1, settings.recordAppVersion(1))
        assertEquals(1, settings.recordAppVersion(3))
        closeAll()

        assertEquals(3, open().get(SettingKeys.LAST_VERSION_CODE))
    }

    @Test
    fun `recordAppVersion cùng bản thì không ghi lại tệp`() = runBlocking {
        val settings = open()
        settings.recordAppVersion(2)
        val written = file.lastModified()
        val bytes = file.readBytes()
        Thread.sleep(20) // để lastModified khác đi nếu tệp bị ghi lại
        settings.recordAppVersion(2)
        assertEquals(written, file.lastModified())
        assertContentEquals(bytes, file.readBytes())
    }

    @Test
    fun `flow phát giá trị mới khi đổi, không phát lại khi giá trị không đổi`() = runBlocking {
        val settings = open()
        val started = CompletableDeferred<Unit>()
        val collected = async(Dispatchers.IO) {
            settings.flow(SettingKeys.LOG_LEVEL).onEach { started.complete(Unit) }.take(2).toList()
        }
        started.await()
        settings.set(SettingKeys.LOG_SIZE_MB, 6) // mục khác đổi: log_level không được phát lại
        settings.set(SettingKeys.LOG_LEVEL, "DEBUG")
        assertEquals(listOf("INFO", "DEBUG"), collected.await())
    }
}

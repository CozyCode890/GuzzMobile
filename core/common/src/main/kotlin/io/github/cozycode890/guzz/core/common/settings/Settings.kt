package io.github.cozycode890.guzz.core.common.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Cài đặt của app trên Preferences DataStore (D16).
 *
 * Đọc ra luôn là giá trị đã qua [SettingKey.sanitize]: tệp có giá trị sai (bản cũ, nhập từ ngoài) cũng không làm hỏng
 * app, giống config.py đọc config.txt.
 */
class Settings(private val dataStore: DataStore<Preferences>) {

    suspend fun <T : Any> get(key: SettingKey<T>): T = flow(key).first()

    fun <T : Any> flow(key: SettingKey<T>): Flow<T> =
        dataStore.data.map { prefs -> read(prefs, key) }.distinctUntilChanged()

    suspend fun <T : Any> set(key: SettingKey<T>, value: T) {
        dataStore.edit { it[key.preferencesKey] = key.sanitize(value) }
    }

    suspend fun reset(key: SettingKey<*>) {
        dataStore.edit { it.remove(key.preferencesKey) }
    }

    private fun <T : Any> read(prefs: Preferences, key: SettingKey<T>): T {
        val stored = try {
            prefs[key.preferencesKey]?.let(key::sanitize)
        } catch (_: ClassCastException) {
            // Cùng id nhưng bản cũ lưu kiểu khác: coi như chưa đặt.
            null
        }
        return stored ?: key.default
    }

    companion object {
        /**
         * [file] phải có đuôi `.preferences_pb` (DataStore đòi). Mỗi tệp chỉ được một [Settings] mở cùng lúc.
         *
         * Tệp hỏng (không đọc được) thì thay bằng cài đặt trống, tức mọi mục về mặc định: mất cài đặt còn hơn app không
         * mở được.
         */
        fun create(
            file: File,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): Settings = Settings(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            ),
        )
    }
}

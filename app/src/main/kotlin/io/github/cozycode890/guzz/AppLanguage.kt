package io.github.cozycode890.guzz

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings

/**
 * Ngôn ngữ giao diện (D115) bằng per-app locale của AppCompat: chạy từ Android 8, không phải khởi động lại app
 * (AppCompat dựng lại Activity), Android 13+ còn đổi được ở Cài đặt hệ thống. AppCompat tự lưu lựa chọn (service
 * `AppLocalesMetadataHolderService` trong manifest); `GUI.language` chỉ là bản sao để xuất / nhập cài đặt.
 */
object AppLanguage {
    const val AUTO = "auto"
    private val supported = listOf("vi", "en")

    /** Ngôn ngữ đang dùng: `auto` (theo máy), `vi` hoặc `en`. */
    fun current(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return AUTO
        return locales[0]?.language?.takeIf { it in supported } ?: AUTO
    }

    /** Đổi ngôn ngữ và ghi `GUI.language`. Activity đang mở được AppCompat dựng lại. */
    suspend fun set(settings: Settings, value: String) {
        settings.set(SettingKeys.LANGUAGE, value)
        apply(value)
    }

    /** Áp [value] cho AppCompat (vd sau khi nhập cài đặt); không đổi thì không làm gì, tránh dựng lại Activity. */
    fun apply(value: String) {
        if (value == current()) return
        val locales = if (value in supported) LocaleListCompat.forLanguageTags(value) else LocaleListCompat.getEmptyLocaleList()
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** AppCompat là nguồn đúng (người dùng có thể đã đổi ở Cài đặt hệ thống): chép về `GUI.language` khi khác. */
    suspend fun syncToSettings(settings: Settings) {
        val now = current()
        if (settings.get(SettingKeys.LANGUAGE) != now) settings.set(SettingKeys.LANGUAGE, now)
    }
}

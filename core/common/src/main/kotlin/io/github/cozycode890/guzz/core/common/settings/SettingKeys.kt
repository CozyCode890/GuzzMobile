package io.github.cozycode890.guzz.core.common.settings

/**
 * Mọi mục cài đặt của app, ở một chỗ.
 *
 * Ràng buộc và mặc định lấy đúng theo `load_config` trong config.py của desktop. Mỗi stage thêm mục của mình vào đây
 * và vào [all].
 */
object SettingKeys {
    // [SYSTEM]
    val LOG_LEVEL = ChoiceSetting(
        "SYSTEM", "log_level", default = "INFO", choices = listOf("INFO", "DEBUG"), case = ChoiceSetting.Case.UPPER,
    )
    val LOG_SIZE_MB = IntSetting("SYSTEM", "log_size_mb", default = 2, min = 1)

    /** Số tệp nhật ký cũ giữ lại. Riêng 0 thì khác desktop: đầy là xoá trắng `guzz.log` (D23, `RotatingFileSink`). */
    val OLD_LOG_COUNT = IntSetting("SYSTEM", "old_log_count", default = 5, min = 0)

    val all: List<SettingKey<*>> = listOf(
        LOG_LEVEL,
        LOG_SIZE_MB,
        OLD_LOG_COUNT,
    )
}

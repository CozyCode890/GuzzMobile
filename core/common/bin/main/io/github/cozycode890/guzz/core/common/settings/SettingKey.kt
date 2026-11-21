package io.github.cozycode890.guzz.core.common.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Một mục cài đặt, có kiểu (D16).
 *
 * [id] = `SECTION.key` đúng như `config.default.txt` của desktop, để đối chiếu mặc định với desktop (golden test
 * `DesktopDefaultsTest`) và sau này nhập / xuất cài đặt.
 *
 * @property sameAsDesktop `false` khi mục chỉ có trên mobile, hoặc cố ý khác mặc định của desktop.
 */
sealed class SettingKey<T : Any>(
    val section: String,
    val key: String,
    val default: T,
    val sameAsDesktop: Boolean,
) {
    val id: String get() = "$section.$key"

    internal abstract val preferencesKey: Preferences.Key<T>

    /** Áp ràng buộc lên giá trị đọc được, như cách `config.py` đọc config.txt (chặn trên / dưới, choice lạ…). */
    abstract fun sanitize(value: T): T

    /** Viết giá trị theo cú pháp config.txt. Port của config.py: `_format_setting`. */
    abstract fun toConfigText(value: T): String

    override fun toString(): String = id
}

class BoolSetting(
    section: String,
    key: String,
    default: Boolean,
    sameAsDesktop: Boolean = true,
) : SettingKey<Boolean>(section, key, default, sameAsDesktop) {
    override val preferencesKey = booleanPreferencesKey(id)

    override fun sanitize(value: Boolean) = value

    override fun toConfigText(value: Boolean) = if (value) "true" else "false"
}

/** Số nguyên có chặn, như `max(1, system.getint(...))` của config.py. */
class IntSetting(
    section: String,
    key: String,
    default: Int,
    val min: Int = Int.MIN_VALUE,
    val max: Int = Int.MAX_VALUE,
    sameAsDesktop: Boolean = true,
) : SettingKey<Int>(section, key, default, sameAsDesktop) {
    init {
        require(min <= max) { "$id: min $min > max $max" }
        require(default in min..max) { "$id: mặc định $default nằm ngoài [$min, $max]" }
    }

    override val preferencesKey = intPreferencesKey(id)

    override fun sanitize(value: Int) = value.coerceIn(min, max)

    override fun toConfigText(value: Int) = value.toString()
}

class DoubleSetting(
    section: String,
    key: String,
    default: Double,
    val min: Double = Double.NEGATIVE_INFINITY,
    val max: Double = Double.POSITIVE_INFINITY,
    sameAsDesktop: Boolean = true,
) : SettingKey<Double>(section, key, default, sameAsDesktop) {
    init {
        require(min <= max) { "$id: min $min > max $max" }
        require(default in min..max) { "$id: mặc định $default nằm ngoài [$min, $max]" }
    }

    override val preferencesKey = doublePreferencesKey(id)

    // NaN không so sánh được nên coerceIn để lọt; config.py không bao giờ ra NaN từ một số hợp lệ.
    override fun sanitize(value: Double) = if (value.isNaN()) default else value.coerceIn(min, max)

    override fun toConfigText(value: Double) = formatPythonG(value)
}

/**
 * Chuỗi tự do. [clean] làm việc của `load_config` với mục đó (vd `.strip()`, rỗng thì lấy mặc định); mặc định giữ
 * nguyên.
 */
class StringSetting(
    section: String,
    key: String,
    default: String,
    sameAsDesktop: Boolean = true,
    private val clean: (String) -> String = { it },
) : SettingKey<String>(section, key, default, sameAsDesktop) {
    override val preferencesKey = stringPreferencesKey(id)

    override fun sanitize(value: String) = clean(value)

    override fun toConfigText(value: String) = value
}

/**
 * Chuỗi trong một tập cho phép. Port của config.py: `_Section.choice` + `_choice`.
 *
 * Như desktop, giá trị được đổi hết sang chữ hoa (`upper = True`, vd `log_level`) hoặc chữ thường trước khi so, nên
 * không phân biệt hoa thường. Khác desktop: giá trị lạ không ném lỗi mà lấy [default], vì đọc cài đặt trên điện thoại
 * không được làm app chết; desktop cũng lấy giá trị đầu khi `strict = False`.
 */
class ChoiceSetting(
    section: String,
    key: String,
    default: String,
    val choices: List<String>,
    val case: Case = Case.LOWER,
    sameAsDesktop: Boolean = true,
) : SettingKey<String>(section, key, default, sameAsDesktop) {
    enum class Case { UPPER, LOWER }

    init {
        require(choices.isNotEmpty()) { "$id: không có lựa chọn nào" }
        require(choices.all { normalize(it) == it }) { "$id: lựa chọn phải viết sẵn theo $case: $choices" }
        require(default in choices) { "$id: mặc định '$default' không nằm trong $choices" }
    }

    override val preferencesKey = stringPreferencesKey(id)

    override fun sanitize(value: String): String = normalize(value.trim()).takeIf { it in choices } ?: default

    override fun toConfigText(value: String) = value

    private fun normalize(value: String) = when (case) {
        Case.UPPER -> value.uppercase()
        Case.LOWER -> value.lowercase()
    }
}

/**
 * Port của định dạng `f"{value:g}"` của Python, dùng trong config.py: `_format_setting`.
 *
 * 6 chữ số có nghĩa, làm tròn nửa về số chẵn trên giá trị nhị phân chính xác (như CPython), bỏ số 0 thừa; dạng khoa
 * học khi số mũ < -4 hoặc ≥ 6 (`1e-05`, `1.23457e+06`).
 */
internal fun formatPythonG(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"

    val rounded = BigDecimal(value).round(MathContext(6, RoundingMode.HALF_EVEN))
    // Số mũ của chữ số đầu, tính sau khi làm tròn (999999.5 → 1e+06), đúng như quy tắc của Python.
    val exponent = rounded.precision() - rounded.scale() - 1
    if (exponent < -4 || exponent >= 6) {
        val digits = rounded.unscaledValue().abs().toString().trimEnd('0')
        val sign = if (rounded.signum() < 0) "-" else ""
        val mantissa = if (digits.length > 1) "${digits[0]}.${digits.substring(1)}" else digits
        val expSign = if (exponent < 0) "-" else "+"
        return "$sign${mantissa}e$expSign${Math.abs(exponent).toString().padStart(2, '0')}"
    }
    return rounded.stripTrailingZeros().toPlainString()
}

package io.github.cozycode890.guzz.core.pipeline

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * Port của `json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)` (dùng trong transcribe.py: `_hash`).
 *
 * Mã băm cache phải ra đúng chuỗi như Python thì mới cùng cách băm: dấu phân cách `", "` / `": "`, số thực theo `repr`
 * (`10.0`, `1e-05`, `1.5e+16`), chữ không phải ASCII giữ nguyên, khoá của map sắp theo mã ký tự. Kiểu lạ đi qua
 * `toString()` như `default=str`.
 */
fun pyJsonDumps(value: Any?): String = StringBuilder().also { it.appendPyJson(value) }.toString()

/** Port của transcribe.py: `_hash`: SHA-1 của [pyJsonDumps] trên cả bộ tham số, lấy 10 ký tự hex đầu. */
fun pyHash(vararg parts: Any?): String {
    val bytes = MessageDigest.getInstance("SHA-1").digest(pyJsonDumps(parts.toList()).toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.take(10)
}

private fun StringBuilder.appendPyJson(value: Any?) {
    when (value) {
        null -> append("null")
        is Boolean -> append(if (value) "true" else "false")
        is String -> appendPyString(value)
        is Double -> append(pyJsonFloat(value))
        is Float -> append(pyJsonFloat(value.toDouble()))
        is Int, is Long, is Short, is Byte, is java.math.BigInteger -> append(value.toString())
        is Map<*, *> -> {
            append('{')
            value.entries.map { it.key.toString() to it.value }
                .sortedWith { a, b -> comparePyStrings(a.first, b.first) }
                .forEachIndexed { i, (k, v) ->
                    if (i > 0) append(", ")
                    appendPyString(k)
                    append(": ")
                    appendPyJson(v)
                }
            append('}')
        }
        is Iterable<*> -> appendPyArray(value.toList())
        is Array<*> -> appendPyArray(value.toList())
        else -> appendPyString(value.toString())
    }
}

private fun StringBuilder.appendPyArray(items: List<Any?>) {
    append('[')
    items.forEachIndexed { i, v ->
        if (i > 0) append(", ")
        appendPyJson(v)
    }
    append(']')
}

/** `ensure_ascii=False`: chỉ thoát `"`, `\`, và ký tự điều khiển < 0x20 (`\n`… hoặc `\u00XX` chữ thường). */
private fun StringBuilder.appendPyString(s: String) {
    append('"')
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

/** Python so chuỗi theo mã ký tự (code point), Kotlin theo đơn vị UTF-16: khác nhau với ký tự ngoài BMP. */
private fun comparePyStrings(a: String, b: String): Int {
    val ia = a.codePoints().iterator()
    val ib = b.codePoints().iterator()
    while (ia.hasNext() && ib.hasNext()) {
        val d = ia.nextInt().compareTo(ib.nextInt())
        if (d != 0) return d
    }
    return ia.hasNext().compareTo(ib.hasNext())
}

/** `json.dumps` của số thực: `repr(x)`, riêng NaN / vô cực viết như JavaScript (`allow_nan=True`). */
private fun pyJsonFloat(x: Double): String = when {
    x.isNaN() -> "NaN"
    x == Double.POSITIVE_INFINITY -> "Infinity"
    x == Double.NEGATIVE_INFINITY -> "-Infinity"
    else -> pyFloatRepr(x)
}

/**
 * Port của `repr(float)`: chuỗi chữ số ngắn nhất đọc lại ra đúng số đó; dạng thường khi số mũ trong [-4, 16), không thì
 * dạng khoa học (`1e-05`, `1.5e+16`). Không dùng `Double.toString` vì bản của Android không bảo đảm ngắn nhất.
 */
fun pyFloatRepr(x: Double): String {
    if (x == 0.0) return if (1.0 / x < 0) "-0.0" else "0.0"
    val exact = BigDecimal(x)
    var r = exact
    for (p in 1..17) {
        r = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
        if (r.toDouble() == x) break
    }
    r = r.stripTrailingZeros()
    val digits = r.unscaledValue().abs().toString()
    val exp = r.precision() - r.scale() - 1
    val sign = if (x < 0) "-" else ""
    if (exp < -4 || exp >= 16) {
        val mantissa = if (digits.length > 1) "${digits[0]}.${digits.substring(1)}" else digits
        val expSign = if (exp < 0) "-" else "+"
        return "$sign${mantissa}e$expSign${Math.abs(exp).toString().padStart(2, '0')}"
    }
    val body = if (exp >= 0) {
        val intPart = digits.take(exp + 1).padEnd(exp + 1, '0')
        val frac = digits.drop(exp + 1).ifEmpty { "0" }
        "$intPart.$frac"
    } else {
        "0." + "0".repeat(-exp - 1) + digits
    }
    return sign + body
}

/**
 * Port của `os.path.splitext` trên một tên tệp (không có thư mục): `("bai.giang", ".m4a")`; dấu chấm ở đầu tên không tính
 * là đuôi (`.hidden`, `...m4a`); `"a."` → `("a", ".")`.
 */
fun pySplitext(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return name to ""
    for (i in 0 until dot) {
        if (name[i] != '.') return name.substring(0, dot) to name.substring(dot)
    }
    return name to ""
}

/** Như `str.isspace()` của Python cho một ký tự (có cả `\u001c`–`\u001f`, `\u0085`, khoảng trắng không ngắt). */
fun isPySpace(c: Char): Boolean =
    c in '\t'..'\r' || c in '\u001c'..'\u001f' || c == '\u0085' || Character.isSpaceChar(c)

/** `str.strip()` của Python. */
fun String.pyStrip(): String = trim(::isPySpace)

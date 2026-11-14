package io.github.cozycode890.guzz.core.common

import java.math.BigDecimal
import java.math.RoundingMode

/** Như `str.isspace()` của Python cho một ký tự (có cả `\u001c`–`\u001f`, `\u0085`, khoảng trắng không ngắt). */
fun isPyWhitespace(c: Char): Boolean =
    c in '\t'..'\r' || c in '\u001c'..'\u001f' || c == '\u0085' || Character.isSpaceChar(c)

/** `str.strip()` của Python. */
fun String.pyStrip(): String = trim(::isPyWhitespace)

/**
 * `str.splitlines()` của Python: tách ở `\n`, `\r`, `\r\n`, `\u000b`, `\u000c`, `\u001c`–`\u001e`, `\u0085`, ` `,
 * ` `; không có dòng rỗng cuối khi chuỗi kết thúc bằng xuống dòng. `String.lines()` của Kotlin chỉ biết `\n`, `\r`.
 */
fun String.pySplitLines(): List<String> {
    val out = ArrayList<String>()
    var start = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        val isBreak = c == '\n' || c == '\r' || c == '\u000b' || c == '\u000c' || c in '\u001c'..'\u001e' ||
            c == '\u0085' || c == ' ' || c == ' '
        if (isBreak) {
            out += substring(start, i)
            if (c == '\r' && i + 1 < length && this[i + 1] == '\n') i++
            start = i + 1
        }
        i++
    }
    if (start < length) out += substring(start)
    return out
}

/**
 * Lớp ký tự regex Java tương đương `\s` của `re` Python với chuỗi str (Unicode): đúng các ký tự [isPyWhitespace].
 * `\s` của Java mặc định chỉ có ASCII.
 */
const val PY_SPACE_CLASS = """[\t-\r\x1c-\x1f\x85\p{Z}]"""

/**
 * Định dạng số thực như `format(x, f".{digits}f")` của Python: làm tròn nửa về số chẵn trên giá trị nhị phân chính xác.
 * `String.format("%.1f")` của Java làm tròn nửa lên trên chuỗi thập phân ngắn nhất, nên lệch ở các số như 0,25 hay 0,15.
 */
fun pyFixed(x: Double, digits: Int): String {
    val s = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
    // BigDecimal không giữ dấu của số âm làm tròn về 0 (hay -0.0); Python in "-0.0".
    val negative = x < 0 || (x == 0.0 && 1.0 / x < 0)
    return if (negative && !s.startsWith("-")) "-$s" else s
}

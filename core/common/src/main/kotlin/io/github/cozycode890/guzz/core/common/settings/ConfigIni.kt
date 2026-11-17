package io.github.cozycode890.guzz.core.common.settings

/**
 * Xuất / nhập cài đặt dạng INI như `config.txt` của desktop (D112): một tệp dùng được cho cả Guzz Mobile lẫn Guzz
 * desktop. Nhập được `config.txt` của desktop: mục chỉ có trên desktop bị bỏ qua, mục sai kiểu báo trong [ImportReport].
 * Không đọc `config.txt` kiểu cũ (section / key tiếng Việt, `LEGACY_SECTIONS` của config.py).
 */
object ConfigIni {
    /** Section `[QUOTAS]` của desktop có key là tên model; mobile lưu cả section trong [SettingKeys.QUOTAS_LINES]. */
    private const val QUOTAS = "QUOTAS"

    private val SECTION = Regex("""^\[(.+)]$""")
    private val TRUE = setOf("true", "1", "yes", "co", "y", "on")
    private val FALSE = setOf("false", "0", "no", "khong", "n", "off")

    /** Một dòng không đọc được (không phải section, không có `=`, nằm ngoài section). */
    data class ParseProblem(val line: Int, val text: String)

    /** Kết quả đọc INI: section → (key viết thường → giá trị), theo thứ tự trong tệp. */
    data class Parsed(val sections: Map<String, Map<String, String>>, val problems: List<ParseProblem>)

    /**
     * Đọc INI theo đúng luật `configparser.ConfigParser(inline_comment_prefixes=("#", ";"), interpolation=None,
     * delimiters=("=",))` mà `config._read_parser` của desktop dùng: dòng bắt đầu bằng `#` / `;` là ghi chú; ghi chú cuối
     * dòng chỉ khi `#` / `;` đứng sau khoảng trắng (nên `vocabulary = C#, F#` giữ nguyên); key viết thường; dòng thụt vào
     * là phần tiếp theo của giá trị trước (nối bằng xuống dòng); bỏ BOM. Khác Python: dòng hỏng không ném lỗi mà ghi vào
     * [Parsed.problems]; section / key lặp thì lấy lần sau.
     */
    fun parse(text: String): Parsed {
        val sections = linkedMapOf<String, LinkedHashMap<String, String>>()
        val problems = mutableListOf<ParseProblem>()
        var section: LinkedHashMap<String, String>? = null
        var lastKey: String? = null
        var pendingBlank = 0
        text.removePrefix("﻿").lines().forEachIndexed { index, raw ->
            val line = stripInlineComment(raw)
            val stripped = line.trim()
            if (stripped.isEmpty()) {
                // Dòng trống giữa giá trị nhiều dòng chỉ giữ lại khi còn dòng thụt vào phía sau (empty_lines_in_values);
                // dòng ghi chú thì bỏ hẳn.
                if (raw.isBlank() && lastKey != null) pendingBlank++
                return@forEachIndexed
            }
            val indented = raw.first().isWhitespace()
            val current = section
            if (indented && current != null && lastKey != null) {
                current[lastKey!!] = current.getValue(lastKey!!) + "\n".repeat(pendingBlank + 1) + stripped
                pendingBlank = 0
                return@forEachIndexed
            }
            pendingBlank = 0
            val header = SECTION.matchEntire(stripped)
            when {
                header != null -> {
                    section = sections.getOrPut(header.groupValues[1]) { linkedMapOf() }
                    lastKey = null
                }
                current == null || '=' !in stripped -> {
                    problems += ParseProblem(index + 1, raw)
                    lastKey = null
                }
                else -> {
                    val key = stripped.substringBefore('=').trim().lowercase()
                    current[key] = stripped.substringAfter('=').trim()
                    lastKey = key
                }
            }
        }
        // Giá trị nhiều dòng của Python bỏ khoảng trắng / dòng trống ở cuối.
        return Parsed(sections.mapValues { (_, keys) -> keys.mapValues { it.value.trimEnd() } }, problems)
    }

    private fun isFullComment(raw: String): Boolean = raw.trimStart().let { it.startsWith("#") || it.startsWith(";") }

    /** Cắt ghi chú: cả dòng khi bắt đầu bằng `#` / `;`, cuối dòng khi `#` / `;` đứng sau khoảng trắng. */
    private fun stripInlineComment(raw: String): String {
        if (isFullComment(raw)) return ""
        for (i in 1 until raw.length) {
            if ((raw[i] == '#' || raw[i] == ';') && raw[i - 1].isWhitespace()) return raw.substring(0, i)
        }
        return raw
    }

    /** Một mục sẽ được đặt khi nhập. */
    data class Assignment<T : Any>(val key: SettingKey<T>, val value: T)

    /**
     * Kết quả nhập: [applied] id các mục đã đặt, [skipped] mục trong tệp mà app không dùng (chỉ có trên desktop, hoặc
     * không được nhập), [errors] mục sai kiểu / ngoài khoảng (`id = giá trị`, không đặt) và dòng hỏng.
     */
    data class ImportReport(val applied: List<String>, val skipped: List<String>, val errors: List<String>)

    /** Kế hoạch nhập, chưa đụng tới [Settings]: tách ra để test trên JVM. */
    data class ImportPlan(val assignments: List<Assignment<*>>, val report: ImportReport)

    /**
     * Đọc giá trị từ [parsed] cho các mục [keys], trừ [excluded] (id `SECTION.key`). Chuyển kiểu như `load_config` của
     * desktop: số nguyên nhận `2.0` (`int(float(s))`), bool nhận `true / 1 / yes / co / y / on` và `false / 0 / no /
     * khong / n / off`, lựa chọn không phân biệt hoa thường; ô trống của mục số = về mặc định. Khác desktop: giá trị
     * ngoài khoảng hay không đọc được thì báo lỗi, không tự kẹp / tự lấy mặc định, để người dùng thấy mục nào không nhập.
     */
    fun plan(parsed: Parsed, keys: List<SettingKey<*>>, excluded: Set<String> = emptySet()): ImportPlan {
        val assignments = mutableListOf<Assignment<*>>()
        val applied = mutableListOf<String>()
        val errors = parsed.problems.map { "#${it.line}: ${it.text.trim()}" }.toMutableList()
        val used = mutableSetOf<String>()

        for (key in keys) {
            if (key.id in excluded) continue
            if (key === SettingKeys.QUOTAS_LINES) {
                val quotas = parsed.sections[QUOTAS] ?: continue
                quotas.keys.forEach { used += "$QUOTAS.$it" }
                val lines = quotas.entries.joinToString("\n") { (model, v) -> "$model = $v" }
                assignments += Assignment(SettingKeys.QUOTAS_LINES, lines)
                applied += key.id
                continue
            }
            val raw = parsed.sections[key.section]?.get(key.key) ?: continue
            used += key.id
            val value = convert(key, raw)
            if (value == null) {
                errors += "${key.id} = $raw"
            } else {
                @Suppress("UNCHECKED_CAST")
                assignments += Assignment(key as SettingKey<Any>, value)
                applied += key.id
            }
        }
        val skipped = parsed.sections.flatMap { (s, ks) -> ks.keys.map { "$s.$it" } }.filter { it !in used }
        return ImportPlan(assignments, ImportReport(applied, skipped, errors))
    }

    /** Giá trị đã chuyển kiểu, `null` khi không dùng được. Ô trống của mục số / bool / lựa chọn = mặc định. */
    internal fun convert(key: SettingKey<*>, raw: String): Any? {
        val s = raw.trim()
        return when (key) {
            is BoolSetting -> when (s.lowercase()) {
                "" -> key.default
                in TRUE -> true
                in FALSE -> false
                else -> null
            }
            is IntSetting -> if (s.isEmpty()) key.default else s.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()?.takeIf { it in key.min..key.max }
            is DoubleSetting -> if (s.isEmpty()) key.default else s.toDoubleOrNull()?.takeIf { !it.isNaN() && it >= key.min && it <= key.max }
            is ChoiceSetting -> if (s.isEmpty()) key.default else key.sanitize(s).takeIf { it == normalizeChoice(key, s) }
            is StringSetting -> raw
        }
    }

    private fun normalizeChoice(key: ChoiceSetting, s: String) = when (key.case) {
        ChoiceSetting.Case.UPPER -> s.uppercase()
        ChoiceSetting.Case.LOWER -> s.lowercase()
    }

    /** Đặt các mục của [plan] vào [settings]. */
    suspend fun apply(settings: Settings, plan: ImportPlan) {
        for (a in plan.assignments) {
            @Suppress("UNCHECKED_CAST")
            val typed = a as Assignment<Any>
            settings.set(typed.key, typed.value)
        }
    }

    /**
     * Viết INI từ các giá trị đang dùng, theo thứ tự [keys], gom theo section; [header] thành các dòng ghi chú ở đầu.
     * Giá trị nhiều dòng viết thành dòng thụt vào (đọc lại được bằng [parse] và `configparser`). [SettingKeys.QUOTAS_LINES]
     * viết thành section `[QUOTAS]` như desktop.
     */
    fun write(values: List<Pair<SettingKey<*>, Any>>, header: List<String>): String {
        val sb = StringBuilder()
        header.forEach { sb.append("# ").append(it).append('\n') }
        var currentSection: String? = null
        for ((key, value) in values) {
            if (key === SettingKeys.QUOTAS_LINES) {
                sb.append('\n').append("[").append(QUOTAS).append("]\n")
                (value as String).lines().map { it.trim() }.filter { it.isNotEmpty() && '=' in it }.forEach { line ->
                    sb.append(line.substringBefore('=').trim()).append(" = ").append(line.substringAfter('=').trim()).append('\n')
                }
                currentSection = QUOTAS
                continue
            }
            if (key.section != currentSection) {
                sb.append('\n').append('[').append(key.section).append("]\n")
                currentSection = key.section
            }
            @Suppress("UNCHECKED_CAST")
            val text = (key as SettingKey<Any>).toConfigText(value)
            val lines = text.lines()
            sb.append(key.key).append(" = ").append(lines.first().trim()).append('\n')
            lines.drop(1).filter { it.isNotBlank() }.forEach { sb.append("    ").append(it.trim()).append('\n') }
        }
        return sb.toString()
    }

    /** Đọc các mục [keys] (trừ [excluded]) từ [settings] rồi viết INI. */
    suspend fun export(settings: Settings, keys: List<SettingKey<*>>, excluded: Set<String>, header: List<String>): String =
        write(
            keys.filter { it.id !in excluded }.map {
                @Suppress("UNCHECKED_CAST")
                it to settings.get(it as SettingKey<Any>)
            },
            header,
        )
}

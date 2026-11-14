package io.github.cozycode890.guzz.core.speaker

import io.github.cozycode890.guzz.core.common.PY_SPACE_CLASS
import io.github.cozycode890.guzz.core.common.pyFixed
import io.github.cozycode890.guzz.core.common.pyRound
import io.github.cozycode890.guzz.core.common.pySplitLines
import io.github.cozycode890.guzz.core.common.pyStrip

/**
 * Một phần tử có người nói và mốc thời gian (giây trên âm thanh ĐÃ LÀM SẠCH): [SpeakerTurn] hoặc [Span]. Các hàm làm
 * mượt / đặt tên của desktop nhận lẫn cả hai kiểu (duck typing), nên ở đây gom về một interface.
 */
interface SpeakerItem {
    val start: Double
    val end: Double
    var speaker: String?
}

/** Lượt nói của bước tách giọng. Port của speaker.py: `SpeakerTurn`. */
data class SpeakerTurn(override var start: Double, override var end: Double, override var speaker: String?) :
    SpeakerItem

/** Một từ (hoặc nhóm từ đã gộp) có mốc thời gian. Port của speaker.py: `Span`. */
data class Span(
    override var start: Double,
    override var end: Double,
    var content: String,
    override var speaker: String? = null,
    var review: Boolean = false,
) : SpeakerItem

/** Một đoạn văn của bản chép lời. [timestamp]: giây trên tệp GỐC, hoặc `null`. Port của speaker.py: `Paragraph`. */
data class Paragraph(
    val content: String,
    val speaker: String? = null,
    val timestamp: Double? = null,
    val review: Boolean = false,
)

/**
 * Phần cài đặt `[SPEAKERS]` mà speaker.py đọc từ `Config` (làm mượt, gộp lượt, đặt tên). Mặc định như desktop.
 */
data class SpeakerOptions(
    val smooth: Boolean = true,
    val sandwichMaxSec: Double = 3.0,
    val sandwichGapSec: Double = 1.0,
    val densityWindowSec: Double = 60.0,
    val densityMinSec: Double = 15.0,
    val mergeGapSec: Double = 1.0,
    val labelMain: Boolean = true,
    val mainLabel: String = "Giảng viên",
    val otherLabel: String = "Người hỏi",
    val genericLabel: String = "Người nói",
)

/**
 * Mọi thứ liên quan tới "ai nói đoạn nào". Port của speaker.py; tên hàm theo bản desktop đã refactor.
 *
 * Ba cách nhận diện dùng chung các khối ở đây:
 * - `pyannote` (trên máy + prompt): lượt nói cả buổi → làm mượt, gộp, đặt tên → cắt theo đoạn → bản đồ gửi kèm →
 *   [parsePromptParagraphs];
 * - `gemini`: từ có nhãn `spk_N` của model `*-transcribe` → đặt tên trong từng đoạn → gộp thành đoạn văn;
 * - `hybrid`: từ có mốc của model `*-transcribe` + lượt nói trên máy → [assignSpeakers] → [smooth] → đặt tên → gộp.
 *
 * Mọi mốc tính trên âm thanh đã làm sạch; chỉ lúc in ra mới đổi về tệp gốc qua `TimeMap`.
 */
object Speakers {
    const val UNKNOWN_SPEAKER = "?"

    // ------------------------------------------------------------------ lượt nói

    /** Port của speaker.py: `remap_timestamps`. Đổi mốc mỗi lượt qua [fn]; lượt bị cắt mất hết thì bỏ. */
    fun remapTimestamps(items: List<SpeakerTurn>, fn: (Double) -> Double): List<SpeakerTurn> {
        val result = ArrayList<SpeakerTurn>()
        for (l in items) {
            val a = fn(l.start)
            val b = fn(l.end)
            if (b - a > 1e-3) result += SpeakerTurn(a, b, l.speaker)
        }
        return result
    }

    /**
     * Người nói lâu nhất, hoà thì người gặp trước (như `max` trên dict của Python). Port của speaker.py:
     * `find_main_speaker`.
     */
    fun findMainSpeaker(items: List<SpeakerItem>): String? {
        val total = LinkedHashMap<String, Double>()
        for (x in items) {
            val s = x.speaker ?: continue
            total[s] = (total[s] ?: 0.0) + (x.end - x.start)
        }
        return total.entries.maxByOrNull { it.value }?.key
    }

    /** Các khoảng [đầu, cuối] liên tiếp cùng người nói. Port của speaker.py: `_blocks`. */
    private fun blocks(items: List<SpeakerItem>): List<IntArray> {
        val results = ArrayList<IntArray>()
        var i = 0
        while (i < items.size) {
            var j = i
            while (j + 1 < items.size && items[j + 1].speaker == items[i].speaker) j++
            results += intArrayOf(i, j)
            i = j + 1
        }
        return results
    }

    /**
     * Quy tắc 1: khối ngắn (< [sandwichMax]) kẹp giữa CÙNG một người, hai bên nghỉ không quá [sandwichGap] → trả về
     * người đó. Lặp tới khi không còn khối kẹp nào. Port của speaker.py: `_sandwich_rule`.
     */
    private fun sandwichRule(items: List<SpeakerItem>, sandwichMax: Double, sandwichGap: Double): Int {
        var numChanged = 0
        var changedAny = true
        while (changedAny) {
            changedAny = false
            val block = blocks(items)
            var k = 1
            while (k < block.size - 1) {
                val a = block[k][0]
                val b = block[k][1]
                val prev = items[block[k - 1][1]]
                val after = items[block[k + 1][0]]
                if (prev.speaker == after.speaker &&
                    items[b].end - items[a].start < sandwichMax &&
                    items[a].start - prev.end <= sandwichGap &&
                    after.start - items[b].end <= sandwichGap
                ) {
                    for (x in a..b) items[x].speaker = prev.speaker
                    numChanged += b - a + 1
                    changedAny = true
                    k += 2 // khối kế tiếp đã nhập vào khối trước, tính lại ở vòng sau
                } else {
                    k += 1
                }
            }
        }
        return numChanged
    }

    /**
     * Quy tắc 2: phần tử của người phụ mà trong ±[window] người phụ nói tổng cộng < [minSec] → trả về người nói chính.
     * Đo bằng thời gian nói của lượt gốc ([turns]), không bằng thời lượng các từ. Port của speaker.py: `_density_rule`.
     */
    private fun densityRule(items: List<SpeakerItem>, turns: List<SpeakerTurn>, window: Double, minSec: Double): Int {
        val primary = findMainSpeaker(turns) ?: return 0
        val minor = turns.filter { it.speaker != primary } // đã sắp theo bắt đầu
        val starts = DoubleArray(minor.size) { minor[it].start }
        val cumsum = DoubleArray(minor.size + 1)
        for ((i, l) in minor.withIndex()) cumsum[i + 1] = cumsum[i] + (l.end - l.start)
        val toChange = ArrayList<SpeakerItem>()
        for (c in items) {
            if (c.speaker == null || c.speaker == primary) continue
            val lo = bisectLeft(starts, c.start - window)
            val hi = bisectRight(starts, c.end + window)
            if (cumsum[hi] - cumsum[lo] < minSec) toChange += c
        }
        for (c in toChange) {
            c.speaker = primary
            if (c is Span) c.review = false
        }
        return toChange.size
    }

    /**
     * Sửa nhãn người nói gốc (trước khi đặt tên) theo hai quy tắc. [items] là `List<Span>` (cách Kết hợp) hoặc chính
     * `List<SpeakerTurn>` (cách trên máy + prompt, lúc đó [turns] là bản sao của lượt gốc). Quy tắc 2 chỉ chạy khi
     * [preferPrimary]. Trả về số phần tử đã đổi. Port của speaker.py: `smooth`.
     */
    fun smooth(
        items: List<SpeakerItem>,
        turns: List<SpeakerTurn>,
        sandwichMax: Double = 3.0,
        sandwichGap: Double = 1.0,
        window: Double = 60.0,
        minSec: Double = 15.0,
        preferPrimary: Boolean = true,
    ): Int {
        var n = sandwichRule(items, sandwichMax, sandwichGap)
        if (preferPrimary && minSec > 0) n += densityRule(items, turns, window, minSec)
        return n
    }

    /** Gộp hai lượt liền nhau của cùng một người cách nhau không quá [maxGap] giây. Port của speaker.py: `merge_turns`. */
    fun mergeTurns(items: List<SpeakerTurn>, maxGap: Double): List<SpeakerTurn> {
        val result = ArrayList<SpeakerTurn>()
        for (l in items) {
            val last = result.lastOrNull()
            if (last != null && last.speaker == l.speaker && l.start - last.end <= maxGap) {
                last.end = maxOf(last.end, l.end)
            } else {
                result += SpeakerTurn(l.start, l.end, l.speaker)
            }
        }
        return result
    }

    /**
     * Cách trên máy + prompt: làm mượt (nếu bật) rồi gộp lượt. Trả về (lượt mới, số lượt đã đổi nhãn). Port của
     * speaker.py: `process_turns`.
     */
    fun processTurns(rawTurns: List<SpeakerTurn>, opts: SpeakerOptions): Pair<List<SpeakerTurn>, Int> {
        val items = rawTurns.map { SpeakerTurn(it.start, it.end, it.speaker) }
        var n = 0
        if (opts.smooth) {
            n = smooth(
                items, rawTurns, opts.sandwichMaxSec, opts.sandwichGapSec, opts.densityWindowSec, opts.densityMinSec,
                preferPrimary = opts.labelMain,
            )
        }
        return mergeTurns(items, opts.mergeGapSec) to n
    }

    // ------------------------------------------------------------------ đặt tên

    /**
     * Nhãn gốc (`SPEAKER_00`, `spk_1`…) → tên để đọc. [primary] (người nói lâu nhất theo tách giọng) là tên người chính;
     * không truyền hoặc không có trong [items] thì tính trên chính [items]. Những người còn lại đánh số theo thứ tự xuất
     * hiện. Port của speaker.py: `build_name_map`.
     */
    fun buildNameMap(items: List<SpeakerItem>, opts: SpeakerOptions, primary: String? = null): LinkedHashMap<String, String> {
        val order = ArrayList<String>()
        for (x in items.sortedBy { it.start }) {
            val s = x.speaker
            if (s != null && s !in order) order += s
        }
        val nameMap = LinkedHashMap<String, String>()
        if (opts.labelMain && order.isNotEmpty()) {
            val main = if (primary in order) primary else findMainSpeaker(items)
            // Python lấy khoá None khi mọi phần tử đều None; ở đây order rỗng thì đã không vào nhánh này.
            nameMap[main!!] = opts.mainLabel
            var i = 1
            for (n in order) {
                if (n == main) continue
                nameMap[n] = "${opts.otherLabel} ${i++}"
            }
        } else {
            for ((i, n) in order.withIndex()) nameMap[n] = "${opts.genericLabel} ${i + 1}"
        }
        return nameMap
    }

    /** {tên: phút}, người nói lâu nhất trước, làm tròn 1 chữ số như Python. Port của speaker.py: `minutes_per_speaker`. */
    fun minutesPerSpeaker(items: List<SpeakerItem>, nameMap: Map<String, String>): LinkedHashMap<String, Double> {
        val total = LinkedHashMap<String, Double>()
        for (x in items) {
            val name = nameMap[x.speaker] ?: UNKNOWN_SPEAKER
            total[name] = (total[name] ?: 0.0) + (x.end - x.start)
        }
        val out = LinkedHashMap<String, Double>()
        for ((k, v) in total.entries.sortedByDescending { it.value }) out[k] = pyRound(v / 60, 1)
        return out
    }

    // ------------------------------------------------------------------ bản đồ gửi Gemini (cách trên máy + prompt)

    /**
     * Các lượt nằm trong [[start], [end]), mốc tính lại từ đầu đoạn. Bỏ mảnh ngắn hơn [minSec] giây: in ra
     * "01:21.0 - 01:21.0" chỉ làm model rối thêm. Port của speaker.py: `split_turns_by_chunk`.
     */
    fun splitTurnsByChunk(items: List<SpeakerTurn>, start: Double, end: Double, minSec: Double = 0.3): List<SpeakerTurn> {
        val result = ArrayList<SpeakerTurn>()
        for (l in items) {
            val a = maxOf(l.start, start)
            val b = minOf(l.end, end)
            if (b - a >= minSec) result += SpeakerTurn(pyRound(a - start, 2), pyRound(b - start, 2), l.speaker)
        }
        return result
    }

    /** `mm:ss.s`. Port của speaker.py: `_mm_ss`. */
    fun mmSs(sec: Double): String {
        val s = maxOf(0.0, sec)
        val minutes = Math.floor(s / 60).toLong()
        return "%02d:%s".format(java.util.Locale.ROOT, minutes, pyFixed(s % 60, 1).padStart(4, '0'))
    }

    /** Nội dung tệp bản đồ gửi cho Gemini (tiếng Anh, tên người nói giữ nguyên). Port của speaker.py: `speaker_map_text`. */
    fun speakerMapText(chunkTurns: List<SpeakerTurn>, nameMap: Map<String, String>): String {
        val lines = arrayListOf("SPEAKER MAP", "Times are mm:ss.s measured from the start of this audio clip.", "")
        if (chunkTurns.isEmpty()) lines += "(the diarization tool found no speech in this clip)"
        for (l in chunkTurns) lines += "${mmSs(l.start)} - ${mmSs(l.end)}  ${nameMap[l.speaker] ?: UNKNOWN_SPEAKER}"
        return lines.joinToString("\n") + "\n"
    }

    /** Tên các người nói có trong đoạn, người nói lâu nhất trước. Port của speaker.py: `speakers_in_chunk`. */
    fun speakersInChunk(chunkTurns: List<SpeakerTurn>, nameMap: Map<String, String>): List<String> =
        if (chunkTurns.isEmpty()) emptyList() else minutesPerSpeaker(chunkTurns, nameMap).keys.toList()

    // ------------------------------------------------------------------ đọc bản chép lời của model đa năng

    private const val S = PY_SPACE_CLASS
    // (?U): `\d` của Python với str nhận cả chữ số Unicode.
    private val LEADING_TIMESTAMP = Regex(
        "(?U)^$S*[\\[(]$S*(?:(\\d{1,2}):)?(\\d{1,3}):(\\d{2})(?:[.,]\\d+)?$S*[\\])]$S*",
    )
    private val SPEAKER_PREFIX = Regex("^$S*\\**$S*([^:\\n]{1,40}?)$S*\\**$S*:$S*\\**$S*")

    /** `'**Giảng viên:** abc'` → (`Giảng viên`, `abc`) nếu tên có trong [names] (khoá viết thường). Port của `_split_speaker`. */
    private fun splitSpeaker(line: String, names: Map<String, String>): Pair<String?, String> {
        val m = SPEAKER_PREFIX.find(line)
        if (m != null) {
            val name = names[m.groupValues[1].pyStrip().trim('*').pyStrip().lowercase()]
            if (name != null) return name to line.substring(m.range.last + 1).pyStrip()
        }
        return null to line.pyStrip()
    }

    /**
     * Tách bản chép lời có tên người nói thành các [Paragraph], mỗi dòng không rỗng là một đoạn. Mốc `[mm:ss]` đầu dòng
     * (tính từ đầu đoạn âm thanh) đổi thành mốc tệp gốc: `toOriginal(chunkStart + mốc)`. Dòng không ghi tên thì thuộc
     * người nói dòng trước; dòng chỉ có "Tên:" thì tên (và mốc) áp cho dòng kế tiếp. Chỉ nhận tên có trong [names], để
     * "Ví dụ: ..." không bị coi là người nói. Port của speaker.py: `parse_prompt_paragraphs`.
     */
    fun parsePromptParagraphs(
        text: String,
        names: List<String>,
        chunkStart: Double,
        toOriginal: (Double) -> Double,
    ): List<Paragraph> {
        val lookup = LinkedHashMap<String, String>()
        for (t in names) lookup[t.lowercase()] = t
        val result = ArrayList<Paragraph>()
        var speakerName: String? = null
        var pendingTs: Double? = null
        for (raw in text.pySplitLines()) {
            if (raw.pyStrip().isEmpty()) continue
            var line = raw
            var timestamp: Double? = null
            val m = LEADING_TIMESTAMP.find(line)
            if (m != null) {
                val h = m.groups[1]?.value?.toInt() ?: 0
                val sec = h * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
                timestamp = toOriginal(chunkStart + sec)
                line = line.substring(m.range.last + 1)
            }
            val (name, content) = splitSpeaker(line, lookup)
            if (name != null) speakerName = name
            if (timestamp == null) timestamp = pendingTs
            pendingTs = null
            if (content.isEmpty()) {
                pendingTs = timestamp
                continue
            }
            result += Paragraph(content, speakerName, timestamp)
        }
        return result
    }

    // ------------------------------------------------------------------ từ có mốc (cách gemini và hybrid)

    /** Một từ của `extract_words`: `tu`, `bat_dau`, `ket_thuc`, `nguoi_noi` (có thể `null`). */
    data class Word(val text: String, val start: Double, val end: Double, val speaker: String? = null)

    /** Từ → [Span], cộng thêm [offset] (mốc đầu đoạn trên âm thanh đã cắt). Port của speaker.py: `words_to_spans`. */
    fun wordsToSpans(words: List<Word>, offset: Double = 0.0): List<Span> =
        words.map { Span(it.start + offset, it.end + offset, it.text, it.speaker) }

    /**
     * Mỗi câu (từ) thuộc về người nói có THỜI GIAN TRÙNG nhiều nhất với nó. Không trùng ai thì lấy lượt gần nhất trong
     * [nearGap] giây, vẫn không có thì `null`. Câu dài từ [reviewMinSec] giây mà người thứ hai chiếm ≥ [reviewThreshold]
     * được đánh dấu xem lại. Sửa trực tiếp và trả lại [spans]. Port của speaker.py: `assign_speakers`.
     */
    fun assignSpeakers(
        spans: List<Span>,
        turns: List<SpeakerTurn>,
        nearGap: Double = 1.0,
        reviewThreshold: Double = 0.4,
        reviewMinSec: Double = 1.5,
    ): List<Span> {
        // Lượt nói không chồng lấn, nên kết thúc cũng tăng dần.
        val ends = DoubleArray(turns.size) { turns[it].end }
        for (c in spans) {
            val overlap = LinkedHashMap<String?, Double>()
            val i = bisectRight(ends, c.start)
            var j = i
            while (j < turns.size && turns[j].start < c.end) {
                val l = turns[j]
                val len = minOf(c.end, l.end) - maxOf(c.start, l.start)
                if (len > 0) overlap[l.speaker] = (overlap[l.speaker] ?: 0.0) + len
                j++
            }
            if (overlap.isNotEmpty()) {
                val ranked = overlap.entries.sortedByDescending { it.value }
                c.speaker = ranked[0].key
                val spanLen = maxOf(c.end - c.start, 1e-6)
                c.review = ranked.size > 1 && spanLen >= reviewMinSec && ranked[1].value / spanLen >= reviewThreshold
                continue
            }
            var nearest: String? = null
            var distance = nearGap
            for (k in intArrayOf(i - 1, i)) {
                if (k in turns.indices) {
                    val l = turns[k]
                    val kc = maxOf(l.start - c.end, c.start - l.end, 0.0)
                    if (kc <= distance) {
                        nearest = l.speaker
                        distance = kc
                    }
                }
            }
            c.speaker = nearest
        }
        return spans
    }

    private val SPACE_BEFORE_PUNCT = Regex("$S+([,.;:!?%…)\\]}])")
    private val SPACE_AFTER_OPEN = Regex("([(\\[{])$S+")

    /** Nối hai mẩu chữ, bỏ khoảng trắng trước dấu câu và sau ngoặc mở. Port của speaker.py: `join_words`. */
    fun joinWords(a: String, b: String): String {
        val s = if (a.isNotEmpty()) "$a $b" else b
        return SPACE_AFTER_OPEN.replace(SPACE_BEFORE_PUNCT.replace(s, "$1"), "$1")
    }

    /** Gộp các câu (từ) liền nhau của cùng một người thành một đoạn. Port của speaker.py: `merge_spans`. */
    fun mergeSpans(spans: List<Span>, maxSeconds: Double = 60.0, silenceGapSec: Double = 3.0): List<Span> {
        val results = ArrayList<Span>()
        for (c in spans) {
            val prev = results.lastOrNull()
            if (prev != null && prev.speaker == c.speaker && c.start - prev.end <= silenceGapSec &&
                (maxSeconds <= 0 || c.end - prev.start <= maxSeconds)
            ) {
                prev.end = maxOf(prev.end, c.end)
                prev.content = joinWords(prev.content, c.content)
                prev.review = prev.review || c.review
            } else {
                results += Span(c.start, c.end, joinWords("", c.content), c.speaker, c.review)
            }
        }
        return results
    }

    /** Port của speaker.py: `rename_speakers`. */
    fun renameSpeakers(items: List<SpeakerItem>, nameMap: Map<String, String>) {
        for (x in items) x.speaker = x.speaker?.let { nameMap[it] ?: UNKNOWN_SPEAKER } ?: UNKNOWN_SPEAKER
    }

    /** Port của speaker.py: `spans_to_paragraphs`. */
    fun spansToParagraphs(spans: List<Span>, toOriginal: (Double) -> Double): List<Paragraph> =
        spans.map { Paragraph(it.content, it.speaker, toOriginal(it.start), it.review) }

    // ------------------------------------------------------------------ in ra

    /** Port của speaker.py: `hh_mm_ss`. */
    fun hhMmSs(sec: Double): String {
        val s = maxOf(0.0, sec).toLong()
        return "%02d:%02d:%02d".format(java.util.Locale.ROOT, s / 3600, s % 3600 / 60, s % 60)
    }

    // Cặp ngoặc rỗng ở đầu mẫu thì bỏ cả khoảng trắng phía sau, ở giữa thì bỏ khoảng trắng phía trước.
    private val EMPTY_BRACKETS = Regex("^$S*[\\[(<{]$S*[\\])>}]$S*|$S*[\\[(<{]$S*[\\])>}]")

    /**
     * [template]: mẫu đoạn văn, biến `{time}` `{speaker}` `{text}` (tên cũ `{moc}` `{nguoi}` `{noi_dung}` vẫn dùng được).
     * Port của speaker.py: `format_paragraph`.
     */
    fun formatParagraph(d: Paragraph, template: String, timestamps: Boolean, markReview: Boolean): String {
        val ts = if (timestamps && d.timestamp != null) hhMmSs(d.timestamp) else ""
        val content = (if (markReview && d.review) "[?] " else "") + d.content
        if (d.speaker.isNullOrEmpty()) return if (ts.isNotEmpty()) "[$ts] $content" else content
        var s = pyFormat(
            template,
            mapOf("time" to ts, "speaker" to d.speaker, "text" to content, "moc" to ts, "nguoi" to d.speaker,
                "noi_dung" to content),
        )
        if (ts.isEmpty()) s = EMPTY_BRACKETS.replaceFirst(s, "")
        return s.pyStrip()
    }

    /**
     * Phần `str.format(**kw)` của Python mà mẫu đoạn văn dùng: `{tên}`, `{{` / `}}`. Tên lạ, chỉ mục, định dạng (`{x:>5}`)
     * hay ngoặc lẻ thì ném [IllegalArgumentException], như Python ném `KeyError` / `ValueError`.
     */
    fun pyFormat(template: String, values: Map<String, String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                c == '{' && i + 1 < template.length && template[i + 1] == '{' -> { sb.append('{'); i += 2 }
                c == '}' && i + 1 < template.length && template[i + 1] == '}' -> { sb.append('}'); i += 2 }
                c == '{' -> {
                    val close = template.indexOf('}', i + 1)
                    require(close > 0) { "Mẫu đoạn văn thiếu '}': $template" }
                    val key = template.substring(i + 1, close)
                    sb.append(values[key] ?: throw IllegalArgumentException("Mẫu đoạn văn có biến lạ {$key}"))
                    i = close + 1
                }
                c == '}' -> throw IllegalArgumentException("Mẫu đoạn văn có '}' lẻ: $template")
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ bisect

    internal fun bisectLeft(a: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    internal fun bisectRight(a: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (x < a[mid]) hi = mid else lo = mid + 1
        }
        return lo
    }
}

package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.audio.ChunkInfo
import io.github.cozycode890.guzz.core.common.audio.TimeMap
import io.github.cozycode890.guzz.core.common.pyFormatG
import io.github.cozycode890.guzz.core.common.pyRound
import io.github.cozycode890.guzz.core.common.pyStrip
import io.github.cozycode890.guzz.core.gemini.DiarizationMethod
import io.github.cozycode890.guzz.core.gemini.Word
import io.github.cozycode890.guzz.core.speaker.Paragraph
import io.github.cozycode890.guzz.core.speaker.SpeakerTurn
import io.github.cozycode890.guzz.core.speaker.Speakers
import io.github.cozycode890.guzz.core.speaker.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Phần người nói của transcribe.py: ghép bản chép lời ba cách, phần đầu có phút nói, bản đồ người nói cả bài. */
object SpeakerTranscript {
    /** Port của transcribe.py: `TIMESTAMP_INSTRUCTIONS`. */
    const val TIMESTAMP_INSTRUCTIONS = "- Begin every paragraph with its start time in square brackets, formatted mm:ss " +
        "and measured from the start of this audio clip (use the times in the speaker map), for example " +
        "\"[03:15] {name}: ...\"."

    /** Biến trong prompt người nói: tên người nói trong đoạn (`{danh_sach_nguoi_noi}` là tên cũ). */
    val SPEAKERS_PLACEHOLDERS = listOf("{speakers}", "{danh_sach_nguoi_noi}")

    /** Prompt của một đoạn (cách `pyannote`): thay tên người nói, thêm yêu cầu mốc thời gian. */
    fun chunkPrompt(prompt: String, names: List<String>, timestamps: Boolean): String {
        var p = prompt
        for (ph in SPEAKERS_PLACEHOLDERS) p = p.replace(ph, names.joinToString(", "))
        if (timestamps) p += "\n" + TIMESTAMP_INSTRUCTIONS.replace("{name}", names[0])
        return p
    }

    /** Từ của `extract_words` → JSON lưu trong `doan_NNN.<mã>.json` (`tu`, `bat_dau`, `ket_thuc`, `nguoi_noi`). */
    fun wordsToJson(words: List<Word>): JsonArray = buildJsonArray {
        for (w in words) add(buildJsonObject {
            put("tu", w.text)
            put("nguoi_noi", w.speaker)
            put("bat_dau", w.start)
            put("ket_thuc", w.end)
        })
    }

    fun wordsFromJson(e: JsonElement): List<Speakers.Word> = (e as? JsonArray).orEmpty().map {
        val o = it.jsonObject
        Speakers.Word(
            o["tu"]!!.jsonPrimitive.content,
            o["bat_dau"]!!.jsonPrimitive.double,
            o["ket_thuc"]!!.jsonPrimitive.double,
            o["nguoi_noi"]?.takeUnless { x -> x is JsonNull }?.jsonPrimitive?.content,
        )
    }

    /** Port của transcribe.py: `_unnamed_paragraphs`. */
    private fun unnamedParagraphs(text: String): List<Paragraph> =
        text.split("\n\n").map { it.pyStrip() }.filter { it.isNotEmpty() }.map { Paragraph(it) }

    /**
     * Thân bản chép lời và số phút nói theo tên. Port của transcribe.py: `assemble_transcript` (đủ 3 cách; [method]
     * `null` = tắt nhận diện, như [Transcript.assemble]).
     *
     * @param turns lượt nói trên âm thanh đã làm sạch (cách `pyannote`: đã làm mượt và gộp; `hybrid`: lượt gốc).
     */
    fun assemble(
        method: String?,
        cfg: SpeakerConfig,
        markChunks: Boolean,
        chunks: List<ChunkInfo>,
        results: List<ChunkResult>,
        turns: List<SpeakerTurn>,
        nameMapIn: Map<String, String>,
        timeMap: TimeMap,
    ): Pair<String, Map<String, Double>> {
        val total = chunks.size
        val parts = List(chunks.size) { ArrayList<Any>() }
        var minutes: Map<String, Double> = emptyMap()
        val opts = cfg.options
        when (method) {
            null -> results.forEachIndexed { i, r -> if (r.text.isNotEmpty()) parts[i].add(r.text) }
            DiarizationMethod.PYANNOTE -> {
                val names = nameMapIn.values.distinct()
                for ((i, d) in chunks.withIndex()) {
                    val r = results[i]
                    parts[i].addAll(Speakers.parsePromptParagraphs(r.text, names, d.startSeconds, timeMap::toOriginal)
                        .ifEmpty { unnamedParagraphs(r.text) })
                }
                minutes = Speakers.minutesPerSpeaker(turns, nameMapIn)
            }
            DiarizationMethod.GEMINI -> {
                // Nhãn spk_1, spk_2... chỉ đúng trong từng đoạn: đặt tên riêng từng đoạn.
                val totalTime = LinkedHashMap<String?, Double>()
                for ((i, d) in chunks.withIndex()) {
                    val r = results[i]
                    val spans = Speakers.wordsToSpans(wordsFromJson(r.words), d.startSeconds)
                    if (spans.isEmpty()) {
                        parts[i].addAll(unnamedParagraphs(r.text))
                        continue
                    }
                    if (spans.any { !it.speaker.isNullOrEmpty() }) {
                        Speakers.renameSpeakers(spans, Speakers.buildNameMap(spans, opts))
                    }
                    for (c in spans) totalTime[c.speaker] = (totalTime[c.speaker] ?: 0.0) + (c.end - c.start)
                    val merged = Speakers.mergeSpans(spans, cfg.maxParagraphSec, cfg.paragraphGapSec)
                    parts[i].addAll(Speakers.spansToParagraphs(merged, timeMap::toOriginal))
                }
                val m = LinkedHashMap<String, Double>()
                for ((k, v) in totalTime.entries.sortedByDescending { it.value }) {
                    if (!k.isNullOrEmpty()) m[k] = pyRound(v / 60, 1)
                }
                minutes = m
            }
            DiarizationMethod.HYBRID -> {
                val spans = ArrayList<Span>()
                for ((i, d) in chunks.withIndex()) {
                    val r = results[i]
                    val chunkSpans = Speakers.wordsToSpans(wordsFromJson(r.words).map { it.copy(speaker = null) }, d.startSeconds)
                    if (chunkSpans.isEmpty()) parts[i].addAll(unnamedParagraphs(r.text))
                    spans += chunkSpans
                }
                if (spans.isNotEmpty()) {
                    Speakers.assignSpeakers(spans, turns)
                    if (opts.smooth) {
                        Speakers.smooth(
                            spans, turns, opts.sandwichMaxSec, opts.sandwichGapSec, opts.densityWindowSec,
                            opts.densityMinSec, preferPrimary = opts.labelMain,
                        )
                    }
                    val nameMap = Speakers.buildNameMap(spans, opts, primary = Speakers.findMainSpeaker(turns))
                    minutes = Speakers.minutesPerSpeaker(turns, nameMap)
                    Speakers.renameSpeakers(spans, nameMap)
                    val starts = DoubleArray(chunks.size) { chunks[it].startSeconds }
                    for (c in Speakers.mergeSpans(spans, cfg.maxParagraphSec, cfg.paragraphGapSec)) {
                        val i = maxOf(0, bisectRight(starts, c.start) - 1)
                        parts[i].addAll(Speakers.spansToParagraphs(listOf(c), timeMap::toOriginal))
                    }
                }
            }
            else -> error("Cách nhận diện lạ: $method")
        }
        val blocks = ArrayList<String>()
        for ((i, items) in parts.withIndex()) {
            if (markChunks) blocks += "----- Đoạn ${i + 1}/$total -----"
            for (x in items) {
                val line = if (x is String) x else Speakers.formatParagraph(
                    x as Paragraph, cfg.paragraphTemplate, cfg.timestamps, cfg.markReview,
                )
                if (line.isNotEmpty()) blocks += line
            }
        }
        return blocks.joinToString("\n\n") to minutes
    }

    /**
     * Dòng "Người nói" của phần đầu: `Người nói: Giảng viên (52.3 phút), Người hỏi 1 (1.2 phút)`. Port của
     * transcribe.py: `format_header` (phần `minutes`).
     */
    fun minutesLine(minutes: Map<String, Double>): String =
        "Người nói: " + minutes.entries.joinToString(", ") { (k, v) -> "$k (${pyFormatG(v)} phút)" }

    /** `Giảng viên 52.3 phút, Người hỏi 1 1.2 phút` cho nhật ký. */
    fun minutesLog(minutes: Map<String, Double>): String =
        minutes.entries.joinToString(", ") { (k, v) -> "$k ${pyFormatG(v)} phút" }

    /** Bản đồ người nói cả bài (`<tên>.nguoi_noi.txt`), mốc tệp gốc. Port của transcribe.py: `full_speaker_map`. */
    fun fullSpeakerMap(turns: List<SpeakerTurn>, nameMap: Map<String, String>, timeMap: TimeMap): String =
        turns.joinToString("\n") {
            "[${Speakers.hhMmSs(timeMap.toOriginal(it.start))} - ${Speakers.hhMmSs(timeMap.toOriginal(it.end))}] " +
                (nameMap[it.speaker] ?: it.speaker.orEmpty())
        } + "\n"

    private fun bisectRight(a: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (x < a[mid]) hi = mid else lo = mid + 1
        }
        return lo
    }
}

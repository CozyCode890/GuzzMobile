package io.github.cozycode890.guzz.feature.automation

import io.github.cozycode890.guzz.core.common.settings.AudioPresets
import io.github.cozycode890.guzz.core.pipeline.ItemOverrides
import io.github.cozycode890.guzz.feature.automation.GuzzIntents as G

/** Một lệnh đã đọc xong từ intent. */
sealed interface Command {
    val requestId: String?

    /** TRANSCRIBE: [paths] đường dẫn, [uris] content:// URI (từ extra `uri`, data hoặc ClipData). */
    data class Transcribe(
        val paths: List<String>,
        val uris: List<String>,
        override val requestId: String?,
        val start: Boolean,
        val overrides: ItemOverrides?,
    ) : Command

    data class Start(override val requestId: String?) : Command
    data class Stop(override val requestId: String?) : Command
    data class Status(override val requestId: String?) : Command
}

/** Lỗi đọc lệnh: extra thiếu / sai kiểu / giá trị lạ. [message] có tên extra. */
class BadExtraException(message: String) : Exception(message)

/**
 * Đọc lệnh từ extra của intent (Stage 6 B1). Nhận `Map` thay cho `Bundle` để test trên PC. MacroDroid hay gửi mọi extra
 * dạng chuỗi, nên số và boolean nhận cả dạng chuỗi (`"3"`, `"true"`, `"on"`); `paths` là mảng chuỗi, danh sách hoặc một
 * chuỗi nhiều dòng (mỗi dòng một đường dẫn).
 */
object ExtrasReader {
    /** Mã yêu cầu thô (để trả lời cả khi lệnh sai). */
    fun requestId(extras: Map<String, Any?>): String? = stringOrNull(extras, G.EXTRA_REQUEST_ID)

    fun secret(extras: Map<String, Any?>): String? = stringOrNull(extras, G.EXTRA_SECRET)

    /**
     * @param dataUris URI ở `Intent.data` và ClipData (app gửi kèm cờ cấp quyền).
     * @throws BadExtraException lệnh lạ, thiếu tệp, giá trị không hợp lệ.
     */
    fun read(action: String?, extras: Map<String, Any?>, dataUris: List<String> = emptyList()): Command {
        val id = requestId(extras)
        return when (action) {
            G.ACTION_TRANSCRIBE -> readTranscribe(extras, dataUris, id)
            G.ACTION_START -> Command.Start(id)
            G.ACTION_STOP -> Command.Stop(id)
            G.ACTION_STATUS -> Command.Status(id)
            else -> throw BadExtraException("lệnh lạ: ${action ?: "(không có action)"}")
        }
    }

    private fun readTranscribe(extras: Map<String, Any?>, dataUris: List<String>, id: String?): Command.Transcribe {
        val sources = LinkedHashSet<String>()
        stringOrNull(extras, G.EXTRA_PATH)?.let(sources::add)
        sources += stringList(extras, G.EXTRA_PATHS)
        stringOrNull(extras, G.EXTRA_URI)?.let(sources::add)
        sources += dataUris.map(String::trim).filter(String::isNotEmpty)
        if (sources.isEmpty()) throw BadExtraException("thiếu tệp: cần extra `path`, `paths` hoặc `uri`")
        // file:// là đường dẫn; content:// là URI; còn lại phải là đường dẫn tuyệt đối.
        val paths = ArrayList<String>()
        val uris = ArrayList<String>()
        for (s in sources) when {
            s.startsWith("file://") -> paths += decodeFileUri(s.removePrefix("file://"))
            s.startsWith("content://") -> uris += s
            s.startsWith("/") -> paths += s
            else -> throw BadExtraException("`$s` không phải đường dẫn tuyệt đối hay content:// URI")
        }
        return Command.Transcribe(
            paths = paths.distinct(), uris = uris.distinct(), requestId = id,
            start = bool(extras, G.EXTRA_START) ?: true, overrides = overrides(extras),
        )
    }

    private fun overrides(extras: Map<String, Any?>): ItemOverrides? {
        val speakers = stringOrNull(extras, G.EXTRA_SPEAKERS)?.lowercase()?.let { v ->
            when (v) {
                "off", "none", "false" -> ItemOverrides.SPEAKERS_OFF
                // "Tách giọng trên máy + Prompt": giá trị cài đặt vẫn là `pyannote` như desktop (D71).
                "device", "pyannote" -> "pyannote"
                "gemini", "hybrid" -> v
                else -> throw BadExtraException("`speakers` = `$v`: chỉ nhận off, device, gemini, hybrid")
            }
        }
        val num = int(extras, G.EXTRA_NUM_SPEAKERS)?.also {
            if (it !in 0..MAX_SPEAKERS) throw BadExtraException("`num_speakers` = $it: phải từ 0 tới $MAX_SPEAKERS (0 = tự đoán)")
        }
        val preset = stringOrNull(extras, G.EXTRA_PRESET)?.lowercase()?.also {
            if (it !in AudioPresets.all) {
                throw BadExtraException("`preset` = `$it`: chỉ nhận ${AudioPresets.all.keys.joinToString(", ")}")
            }
        }
        val outputDir = stringOrNull(extras, G.EXTRA_OUTPUT_DIR)?.also {
            if (it != ItemOverrides.OUTPUT_BESIDE_SOURCE && !it.startsWith("/")) {
                throw BadExtraException("`output_dir` = `$it`: chỉ nhận `source` hoặc đường dẫn tuyệt đối")
            }
        }
        val o = ItemOverrides(
            speakers = speakers, numSpeakers = num, preset = preset, denoise = bool(extras, G.EXTRA_DENOISE),
            outputDir = outputDir, fakeGemini = bool(extras, G.EXTRA_FAKE_GEMINI),
        )
        return o.takeUnless { it.isEmpty }
    }

    // ------------------------------------------------------------------ kiểu

    /** Chuỗi đã cắt khoảng trắng; rỗng = không có. Số được đổi ra chuỗi (vd `request_id` gửi dạng số). */
    fun stringOrNull(extras: Map<String, Any?>, key: String): String? = when (val v = extras[key]) {
        null -> null
        is CharSequence -> v.toString().trim().takeIf { it.isNotEmpty() }
        is Number, is Boolean -> v.toString()
        else -> if (v.javaClass.name.endsWith("Uri")) v.toString() else throw BadExtraException("`$key` phải là chuỗi")
    }

    /** `paths`: mảng chuỗi, danh sách, hoặc một chuỗi nhiều dòng. Dòng trống bỏ qua. */
    fun stringList(extras: Map<String, Any?>, key: String): List<String> {
        val raw: List<Any?> = when (val v = extras[key]) {
            null -> return emptyList()
            is Array<*> -> v.toList()
            is Iterable<*> -> v.toList()
            is CharSequence -> v.lines()
            else -> throw BadExtraException("`$key` phải là mảng chuỗi hoặc chuỗi nhiều dòng")
        }
        return raw.map { (it ?: "").toString().trim() }.filter(String::isNotEmpty)
    }

    fun bool(extras: Map<String, Any?>, key: String): Boolean? = when (val v = extras[key]) {
        null -> null
        is Boolean -> v
        is Number -> when (v.toLong()) {
            1L -> true
            0L -> false
            else -> throw BadExtraException("`$key` = $v: chỉ nhận true / false")
        }
        is CharSequence -> when (v.toString().trim().lowercase()) {
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no" -> false
            "" -> null
            else -> throw BadExtraException("`$key` = `$v`: chỉ nhận true / false")
        }
        else -> throw BadExtraException("`$key` phải là true / false")
    }

    fun int(extras: Map<String, Any?>, key: String): Int? = when (val v = extras[key]) {
        null -> null
        is Int, is Short, is Byte -> (v as Number).toInt()
        is Long -> v.toInt().takeIf { it.toLong() == v } ?: throw BadExtraException("`$key` = $v: quá lớn")
        is Double, is Float -> (v as Number).toDouble().let { d ->
            if (d % 1.0 != 0.0) throw BadExtraException("`$key` = $v: phải là số nguyên")
            d.toInt()
        }
        is CharSequence -> v.toString().trim().let { s ->
            if (s.isEmpty()) null else s.toIntOrNull() ?: throw BadExtraException("`$key` = `$s`: phải là số nguyên")
        }
        else -> throw BadExtraException("`$key` phải là số nguyên")
    }

    /** Giải mã %XX của `file://` (không dùng `Uri` của Android để test được trên PC). */
    private fun decodeFileUri(s: String): String = java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private const val MAX_SPEAKERS = 50
}

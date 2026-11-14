package io.github.cozycode890.guzz.core.speaker

import io.github.cozycode890.guzz.core.common.pyRound
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Một lượt trong `doan` của `nguoi_noi.<mã>.json`. */
@Serializable
data class TurnJson(
    @SerialName("bat_dau") val start: Double,
    @SerialName("ket_thuc") val end: Double,
    @SerialName("nguoi_noi") val speaker: String,
)

/**
 * Kết quả tách giọng của cả bài, cùng khoá JSON với `diarization_worker.py` của desktop (`model`, `thiet_bi`,
 * `thoi_luong_giay`, `giay_xu_ly`, `so_nguoi`, `doan`), để dùng lại dữ liệu và test của desktop. `file_wav` và
 * `vram_dinh_mb` của desktop không có ý nghĩa trên máy nên bỏ; [extra] giữ thông tin riêng của sherpa-onnx (cấu hình,
 * nhật ký nối phần).
 */
@Serializable
data class DiarizationJson(
    val model: String,
    @SerialName("thiet_bi") val device: String = "cpu",
    @SerialName("thoi_luong_giay") val durationSec: Double,
    @SerialName("giay_xu_ly") val processingSec: Double,
    @SerialName("so_nguoi") val numSpeakers: Int,
    @SerialName("doan") val turns: List<TurnJson>,
    val sherpa: JsonElement? = null,
) {
    fun toTurns(): List<SpeakerTurn> = turns.map { SpeakerTurn(it.start, it.end, it.speaker) }.sortedBy { it.start }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = " "; encodeDefaults = true }

        /**
         * Đánh số lại nhãn theo thứ tự xuất hiện (`SPEAKER_00`…) như pyannote và `run_sherpa.py`, làm tròn mốc 3 chữ số.
         * [turns] đã sắp theo bắt đầu.
         */
        fun build(
            model: String,
            durationSec: Double,
            processingSec: Double,
            turns: List<Triple<Double, Double, Int>>,
            sherpa: JsonElement? = null,
        ): DiarizationJson {
            val order = LinkedHashMap<Int, Int>()
            for ((_, _, s) in turns) order.getOrPut(s) { order.size }
            return DiarizationJson(
                model = model,
                durationSec = pyRound(durationSec, 2),
                processingSec = pyRound(processingSec, 1),
                numSpeakers = order.size,
                turns = turns.map { (a, b, s) ->
                    TurnJson(pyRound(a, 3), pyRound(b, 3), "SPEAKER_%02d".format(java.util.Locale.ROOT, order[s]))
                },
                sherpa = sherpa,
            )
        }

        fun decode(text: String): DiarizationJson = json.decodeFromString(serializer(), text)

        fun encode(d: DiarizationJson): String = json.encodeToString(serializer(), d)

        /** Ghi `.tmp` rồi đổi tên, như `write_json` của `diarization_worker.py`: chết giữa chừng không để lại tệp dở. */
        fun write(file: File, d: DiarizationJson) {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encode(d), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "Không đổi tên được $tmp → $file" }
            }
        }
    }
}

/**
 * Đọc lượt nói từ JSON của bước tách giọng (`doan`: `bat_dau`, `ket_thuc`, `nguoi_noi`), sắp theo bắt đầu. Như desktop,
 * mốc nhận cả số lẫn chuỗi số, nhãn đổi sang chuỗi; trường khác bỏ qua. Port của speaker.py: `load_speaker_turns`.
 */
fun loadSpeakerTurns(file: File): List<SpeakerTurn> = parseSpeakerTurns(file.readText(Charsets.UTF_8))

/** Như [loadSpeakerTurns] trên nội dung JSON. */
fun parseSpeakerTurns(text: String): List<SpeakerTurn> {
    val root = Json.parseToJsonElement(text).jsonObject
    val doan = root["doan"]
    if (doan == null || doan !is kotlinx.serialization.json.JsonArray) return emptyList()
    return doan.jsonArray.map {
        val o: JsonObject = it.jsonObject
        SpeakerTurn(
            o["bat_dau"]!!.jsonPrimitive.content.toDouble(),
            o["ket_thuc"]!!.jsonPrimitive.content.toDouble(),
            (o["nguoi_noi"] as JsonPrimitive).content,
        )
    }.sortedBy { it.start }
}

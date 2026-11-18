package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.common.settings.AudioPresets
import kotlinx.serialization.Serializable

/**
 * Cài đặt ghi đè cho riêng một tệp, từ extra của lệnh TRANSCRIBE (Stage 6, D95). Trường `null` = dùng cài đặt của app.
 * Ghi đè lưu cùng mục trong hàng đợi, không đổi cài đặt đã lưu, nên tệp khác vẫn chạy theo cài đặt app.
 *
 * @param speakers [SPEAKERS_OFF] = tắt nhận diện người nói; `pyannote` / `gemini` / `hybrid` = bật với cách đó (D71).
 * @param numSpeakers số người nói (0 = tự đoán trong min–max).
 * @param preset một khoá của [AudioPresets.all]; áp như chọn preset trên trang Âm thanh (bật cắt lặng và khử ồn).
 * @param denoise bật / tắt lọc dải tần + khử ồn; áp sau [preset].
 * @param outputDir [OUTPUT_BESIDE_SOURCE] hoặc đường dẫn tuyệt đối. Không nằm trong [PipelineConfig]: app chọn thư mục
 * lưu bằng `QueueEnvironment.output` theo quyền đọc tệp (D96).
 * @param fakeGemini chỉ bản debug: Gemini giả cho riêng tệp này (đêm thử MacroDroid, D100); bản release bỏ qua.
 */
@Serializable
data class ItemOverrides(
    val speakers: String? = null,
    val numSpeakers: Int? = null,
    val preset: String? = null,
    val denoise: Boolean? = null,
    val outputDir: String? = null,
    val fakeGemini: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == ItemOverrides()

    /** Mô tả ngắn cho log, vd `speakers=off, preset=near_lecturer`. */
    fun describe(): String = listOfNotNull(
        speakers?.let { "speakers=$it" },
        numSpeakers?.let { "num_speakers=$it" },
        preset?.let { "preset=$it" },
        denoise?.let { "denoise=$it" },
        outputDir?.let { "output_dir=$it" },
        fakeGemini?.let { "fake_gemini=$it" },
    ).joinToString(", ")

    companion object {
        const val SPEAKERS_OFF = "off"
        const val OUTPUT_BESIDE_SOURCE = "source"
        val SPEAKER_METHODS = listOf("pyannote", "gemini", "hybrid")
    }
}

/** Cấu hình cho một tệp: cài đặt app cộng ghi đè của tệp đó (D95). `null` hoặc rỗng thì trả lại nguyên cấu hình. */
fun PipelineConfig.withOverrides(o: ItemOverrides?): PipelineConfig {
    if (o == null || o.isEmpty) return this
    var cfg = this
    o.speakers?.let { s ->
        cfg = if (s == ItemOverrides.SPEAKERS_OFF) {
            cfg.copy(gemini = cfg.gemini.copy(diarizationEnabled = false))
        } else {
            require(s in ItemOverrides.SPEAKER_METHODS) { "speakers lạ: $s" }
            cfg.copy(gemini = cfg.gemini.copy(diarizationEnabled = true, diarizationMethod = s))
        }
    }
    o.numSpeakers?.let { n -> cfg = cfg.copy(speakers = cfg.speakers.copy(numSpeakers = n)) }
    o.preset?.let { name ->
        val p = requireNotNull(AudioPresets.all[name]) { "preset lạ: $name" }
        cfg = cfg.copy(
            chunk = cfg.chunk.copy(
                targetDbfs = p.targetDbfs, trimSilence = true, minSilenceLen = p.minSilenceLen,
                silenceThreshOffset = p.silenceThreshOffset, keepSilence = p.keepSilence,
            ),
            clean = cfg.clean.copy(
                denoise = true, noiseSampleSec = p.noiseSampleSec, bandpassLow = p.bandpassLow,
                bandpassHigh = p.bandpassHigh, propDecrease = p.propDecrease, levelWindowSec = p.levelWindowSec,
                transientClampDb = p.transientClampDb,
            ),
        )
    }
    o.denoise?.let { d -> cfg = cfg.copy(clean = cfg.clean.copy(denoise = d)) }
    o.fakeGemini?.let { f -> cfg = cfg.copy(debugFakeGemini = f) }
    return cfg
}

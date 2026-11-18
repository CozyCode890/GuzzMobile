package io.github.cozycode890.guzz.core.audio

import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings

/**
 * Cài đặt của các bước làm sạch đầy đủ (Stage 7, mục `[AUDIO]`), là tham số của audio_processing.py: `clean_audio`
 * trừ phần chuẩn hoá / cắt lặng (đã ở [ChunkSettings]). Mặc định = `config.default.txt` (preset "Giảng viên đi lại", D87).
 *
 * @param denoise lọc dải tần + khử ồn (desktop `denoise`).
 * @param noiseSampleSec cửa sổ (giây) ước lượng sàn ồn, là `time_constant_s` của noisereduce (tối thiểu 0,2).
 * @param levelWindowSec cửa sổ cân bằng âm lượng theo thời gian, 0 = tắt.
 * @param transientClampDb ghìm tiếng động to hơn mức thường quá số dB này, 0 = tắt.
 */
data class CleanSettings(
    val denoise: Boolean = true,
    val noiseSampleSec: Double = 1.5,
    val bandpassLow: Int = 80,
    val bandpassHigh: Int = 7000,
    val propDecrease: Double = 0.8,
    val levelWindowSec: Double = 3.0,
    val transientClampDb: Double = 0.0,
) {
    companion object {
        /** Tắt hết: chỉ chuẩn hoá âm lượng và cắt lặng như Stage 2 (D7). */
        val OFF = CleanSettings(denoise = false, levelWindowSec = 0.0, transientClampDb = 0.0)

        /** Đọc từ cài đặt của app (mục `[AUDIO]`). */
        suspend fun load(settings: Settings) = CleanSettings(
            denoise = settings.get(SettingKeys.DENOISE),
            noiseSampleSec = settings.get(SettingKeys.NOISE_SAMPLE_SEC),
            bandpassLow = settings.get(SettingKeys.BANDPASS_LOW),
            bandpassHigh = settings.get(SettingKeys.BANDPASS_HIGH),
            propDecrease = settings.get(SettingKeys.PROP_DECREASE),
            levelWindowSec = settings.get(SettingKeys.LEVEL_WINDOW_SEC),
            transientClampDb = settings.get(SettingKeys.TRANSIENT_CLAMP_DB),
        )
    }
}

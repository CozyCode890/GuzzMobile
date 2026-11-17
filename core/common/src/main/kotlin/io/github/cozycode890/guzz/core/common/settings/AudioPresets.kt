package io.github.cozycode890.guzz.core.common.settings

/**
 * Giá trị các mục `[AUDIO]` mà một preset đặt. Port của gui/presets.py: `PRESET_FIELDS` (cùng thứ tự), mỗi trường có
 * đúng kiểu của mục cài đặt tương ứng nên so bằng `==` như `detect_preset` của desktop (Python so 80 == 80.0 là bằng).
 */
data class AudioPresetValues(
    val targetDbfs: Double,
    val silenceThreshOffset: Int,
    val minSilenceLen: Int,
    val keepSilence: Int,
    val noiseSampleSec: Double,
    val bandpassLow: Int,
    val bandpassHigh: Int,
    val propDecrease: Double,
    val levelWindowSec: Double,
    val transientClampDb: Double,
) {
    /** Áp preset = ghi từng mục cài đặt; như desktop, không lưu khoá "preset" nào (preset nhận lại bằng [AudioPresets.detect]). */
    suspend fun applyTo(settings: Settings) {
        settings.set(SettingKeys.TARGET_DBFS, targetDbfs)
        settings.set(SettingKeys.SILENCE_THRESH_OFFSET, silenceThreshOffset)
        settings.set(SettingKeys.MIN_SILENCE_LEN, minSilenceLen)
        settings.set(SettingKeys.KEEP_SILENCE, keepSilence)
        settings.set(SettingKeys.NOISE_SAMPLE_SEC, noiseSampleSec)
        settings.set(SettingKeys.BANDPASS_LOW, bandpassLow)
        settings.set(SettingKeys.BANDPASS_HIGH, bandpassHigh)
        settings.set(SettingKeys.PROP_DECREASE, propDecrease)
        settings.set(SettingKeys.LEVEL_WINDOW_SEC, levelWindowSec)
        settings.set(SettingKeys.TRANSIENT_CLAMP_DB, transientClampDb)
    }

    /** Cặp (mục cài đặt, giá trị) theo thứ tự `PRESET_FIELDS`, cho test và trang Âm thanh. */
    fun entries(): List<Pair<SettingKey<*>, Any>> = listOf(
        SettingKeys.TARGET_DBFS to targetDbfs,
        SettingKeys.SILENCE_THRESH_OFFSET to silenceThreshOffset,
        SettingKeys.MIN_SILENCE_LEN to minSilenceLen,
        SettingKeys.KEEP_SILENCE to keepSilence,
        SettingKeys.NOISE_SAMPLE_SEC to noiseSampleSec,
        SettingKeys.BANDPASS_LOW to bandpassLow,
        SettingKeys.BANDPASS_HIGH to bandpassHigh,
        SettingKeys.PROP_DECREASE to propDecrease,
        SettingKeys.LEVEL_WINDOW_SEC to levelWindowSec,
        SettingKeys.TRANSIENT_CLAMP_DB to transientClampDb,
    )

    companion object {
        /** Port của gui/presets.py: `values_from_config`. */
        suspend fun read(settings: Settings) = AudioPresetValues(
            targetDbfs = settings.get(SettingKeys.TARGET_DBFS),
            silenceThreshOffset = settings.get(SettingKeys.SILENCE_THRESH_OFFSET),
            minSilenceLen = settings.get(SettingKeys.MIN_SILENCE_LEN),
            keepSilence = settings.get(SettingKeys.KEEP_SILENCE),
            noiseSampleSec = settings.get(SettingKeys.NOISE_SAMPLE_SEC),
            bandpassLow = settings.get(SettingKeys.BANDPASS_LOW),
            bandpassHigh = settings.get(SettingKeys.BANDPASS_HIGH),
            propDecrease = settings.get(SettingKeys.PROP_DECREASE),
            levelWindowSec = settings.get(SettingKeys.LEVEL_WINDOW_SEC),
            transientClampDb = settings.get(SettingKeys.TRANSIENT_CLAMP_DB),
        )
    }
}

/** Các preset âm thanh dựng sẵn. Port của gui/presets.py (lý do từng con số: docstring của tệp đó). */
object AudioPresets {
    const val NEAR_LECTURER = "near_lecturer"
    const val NEAR_SPEAKER = "near_speaker"
    const val MOVING_LECTURER = "moving_lecturer"
    const val SPEAKERS_AROUND = "speakers_around"
    const val CUSTOM = "custom"

    /** Port của gui/presets.py: `PRESETS`, cùng thứ tự (thứ tự hiện trên trang Âm thanh). */
    val all: Map<String, AudioPresetValues> = linkedMapOf(
        NEAR_LECTURER to AudioPresetValues(
            targetDbfs = -16.0, silenceThreshOffset = 16, minSilenceLen = 700, keepSilence = 300,
            noiseSampleSec = 2.0, bandpassLow = 80, bandpassHigh = 8000, propDecrease = 0.85,
            levelWindowSec = 0.0, transientClampDb = 0.0,
        ),
        NEAR_SPEAKER to AudioPresetValues(
            targetDbfs = -18.0, silenceThreshOffset = 14, minSilenceLen = 700, keepSilence = 250,
            noiseSampleSec = 3.0, bandpassLow = 80, bandpassHigh = 6000, propDecrease = 0.92,
            levelWindowSec = 0.0, transientClampDb = 0.0,
        ),
        MOVING_LECTURER to AudioPresetValues(
            targetDbfs = -16.0, silenceThreshOffset = 20, minSilenceLen = 1000, keepSilence = 400,
            noiseSampleSec = 1.5, bandpassLow = 80, bandpassHigh = 7000, propDecrease = 0.8,
            levelWindowSec = 3.0, transientClampDb = 0.0,
        ),
        SPEAKERS_AROUND to AudioPresetValues(
            targetDbfs = -16.0, silenceThreshOffset = 16, minSilenceLen = 700, keepSilence = 300,
            noiseSampleSec = 3.0, bandpassLow = 120, bandpassHigh = 8000, propDecrease = 0.85,
            levelWindowSec = 0.0, transientClampDb = 15.0,
        ),
    )

    /** Port của gui/presets.py: `detect_preset`: preset đầu tiên trùng mọi trường, không có thì [CUSTOM]. */
    fun detect(current: AudioPresetValues): String = all.entries.firstOrNull { it.value == current }?.key ?: CUSTOM
}

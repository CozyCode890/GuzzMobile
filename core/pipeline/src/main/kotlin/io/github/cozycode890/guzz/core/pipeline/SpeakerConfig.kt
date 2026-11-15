package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.core.speaker.DiarizationSettings
import io.github.cozycode890.guzz.core.speaker.SpeakerBounds
import io.github.cozycode890.guzz.core.speaker.SpeakerOptions

/**
 * Phần `[SPEAKERS]` (và `[OUTPUT] save_speaker_map`) của `Config` desktop mà pipeline dùng. Bật / tắt và cách nhận diện
 * nằm ở `GeminiConfig` (Stage 3, vì ràng buộc model ↔ cách).
 */
data class SpeakerConfig(
    val numSpeakers: Int = SettingKeys.NUM_SPEAKERS.default,
    val minSpeakers: Int = SettingKeys.MIN_SPEAKERS.default,
    val maxSpeakers: Int = SettingKeys.MAX_SPEAKERS.default,
    /** `clean` | `original`. */
    val runOn: String = SettingKeys.RUN_ON.default,
    val options: SpeakerOptions = SpeakerOptions(),
    /** `file` | `text`. */
    val mapDelivery: String = SettingKeys.MAP_DELIVERY.default,
    val timestamps: Boolean = SettingKeys.SPEAKER_TIMESTAMPS.default,
    val paragraphTemplate: String = SettingKeys.PARAGRAPH_TEMPLATE.default,
    val maxParagraphSec: Double = SettingKeys.MAX_PARAGRAPH_SEC.default,
    val paragraphGapSec: Double = SettingKeys.PARAGRAPH_GAP_SEC.default,
    val markReview: Boolean = SettingKeys.MARK_REVIEW.default,
    val saveSpeakerMap: Boolean = SettingKeys.SAVE_SPEAKER_MAP.default,
    /** Cấu hình sherpa-onnx (hằng số trong code, D76); vào mã cache qua [DiarizationSettings.signature]. */
    val diarization: DiarizationSettings = DiarizationSettings(),
) {
    val bounds: SpeakerBounds get() = SpeakerBounds.of(numSpeakers, minSpeakers, maxSpeakers)
    val runOnOriginal: Boolean get() = runOn == RUN_ON_ORIGINAL

    companion object {
        const val RUN_ON_ORIGINAL = "original"
        const val DELIVERY_FILE = "file"

        suspend fun load(settings: Settings) = SpeakerConfig(
            numSpeakers = settings.get(SettingKeys.NUM_SPEAKERS),
            minSpeakers = settings.get(SettingKeys.MIN_SPEAKERS),
            maxSpeakers = settings.get(SettingKeys.MAX_SPEAKERS),
            runOn = settings.get(SettingKeys.RUN_ON),
            options = SpeakerOptions(
                smooth = settings.get(SettingKeys.SMOOTH),
                sandwichMaxSec = settings.get(SettingKeys.SANDWICH_MAX_SEC),
                sandwichGapSec = settings.get(SettingKeys.SANDWICH_GAP_SEC),
                densityWindowSec = settings.get(SettingKeys.DENSITY_WINDOW_SEC),
                densityMinSec = settings.get(SettingKeys.DENSITY_MIN_SEC),
                mergeGapSec = settings.get(SettingKeys.MERGE_GAP_SEC),
                labelMain = settings.get(SettingKeys.LABEL_MAIN_SPEAKER),
                mainLabel = settings.get(SettingKeys.MAIN_LABEL),
                otherLabel = settings.get(SettingKeys.OTHER_LABEL),
                genericLabel = settings.get(SettingKeys.GENERIC_LABEL),
            ),
            mapDelivery = settings.get(SettingKeys.MAP_DELIVERY),
            timestamps = settings.get(SettingKeys.SPEAKER_TIMESTAMPS),
            paragraphTemplate = settings.get(SettingKeys.PARAGRAPH_TEMPLATE),
            maxParagraphSec = settings.get(SettingKeys.MAX_PARAGRAPH_SEC),
            paragraphGapSec = settings.get(SettingKeys.PARAGRAPH_GAP_SEC),
            markReview = settings.get(SettingKeys.MARK_REVIEW),
            saveSpeakerMap = settings.get(SettingKeys.SAVE_SPEAKER_MAP),
        )
    }
}

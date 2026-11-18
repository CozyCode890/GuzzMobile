package io.github.cozycode890.guzz.feature.queue

import android.content.Context
import io.github.cozycode890.guzz.core.audio.PrepareStep
import io.github.cozycode890.guzz.core.pipeline.ErrorCodes
import io.github.cozycode890.guzz.core.pipeline.QueueItem

/** Mã bước giải mã (trước các bước của [PrepareStep]) trong `QueueItem.step`. */
const val STEP_DECODE = "decode"

/** Mã bước lưu trong `QueueItem.step` (Stage 8 G1): giao diện dịch qua [stepText], không lưu chữ tiếng Việt. */
val PrepareStep.code: String get() = name.lowercase()

/** Tên bước xử lý âm thanh theo ngôn ngữ app. Hàng đợi cũ (lưu chữ tiếng Việt) thì hiện nguyên văn. */
fun stepText(context: Context, step: String?): String = when (step) {
    null -> ""
    STEP_DECODE -> context.getString(R.string.step_decode)
    PrepareStep.BANDPASS.code -> context.getString(R.string.step_bandpass)
    PrepareStep.TRANSIENTS.code -> context.getString(R.string.step_transients)
    PrepareStep.DENOISE.code -> context.getString(R.string.step_denoise)
    PrepareStep.LEVEL.code -> context.getString(R.string.step_level)
    PrepareStep.NORMALIZE.code -> context.getString(R.string.step_normalize)
    PrepareStep.TRIM.code -> context.getString(R.string.step_trim)
    PrepareStep.WRITE_CLEAN.code -> context.getString(R.string.step_write_clean)
    PrepareStep.WRITE_CHUNKS.code -> context.getString(R.string.step_write_chunks)
    else -> step
}

/** Loại lỗi Google AI (`ErrorKind.code`, cũng là `loai_loi` trong sổ hạn mức) theo ngôn ngữ app. */
fun googleErrorKindText(context: Context, kind: String?): String = context.getString(
    when (kind) {
        "qua_tai" -> R.string.google_err_overloaded
        "han_muc" -> R.string.google_err_minute
        "han_muc_ngay" -> R.string.google_err_daily
        "bi_chan" -> R.string.google_err_blocked
        "model" -> R.string.google_err_model
        "cau_hinh" -> R.string.google_err_config
        "tam_thoi" -> R.string.google_err_network
        else -> R.string.google_err_other
    },
)

/**
 * Lỗi của một tệp theo ngôn ngữ app: có mã thì dịch qua `strings.xml`, không có (lỗi lạ, hàng đợi cũ) thì hiện
 * `error` nguyên văn. Lỗi Google AI: loại lỗi đã dịch, kèm chi tiết Google / client trả về.
 */
fun errorText(context: Context, item: QueueItem): String? {
    val a = item.errorArgs
    fun arg(i: Int) = a.getOrElse(i) { "" }
    return when (item.errorCode) {
        null -> item.error
        ErrorCodes.CONFIG -> context.getString(R.string.err_config, arg(0))
        ErrorCodes.OVERRIDES -> context.getString(R.string.err_overrides, arg(0))
        ErrorCodes.GOOGLE_AI -> context.getString(R.string.err_google_ai, googleErrorKindText(context, item.errorKind)) +
            item.error?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        ErrorCodes.CONSTRAINT_ON_DEVICE -> context.getString(R.string.err_constraint_on_device, arg(0))
        ErrorCodes.CONSTRAINT_GOOGLE -> context.getString(R.string.err_constraint_google, arg(0))
        ErrorCodes.FILENAME_TEMPLATE -> context.getString(R.string.err_filename_template, arg(0))
        ErrorCodes.NO_DIARIZATION -> context.getString(R.string.err_no_diarization)
        ErrorCodes.OUTPUT_FOLDER -> context.getString(R.string.err_output_folder, arg(0))
        ErrorCodes.OUTPUT_WRITE -> context.getString(R.string.err_output_write, arg(0), arg(1))
        ErrorCodes.SOURCE_MISSING -> context.getString(R.string.err_source_missing, arg(0))
        ErrorCodes.NO_SPEECH -> context.getString(R.string.err_no_speech)
        ErrorCodes.NO_AUDIO -> context.getString(R.string.err_no_audio)
        ErrorCodes.MEDIA_READ -> context.getString(R.string.err_media_read, arg(0))
        ErrorCodes.STORAGE -> context.getString(R.string.err_storage, arg(0), arg(1))
        else -> item.error
    }
}

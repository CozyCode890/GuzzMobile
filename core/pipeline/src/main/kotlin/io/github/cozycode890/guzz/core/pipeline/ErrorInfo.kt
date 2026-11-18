package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.audio.NoSpeechException
import io.github.cozycode890.guzz.core.gemini.ConstraintException
import io.github.cozycode890.guzz.core.gemini.DiarizationMethod
import io.github.cozycode890.guzz.core.gemini.GoogleAIException
import java.io.FileNotFoundException

/**
 * Mã lỗi của một tệp trong hàng đợi (Stage 8 G1): giao diện dịch mã + [args] qua `strings.xml`, còn `QueueItem.error`
 * tiếng Việt vẫn giữ cho log và sự kiện `FILE_FAILED` (Stage 6). Lỗi không có mã thì giao diện hiện `error` như cũ.
 */
data class ErrorInfo(val code: String, val args: List<String> = emptyList())

/** Các mã lỗi. Tham số ghi ở từng mã, theo thứ tự `%1$s`, `%2$s`… trong `strings.xml`. */
object ErrorCodes {
    /** Không đọc được cài đặt. [detail] */
    const val CONFIG = "config"

    /** Ghi đè cài đặt của riêng tệp (Stage 6) không hợp lệ. [detail] */
    const val OVERRIDES = "overrides"

    /** Lỗi Google AI; loại lỗi ở `QueueItem.errorKind`, chi tiết ở `error`. */
    const val GOOGLE_AI = "google_ai"

    /** Cách tách giọng trên máy + prompt mà model là *-transcribe. [model] */
    const val CONSTRAINT_ON_DEVICE = "constraint_on_device"

    /** Cách tách giọng trên Google mà model không phải *-transcribe. [model] */
    const val CONSTRAINT_GOOGLE = "constraint_google"

    /** Mẫu tên tệp sai. [template] */
    const val FILENAME_TEMPLATE = "filename_template"

    /** Bản build không có tách giọng trên máy. */
    const val NO_DIARIZATION = "no_diarization"

    /** Không mở / tạo / đọc được thư mục lưu. [folder] */
    const val OUTPUT_FOLDER = "output_folder"

    /** Không ghi được một tệp vào thư mục lưu. [name, folder] */
    const val OUTPUT_WRITE = "output_write"

    /** Tệp nguồn không còn hoặc mất quyền đọc. [displayName] */
    const val SOURCE_MISSING = "source_missing"

    /** Sau khi làm sạch không còn tiếng. */
    const val NO_SPEECH = "no_speech"

    /** Video không có luồng âm thanh. */
    const val NO_AUDIO = "no_audio"

    /** Không đọc / giải mã được tệp nguồn (hỏng, định dạng máy không giải mã được). [chi tiết kỹ thuật] */
    const val MEDIA_READ = "media_read"

    /** Không đủ chỗ trống. [cần MB, còn MB] */
    const val STORAGE = "storage"
}

/**
 * Mã lỗi cho [e] khi tệp [displayName] lỗi; `null` nếu là lỗi lạ (giao diện hiện `error`). [cfg] để biết cách nhận diện
 * và model khi lỗi ràng buộc.
 */
fun errorInfoOf(e: Throwable, displayName: String, cfg: PipelineConfig?): ErrorInfo? = when (e) {
    is PipelineException -> e.code?.let { ErrorInfo(it, e.args) }
    is GoogleAIException -> ErrorInfo(ErrorCodes.GOOGLE_AI)
    is ConstraintException -> cfg?.let {
        val onDevice = it.gemini.currentDiarizationMethod() == DiarizationMethod.PYANNOTE
        ErrorInfo(if (onDevice) ErrorCodes.CONSTRAINT_ON_DEVICE else ErrorCodes.CONSTRAINT_GOOGLE, listOf(it.gemini.model))
    }
    is FileNotFoundException -> ErrorInfo(ErrorCodes.SOURCE_MISSING, listOf(displayName))
    is NoSpeechException -> ErrorInfo(ErrorCodes.NO_SPEECH)
    is NoAudioTrackException -> ErrorInfo(ErrorCodes.NO_AUDIO)
    else -> null
}

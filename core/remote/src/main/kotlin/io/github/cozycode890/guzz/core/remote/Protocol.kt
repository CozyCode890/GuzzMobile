package io.github.cozycode890.guzz.core.remote

import io.github.cozycode890.guzz.core.audio.ChunkSettings
import io.github.cozycode890.guzz.core.audio.CleanSettings
import io.github.cozycode890.guzz.core.speaker.DiarizationSettings
import io.github.cozycode890.guzz.core.speaker.SpeakerBounds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Giao thức job giữa app và server xử lý âm thanh (Stage 11). Server làm cả cắt đoạn lẫn tách giọng trong một job, ghi
 * đúng các tệp cache của `FilePipeline` (`danh_sach_doan.json`, `doan_NNN.flac`, `nguoi_noi.<speakerKey>.json`,
 * `sach.flac`, `am_thanh.flac`), nên sau khi giải nén vào thư mục tạm, pipeline chỉ thấy cache trúng như bình thường.
 *
 * Đổi nghĩa một trường hay thêm trường bắt buộc thì tăng [VERSION]: hai bên khác phiên bản thì app tự làm trên máy.
 */
object Protocol {
    const val VERSION = 1

    /** Tên tệp kết quả cắt đoạn, như `ChunkPreparer.CHUNK_LIST_FILE`. */
    const val CHUNK_LIST_FILE = "danh_sach_doan.json"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun speakersFile(speakerKey: String) = "nguoi_noi.$speakerKey.json"
}

/**
 * Yêu cầu một job. Mã cache app đã tính sẵn ([audioKey], [speakerKey]); server chỉ ghi chúng vào tên / khoá tệp.
 *
 * @param fileName tên tệp tải lên (đuôi giúp ffmpeg đoán định dạng).
 * @param streamIndex luồng âm thanh thứ mấy của tệp tải lên (`-map 0:a:N`); video đã tách sẵn một luồng thì 0.
 * @param sourceName đường dẫn / URI tệp gốc trên điện thoại, ghi vào khoá `nguon` của `danh_sach_doan.json`.
 * @param speakerKey `null` = không tách giọng.
 * @param runOnOriginal tách giọng trên âm thanh gốc (D75), không phải âm thanh đã làm sạch.
 * @param saveClean ghi thêm `sach.flac`; [saveExtracted] ghi thêm `am_thanh.flac` (video).
 */
@Serializable
data class JobRequest(
    val protocol: Int = Protocol.VERSION,
    val fileName: String,
    val sourceName: String,
    val streamIndex: Int = 0,
    val audioKey: String,
    val speakerKey: String? = null,
    val sampleRate: Int = 16_000,
    val chunk: ChunkSettings = ChunkSettings(),
    val clean: CleanSettings = CleanSettings(),
    val saveClean: Boolean = false,
    val saveExtracted: Boolean = false,
    val bounds: SpeakerBounds = SpeakerBounds(0, 0),
    val diarization: DiarizationSettings = DiarizationSettings(),
    val runOnOriginal: Boolean = false,
) {
    val diarize: Boolean get() = speakerKey != null
}

@Serializable
enum class JobState {
    /** D150: đã tạo job, đang nhận tệp theo phần. */
    @SerialName("uploading") UPLOADING,
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,
    @SerialName("done") DONE,
    @SerialName("failed") FAILED,
}

/** Mã lỗi của job. [NO_AUDIO] và [NO_SPEECH] làm trên máy cũng ra đúng lỗi đó, nên app không chuyển sang máy. */
object JobErrors {
    const val NO_AUDIO = "no_audio"
    const val NO_SPEECH = "no_speech"
    const val BAD_REQUEST = "bad_request"
    const val CANCELLED = "cancelled"
    const val INTERNAL = "internal"
}

/** Một tệp kết quả: tên trong thư mục tạm, cỡ, SHA-256 (chữ thường) để app kiểm sau khi tải về. */
@Serializable
data class ResultFile(val name: String, val size: Long, val sha256: String)

@Serializable
data class JobResult(val files: List<ResultFile>)

/**
 * Trạng thái job. [step] là bước con (`decode`, `clean`, `chunks`, `diarize`), [fraction] tiến độ cả job (0..1).
 * Có [result] khi [state] = [JobState.DONE]; có [error] (mã [JobErrors]) và [message] khi [JobState.FAILED].
 * [received]: số byte server đã nhận (các phần liền nhau từ đầu tệp), để tải lên tiếp sau khi đứt (D150).
 */
@Serializable
data class JobStatus(
    val protocol: Int = Protocol.VERSION,
    val id: String = "",
    val state: JobState,
    val step: String? = null,
    val fraction: Double = 0.0,
    val error: String? = null,
    val message: String? = null,
    val result: JobResult? = null,
    val received: Long = 0,
)

/** `GET /health`: phiên bản giao thức và server. */
@Serializable
data class ServerInfo(val protocol: Int, val server: String)

/** Các bước con của job, ghi vào [JobStatus.step]. */
object JobSteps {
    const val DECODE = "decode"
    const val CLEAN = "clean"
    const val DIARIZE = "diarize"
    const val PACK = "pack"
}

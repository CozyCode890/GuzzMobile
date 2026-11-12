package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.audio.ChunkList
import io.github.cozycode890.guzz.core.audio.ChunkSettings
import java.io.File
import java.io.IOException

/**
 * Thông tin tệp nguồn, đủ để tính vân tay, đặt tên và biết là video.
 *
 * @param id đường dẫn tuyệt đối hoặc content:// URI.
 * @param modifiedMillis giờ sửa tệp; 0 = không biết (provider không cho).
 * @param mimeType MIME của provider, `null` nếu không có (khi đó đoán theo đuôi).
 */
data class SourceInfo(
    val id: String,
    val displayName: String,
    val size: Long,
    val modifiedMillis: Long,
    val mimeType: String? = null,
)

/** Nghe bước xử lý âm thanh: tiến độ (0..1 của cả bước) và điểm dừng (ném lỗi để dừng giữa chừng). */
interface SourceListener {
    fun onProgress(fraction: Double, step: String) {}
    fun checkCancelled() {}
}

/**
 * Tệp nguồn của một mục hàng đợi: trên máy bọc `AudioPreparation` (giải mã → làm sạch → cắt đoạn FLAC), test đọc WAV
 * tổng hợp rồi chạy `ChunkPreparer`.
 */
interface ChunkSource {
    /** Ném [java.io.FileNotFoundException] khi tệp không còn (đã xoá, mất quyền đọc URI). */
    fun info(): SourceInfo

    /**
     * Cắt đoạn vào [folder] và ghi `danh_sach_doan.json` có khoá `ma` = [digest]. Video không có luồng âm thanh thì ném
     * [NoAudioTrackException].
     */
    fun prepare(
        folder: File,
        digest: String,
        chunk: ChunkSettings,
        audioTrack: String,
        listener: SourceListener,
    ): ChunkList
}

/** Video không có luồng âm thanh nào. Port của transcribe.py: `NoAudioError`. */
class NoAudioTrackException(message: String) : RuntimeException(message)

/**
 * Lỗi của pipeline mà không phải lỗi Google AI: cấu hình (mẫu tên tệp sai, bật nhận diện người nói khi chưa có), thư mục
 * lưu không dùng được… [stopQueue]: tệp nào cũng sẽ lỗi như vậy, dừng cả hàng đợi.
 */
class PipelineException(message: String, val stopQueue: Boolean = false, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Thư mục lưu bản chép lời: trên máy là thư mục SAF người dùng chọn (D59), test và lệnh adb của bản debug dùng thư mục
 * thường ([DirectoryOutputFolder]). Liệt kê tên một lần để tính tên không trùng như `unique_path`.
 */
interface OutputFolder {
    /** Tên để ghi log và hiện cho người dùng. */
    val label: String

    /** Tên các tệp đang có. Ném [PipelineException] (dừng hàng đợi) khi thư mục không còn hoặc mất quyền. */
    fun listNames(): Collection<String>

    /**
     * Ghi [bytes] vào tệp [name]. [overwrite]: tệp cùng tên đã có thì ghi đè, không thì tạo mới. Ghi hỏng giữa chừng
     * thì không để lại tệp dở. Trả về vị trí đã ghi (đường dẫn hoặc URI) để mở / chia sẻ.
     */
    fun write(name: String, bytes: ByteArray, overwrite: Boolean): String
}

/** Thư mục thường: ghi `.tmp` rồi đổi tên như `write_text` của desktop. */
class DirectoryOutputFolder(val dir: File) : OutputFolder {
    override val label: String get() = dir.path

    override fun listNames(): Collection<String> {
        if (!dir.isDirectory && !dir.mkdirs()) throw PipelineException("Không tạo được thư mục lưu $dir.", stopQueue = true)
        return dir.list()?.toList() ?: throw PipelineException("Không đọc được thư mục lưu $dir.", stopQueue = true)
    }

    override fun write(name: String, bytes: ByteArray, overwrite: Boolean): String {
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!overwrite && target.exists()) throw IOException("$target đã có")
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Không đổi tên được $tmp")
        } catch (e: IOException) {
            tmp.delete()
            throw PipelineException("Không ghi được $target: ${e.message}", cause = e)
        }
        return target.path
    }
}

package io.github.cozycode890.guzz.core.media

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import android.net.Uri
import java.io.Closeable
import java.io.File

/**
 * Tệp đầu vào: đường dẫn tuyệt đối (có quyền "Truy cập mọi tệp", hoặc tệp trong thư mục riêng của app) hoặc content://
 * URI từ trình chọn tệp / SAF (D6). Cả hai đều mở MediaExtractor qua file descriptor.
 */
sealed class MediaInput {
    /** Tên hiển thị và ghi vào `nguon` của `danh_sach_doan.json`. */
    abstract val name: String

    data class Path(val file: File) : MediaInput() {
        override val name: String get() = file.path
    }

    data class Content(val uri: Uri) : MediaInput() {
        override val name: String get() = uri.toString()
    }

    /** Mở extractor; đóng [OpenedMedia] để giải phóng cả extractor lẫn file descriptor. */
    fun open(context: Context): OpenedMedia {
        val extractor = MediaExtractor()
        var afd: AssetFileDescriptor? = null
        try {
            when (this) {
                is Path -> {
                    if (!file.isFile) throw MediaReadException("Không thấy tệp ${file.path}")
                    extractor.setDataSource(file.path)
                }
                is Content -> {
                    afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
                        ?: throw MediaReadException("Không mở được $uri")
                    extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
                }
            }
        } catch (e: MediaReadException) {
            extractor.release()
            afd?.close()
            throw e
        } catch (e: Exception) {
            // setDataSource ném IOException / IllegalArgumentException khi tệp hỏng hoặc không phải âm thanh / video.
            extractor.release()
            afd?.close()
            throw MediaReadException("Không đọc được $name: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return OpenedMedia(extractor, afd)
    }
}

class OpenedMedia(val extractor: MediaExtractor, private val afd: AssetFileDescriptor?) : Closeable {
    override fun close() {
        extractor.release()
        afd?.close()
    }
}

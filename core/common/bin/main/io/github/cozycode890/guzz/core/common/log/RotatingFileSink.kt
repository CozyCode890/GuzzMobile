package io.github.cozycode890.guzz.core.common.log

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Ghi log ra tệp, xoay vòng theo dung lượng. Port của `logging.handlers.RotatingFileHandler` mà desktop dùng
 * (transcribe.py: `setup_logging`, `maxBytes = log_size_mb MB`, `backupCount = old_log_count`).
 *
 * Giống Python:
 * - xoay khi `cỡ tệp + cỡ bản ghi >= maxBytes`; tệp đang rỗng thì không xoay (một bản ghi dài hơn `maxBytes` vẫn được
 *   ghi trọn vào tệp rỗng);
 * - xoay: `guzz.log.(N-1)` → `guzz.log.N`, …, `guzz.log` → `guzz.log.1`, giữ [backupCount] tệp cũ, tệp `.1` mới nhất.
 *
 * Khác Python:
 * - `backupCount = 0`: Python không bao giờ xoay nên tệp lớn mãi; ở đây đầy thì xoá trắng `guzz.log`, không giữ tệp cũ,
 *   vì dung lượng log trên điện thoại phải luôn bị chặn (D23).
 * - so theo **byte** UTF-8, còn Python 3.12 so `len(msg)` theo ký tự: với tiếng Việt có dấu Python để tệp vượt
 *   `maxBytes`, ở đây thì không.
 * - xuống dòng `\n` (Python trên Windows ghi `\r\n`).
 *
 * An toàn khi nhiều luồng cùng ghi: mỗi bản ghi được ghi trọn, không lẫn dòng.
 */
class RotatingFileSink(
    private val file: File,
    maxBytes: Long,
    backupCount: Int,
    private val formatter: LogFormatter = LogFormatter(),
) : LogSink, Closeable {

    private val lock = Any()
    private var out: FileOutputStream? = null
    private var size = 0L
    private var maxBytes = 0L
    private var backupCount = 0

    init {
        configure(maxBytes, backupCount)
    }

    /** Đổi dung lượng / số tệp cũ khi người dùng sửa cài đặt; áp dụng từ bản ghi tiếp theo. */
    fun configure(maxBytes: Long, backupCount: Int) {
        require(maxBytes > 0) { "maxBytes phải > 0: $maxBytes" }
        require(backupCount >= 0) { "backupCount phải >= 0: $backupCount" }
        synchronized(lock) {
            this.maxBytes = maxBytes
            this.backupCount = backupCount
        }
    }

    override fun write(record: LogRecord) {
        val bytes = (formatter.format(record) + "\n").toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            if (out == null) open()
            if (size > 0 && size + bytes.size >= maxBytes) rollover()
            val stream = out ?: open()
            // Ghi và flush ngay từng bản ghi như StreamHandler của Python: app bị giết vẫn còn log tới dòng cuối.
            stream.write(bytes)
            stream.flush()
            size += bytes.size
        }
    }

    override fun close() {
        synchronized(lock) {
            out?.close()
            out = null
        }
    }

    private fun open(): FileOutputStream {
        file.parentFile?.mkdirs()
        return FileOutputStream(file, true).also {
            out = it
            size = file.length()
        }
    }

    private fun rollover() {
        out?.close()
        out = null
        if (backupCount > 0) {
            for (i in backupCount - 1 downTo 1) {
                val source = backup(i)
                if (source.exists()) move(source, backup(i + 1))
            }
            move(file, backup(1))
        } else {
            // D23: không giữ tệp cũ; FileOutputStream(append = false) cắt tệp về rỗng.
            FileOutputStream(file, false).close()
        }
    }

    private fun backup(index: Int) = File(file.path + "." + index)

    private fun move(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

package io.github.cozycode890.guzz.feature.queue

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.github.cozycode890.guzz.core.audio.ChunkList
import io.github.cozycode890.guzz.core.audio.ChunkSettings
import io.github.cozycode890.guzz.core.audio.CleanSettings
import io.github.cozycode890.guzz.core.audio.PrepareStep
import io.github.cozycode890.guzz.core.media.AudioPreparation
import io.github.cozycode890.guzz.core.media.InsufficientStorageException
import io.github.cozycode890.guzz.core.media.MediaInput
import io.github.cozycode890.guzz.core.media.MediaReadException
import io.github.cozycode890.guzz.core.media.NoAudioException
import io.github.cozycode890.guzz.core.media.PreparationListener
import io.github.cozycode890.guzz.core.pipeline.ChunkSource
import io.github.cozycode890.guzz.core.pipeline.ErrorCodes
import io.github.cozycode890.guzz.core.pipeline.NoAudioTrackException
import io.github.cozycode890.guzz.core.pipeline.OutputFolder
import io.github.cozycode890.guzz.core.pipeline.PipelineException
import io.github.cozycode890.guzz.core.pipeline.SourceInfo
import io.github.cozycode890.guzz.core.pipeline.SourceListener
import java.io.File
import java.io.FileNotFoundException

/** Nguồn là đường dẫn tuyệt đối (`/…`) hay content:// URI (D6). */
fun mediaInputOf(source: String): MediaInput =
    if (source.startsWith("/")) MediaInput.Path(File(source)) else MediaInput.Content(Uri.parse(source))

/** Đọc thông tin tệp nguồn: tên, cỡ, giờ sửa, MIME (vân tay cache, đặt tên, video hay âm thanh). */
fun querySource(context: Context, source: String): SourceInfo = when (val input = mediaInputOf(source)) {
    is MediaInput.Path -> {
        val f = input.file
        if (!f.isFile) throw FileNotFoundException("Không thấy tệp ${f.path}")
        SourceInfo(f.path, f.name, f.length(), f.lastModified(), mimeFromName(f.name))
    }
    is MediaInput.Content -> {
        val resolver = context.contentResolver
        val row = try {
            // Projection null: provider không phải tài liệu (MediaStore) ném IllegalArgumentException khi hỏi cột nó không
            // có (`last_modified`, Stage 6 D1); đọc mọi cột rồi chỉ lấy cột có mặt.
            resolver.query(input.uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                fun col(name: String) = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }
                Triple(
                    col(OpenableColumns.DISPLAY_NAME)?.let(c::getString),
                    col(OpenableColumns.SIZE)?.let(c::getLong) ?: 0L,
                    // Provider không phải tài liệu (vd MediaStore) có thể không có cột này: 0 = không biết.
                    col(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.let(c::getLong) ?: 0L,
                )
            }
        } catch (e: SecurityException) {
            throw FileNotFoundException("Mất quyền đọc ${input.uri}: ${e.message}")
        } ?: throw FileNotFoundException("Không đọc được ${input.uri} (tệp đã xoá hoặc mất quyền).")
        val name = row.first ?: input.uri.lastPathSegment ?: "tep"
        val mime = runCatching { resolver.getType(input.uri) }.getOrNull()?.takeIf { it != "application/octet-stream" }
        SourceInfo(source, name, row.second, row.third, mime ?: mimeFromName(name))
    }
}

/** MIME theo đuôi tệp, `null` nếu không biết. */
fun mimeFromName(name: String): String? =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

/**
 * [ChunkSource] trên máy: giải mã → làm sạch (Stage 7) → cắt đoạn FLAC bằng [AudioPreparation]. Tiến độ: giải mã chiếm
 * 60% bước âm thanh, các bước của `ChunkPreparer` chia đều phần còn lại.
 */
class AndroidChunkSource(private val context: Context, private val source: String) : ChunkSource {
    override fun info(): SourceInfo = querySource(context, source)

    override fun prepare(
        folder: File,
        digest: String,
        chunk: ChunkSettings,
        clean: CleanSettings,
        saveClean: Boolean,
        audioTrack: String,
        listener: SourceListener,
    ): ChunkList {
        val l = object : PreparationListener {
            override fun onProgress(fraction: Double) = listener.onProgress(DECODE_WEIGHT * fraction, STEP_DECODE)

            override fun onProgress(step: PrepareStep, fraction: Double) = listener.onProgress(
                DECODE_WEIGHT + (1 - DECODE_WEIGHT) * (step.ordinal + fraction) / PrepareStep.entries.size, step.code,
            )

            override fun checkCancelled() = listener.checkCancelled()

            override fun log(message: String) = listener.log(message)
        }
        return try {
            AudioPreparation(context).run(mediaInputOf(source), audioTrack, chunk, folder, l, digest, clean, saveClean).chunks
        } catch (e: NoAudioException) {
            throw NoAudioTrackException(e.message ?: "Tệp không có luồng âm thanh nào.")
        } catch (e: MediaReadException) {
            // Chi tiết cho giao diện: thông báo gốc của hệ thống (vd "Failed to instantiate extractor"), không lặp câu tiếng Việt.
            val detail = (e.cause?.message ?: e.message).orEmpty()
            throw PipelineException(e.message.orEmpty(), cause = e, code = ErrorCodes.MEDIA_READ, args = listOf(detail))
        } catch (e: InsufficientStorageException) {
            throw PipelineException(
                e.message.orEmpty(), cause = e, code = ErrorCodes.STORAGE,
                args = listOf((e.neededBytes / 1_000_000).toString(), (e.freeBytes / 1_000_000).toString()),
            )
        }
    }

    private companion object {
        const val DECODE_WEIGHT = 0.6
    }
}

/**
 * Thư mục lưu người dùng chọn qua SAF (`OpenDocumentTree`, D59). Liệt kê tên một lần; tạo tài liệu với MIME theo đuôi
 * (`.txt` → `text/plain`) để provider không tự thêm đuôi; ghi đè thì mở chế độ `"wt"`. SAF không đổi tên nguyên tử như
 * `os.replace`: ghi hỏng giữa chừng thì xoá tài liệu vừa tạo.
 */
class SafOutputFolder(private val context: Context, private val tree: Uri) : OutputFolder {
    private val resolver: ContentResolver get() = context.contentResolver
    private val treeDoc: Uri by lazy {
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    override val label: String get() = tree.lastPathSegment?.substringAfter(':')?.let { "…/$it" } ?: tree.toString()

    /** {tên: URI tài liệu} của các tệp trong thư mục. */
    private fun children(): Map<String, Uri> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val result = LinkedHashMap<String, Uri>()
        try {
            resolver.query(childrenUri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    result[c.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            } ?: throw FileNotFoundException("provider trả về null")
        } catch (e: Exception) {
            throw PipelineException(
                "Không mở được thư mục lưu bản chép lời ($label): ${e.message}. Chọn lại thư mục lưu.", stopQueue = true,
                cause = e, code = ErrorCodes.OUTPUT_FOLDER, args = listOf(label),
            )
        }
        return result
    }

    override fun listNames(): Collection<String> = children().keys

    override fun write(name: String, bytes: ByteArray, overwrite: Boolean): String {
        val existing = if (overwrite) children().entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value else null
        val created = existing == null
        val doc = existing ?: try {
            val mime = mimeFromName(name) ?: "application/octet-stream"
            DocumentsContract.createDocument(resolver, treeDoc, mime, name)
        } catch (e: Exception) {
            throw PipelineException(
                "Không tạo được $name trong thư mục lưu ($label): ${e.message}", stopQueue = true, cause = e,
                code = ErrorCodes.OUTPUT_WRITE, args = listOf(name, label),
            )
        } ?: throw PipelineException(
            "Không tạo được $name trong thư mục lưu ($label).", stopQueue = true, code = ErrorCodes.OUTPUT_WRITE,
            args = listOf(name, label),
        )
        try {
            (resolver.openOutputStream(doc, "wt") ?: throw FileNotFoundException("không mở được để ghi")).use { it.write(bytes) }
        } catch (e: Exception) {
            if (created) runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw PipelineException(
                "Không ghi được $name ($label): ${e.message}", cause = e, code = ErrorCodes.OUTPUT_WRITE,
                args = listOf(name, label),
            )
        }
        return doc.toString()
    }

    /** Như [write] nhưng chép theo dòng từ tệp (âm thanh sạch có thể vài trăm MB, không đọc cả vào RAM). */
    override fun copy(name: String, source: File, overwrite: Boolean): String {
        val existing = if (overwrite) children().entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value else null
        val created = existing == null
        val doc = existing ?: try {
            DocumentsContract.createDocument(resolver, treeDoc, mimeFromName(name) ?: "application/octet-stream", name)
        } catch (e: Exception) {
            throw PipelineException(
                "Không tạo được $name trong thư mục lưu ($label): ${e.message}", stopQueue = true, cause = e,
                code = ErrorCodes.OUTPUT_WRITE, args = listOf(name, label),
            )
        } ?: throw PipelineException(
            "Không tạo được $name trong thư mục lưu ($label).", stopQueue = true, code = ErrorCodes.OUTPUT_WRITE,
            args = listOf(name, label),
        )
        try {
            (resolver.openOutputStream(doc, "wt") ?: throw FileNotFoundException("không mở được để ghi")).use { os ->
                source.inputStream().use { it.copyTo(os, 1 shl 16) }
            }
        } catch (e: Exception) {
            if (created) runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw PipelineException(
                "Không ghi được $name ($label): ${e.message}", cause = e, code = ErrorCodes.OUTPUT_WRITE,
                args = listOf(name, label),
            )
        }
        return doc.toString()
    }
}

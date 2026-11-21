package io.github.cozycode890.guzz.core.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Tệp tải lên server: [open] mở luồng đọc từ đầu (gọi lại được nếu phải tải lên lại), [length] cỡ (byte, `-1` = không
 * biết). Tệp âm thanh thì là chính tệp gốc; video thì là luồng âm thanh đã tách (D144). [streamIndex]: luồng âm thanh thứ
 * mấy của tệp tải lên mà server giải mã (`-map 0:a:N`).
 */
class UploadSource(
    val name: String,
    val length: Long,
    val streamIndex: Int = 0,
    /** Xoá tệp tạm (luồng tách từ video) sau khi dùng xong. */
    val cleanup: () -> Unit = {},
    val open: () -> InputStream,
)

/**
 * Server không dùng được cho tệp này (mạng, token, 4xx / 5xx, quá giờ, khác phiên bản giao thức, kết quả hỏng): app
 * chuyển sang làm trên máy (D145). [reason] là mã ngắn để log / thông báo ([Reasons]).
 */
class RemoteUnavailableException(val reason: String, message: String, cause: Throwable? = null) : IOException(message, cause)

/** Server báo lỗi mà làm trên máy cũng ra đúng lỗi đó ([JobErrors.NO_AUDIO], [JobErrors.NO_SPEECH]): không chuyển. */
class RemoteJobFailedException(val code: String, message: String?) : RuntimeException(message)

/** Mã lý do chuyển sang máy. */
object Reasons {
    const val NETWORK = "network"
    const val AUTH = "auth"
    const val PROTOCOL = "protocol"
    const val HTTP = "http"
    const val TIMEOUT = "timeout"
    const val SERVER = "server"
    const val RESULT = "result"
    const val CONFIG = "config"
}

/** Các bước con của việc dùng server, ghi vào `FileProgress.step` (E5). */
object RemoteSteps {
    const val UPLOAD = "server_upload"
    const val PROCESS = "server_process"
    const val DOWNLOAD = "server_download"
}

/** Chạy một job trên server: tải lên, chờ, tải kết quả vào [folder]. Bản thật: [RemoteClient]; test của pipeline dùng bản giả. */
interface RemoteProcessor {
    /**
     * Sau khi trả về, [folder] có đủ tệp cache của [request] (danh sách đoạn ghi sau cùng). Ném
     * [RemoteUnavailableException] (chuyển sang máy), [RemoteJobFailedException], hoặc `CancellationException` (Dừng: job
     * trên server bị xoá).
     *
     * @param onProgress (bước [RemoteSteps], phần đã xong của bước 0..1).
     */
    suspend fun process(
        upload: UploadSource,
        request: JobRequest,
        folder: File,
        log: (String) -> Unit = {},
        onProgress: (String, Double) -> Unit = { _, _ -> },
    )
}

/**
 * Client OkHttp của server Modal (Stage 11 E2, E3). Token dạng `<Modal-Key>.<Modal-Secret>` gửi trong header
 * `Authorization: Bearer` (proxy auth của Modal). Tải lên dạng stream (không đọc cả tệp vào RAM), hỏi trạng thái mỗi
 * [pollMs], tải tar về tệp `.tmp`, giải nén, kiểm cỡ + SHA-256 từng tệp, rồi `DELETE` job.
 *
 * Làm tiếp (D64, E3): mã job lưu ở `server_job.json` trong [folder] ngay khi tạo; chạy lại tệp đó thì hỏi trạng thái
 * job cũ trước, job không còn (404) thì tải lên lại.
 */
class RemoteClient(
    private val http: OkHttpClient,
    baseUrl: String,
    private val token: String,
    private val pollMs: Long = 5_000,
    private val maxWaitMs: Long = 90 * 60_000L,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) : RemoteProcessor {
    private val base: HttpUrl? = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()

    private fun url(vararg segments: String): HttpUrl {
        val b = base ?: throw RemoteUnavailableException(Reasons.CONFIG, "URL server không hợp lệ")
        return b.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
    }

    private fun request(u: HttpUrl) = Request.Builder().url(u).header("Authorization", "Bearer ${token.trim()}")

    /** `GET /health` (nút Kiểm tra kết nối). Ném [RemoteUnavailableException]. */
    suspend fun health(): ServerInfo {
        val r = send(request(url("health")).get().build())
        val info = decode(r, ServerInfo.serializer())
        if (info.protocol != Protocol.VERSION) {
            throw RemoteUnavailableException(Reasons.PROTOCOL, "Server dùng giao thức ${info.protocol}, app dùng ${Protocol.VERSION}")
        }
        return info
    }

    override suspend fun process(
        upload: UploadSource,
        request: JobRequest,
        folder: File,
        log: (String) -> Unit,
        onProgress: (String, Double) -> Unit,
    ) {
        folder.mkdirs()
        val jobFile = File(folder, JOB_FILE)
        var id: String? = null
        try {
            var status: JobStatus? = null
            val saved = readJob(jobFile)?.takeIf { it.audioKey == request.audioKey && it.speakerKey == request.speakerKey }
            if (saved != null) {
                status = getStatus(saved.id)
                if (status != null) {
                    id = saved.id
                    log("Nối lại job ${saved.id} trên server (${status.state.name.lowercase()}).")
                }
            }
            if (status == null) {
                jobFile.delete()
                onProgress(RemoteSteps.UPLOAD, 0.0)
                status = create(upload, request, onProgress)
                id = status.id
                writeJob(jobFile, SavedJob(status.id, request.audioKey, request.speakerKey))
                log("Đã tải lên server, job ${status.id}.")
            }
            val jobId = id!!
            status = wait(jobId, status, onProgress)
            download(jobId, status, folder, onProgress)
            withContext(NonCancellable) { deleteQuietly(jobId) }
            jobFile.delete()
        } catch (e: CancellationException) {
            // Dừng: xoá job trên server (không giữ bản ghi), không chuyển sang máy.
            withContext(NonCancellable) { id?.let { deleteQuietly(it) } }
            jobFile.delete()
            throw e
        } catch (e: RemoteJobFailedException) {
            jobFile.delete()
            withContext(NonCancellable) { id?.let { deleteQuietly(it) } }
            throw e
        } catch (e: RemoteUnavailableException) {
            jobFile.delete()
            withContext(NonCancellable) { id?.let { deleteQuietly(it) } }
            throw e
        }
    }

    private suspend fun create(upload: UploadSource, req: JobRequest, onProgress: (String, Double) -> Unit): JobStatus {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = upload.length
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                upload.open().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        done += n
                        if (upload.length > 0) onProgress(RemoteSteps.UPLOAD, done.toDouble() / upload.length)
                    }
                }
            }
        }
        val u = url("jobs").newBuilder()
            .addQueryParameter("request", Protocol.json.encodeToString(JobRequest.serializer(), req)).build()
        val st = decode(send(request(u).post(body).build()), JobStatus.serializer())
        if (st.id.isEmpty()) throw RemoteUnavailableException(Reasons.SERVER, "Server không trả mã job")
        onProgress(RemoteSteps.UPLOAD, 1.0)
        return st
    }

    /** Trạng thái job, `null` khi server không còn job đó (404). */
    private suspend fun getStatus(id: String): JobStatus? {
        val r = send(request(url("jobs", id)).get().build(), allow404 = true) ?: return null
        val st = decode(r, JobStatus.serializer())
        if (st.protocol != Protocol.VERSION) {
            throw RemoteUnavailableException(Reasons.PROTOCOL, "Server dùng giao thức ${st.protocol}")
        }
        return st
    }

    private suspend fun wait(id: String, first: JobStatus, onProgress: (String, Double) -> Unit): JobStatus {
        val started = now()
        var st = first
        while (true) {
            when (st.state) {
                JobState.DONE -> {
                    onProgress(RemoteSteps.PROCESS, 1.0)
                    return st
                }
                JobState.FAILED -> when (st.error) {
                    JobErrors.NO_AUDIO, JobErrors.NO_SPEECH -> throw RemoteJobFailedException(st.error, st.message)
                    else -> throw RemoteUnavailableException(Reasons.SERVER, "Server lỗi: ${st.error} ${st.message.orEmpty()}".trim())
                }
                else -> onProgress(RemoteSteps.PROCESS, st.fraction)
            }
            if (now() - started > maxWaitMs) throw RemoteUnavailableException(Reasons.TIMEOUT, "Server xử lý quá lâu")
            sleep(pollMs)
            st = getStatus(id) ?: throw RemoteUnavailableException(Reasons.SERVER, "Server không còn job $id")
        }
    }

    private suspend fun download(id: String, st: JobStatus, folder: File, onProgress: (String, Double) -> Unit) {
        val files = st.result?.files ?: throw RemoteUnavailableException(Reasons.RESULT, "Server không kê tệp kết quả")
        val listed = files.map { it.name }
        if (Protocol.CHUNK_LIST_FILE !in listed || listed.any { !SAFE_NAME.matches(it) }) {
            throw RemoteUnavailableException(Reasons.RESULT, "Danh sách tệp kết quả không hợp lệ")
        }
        val total = files.sumOf { it.size }.coerceAtLeast(1)
        val tmpDir = File(folder, TMP_DIR).apply { deleteRecursively(); mkdirs() }
        try {
            onProgress(RemoteSteps.DOWNLOAD, 0.0)
            val call = http.newCall(request(url("jobs", id, "result")).get().build())
            val response = call.await()
            response.use { r ->
                if (!r.isSuccessful) throw httpError(r.code)
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    var done = 0L
                    Tar.extract(r.body.byteStream(), tmpDir, listed.toSet()) { n ->
                        done += n
                        onProgress(RemoteSteps.DOWNLOAD, done.toDouble() / total)
                    }
                }
            }
            for (f in files) {
                val got = File(tmpDir, f.name)
                if (!got.isFile || got.length() != f.size || sha256(got) != f.sha256) {
                    throw RemoteUnavailableException(Reasons.RESULT, "Tệp ${f.name} tải về bị hỏng")
                }
            }
            // Danh sách đoạn chuyển sau cùng: có nó nghĩa là mọi đoạn đã ở đúng chỗ (như ChunkPreparer).
            for (name in listed.sortedBy { it == Protocol.CHUNK_LIST_FILE }) {
                val target = File(folder, name)
                target.delete()
                if (!File(tmpDir, name).renameTo(target)) throw IOException("Không đổi tên được $name")
            }
            onProgress(RemoteSteps.DOWNLOAD, 1.0)
        } catch (e: IOException) {
            if (e is RemoteUnavailableException) throw e
            throw RemoteUnavailableException(Reasons.NETWORK, "Tải kết quả lỗi: ${e.message}", e)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private suspend fun deleteQuietly(id: String) {
        try {
            send(request(url("jobs", id)).delete().build(), allow404 = true)?.close()
        } catch (e: Exception) {
            // Không xoá được thì việc dọn hằng ngày của server xoá sau ≤ 24 giờ (D147).
        }
    }

    /** Gửi, trả về [Response] thành công (hoặc `null` khi 404 và [allow404]); lỗi HTTP / mạng thành [RemoteUnavailableException]. */
    private suspend fun send(req: Request, allow404: Boolean = false): Response? {
        val r = try {
            http.newCall(req).await()
        } catch (e: IOException) {
            throw RemoteUnavailableException(Reasons.NETWORK, "Không tới được server: ${e.message}", e)
        }
        if (r.isSuccessful) return r
        r.close()
        if (r.code == 404 && allow404) return null
        throw httpError(r.code)
    }

    private fun httpError(code: Int) = when (code) {
        401, 403 -> RemoteUnavailableException(Reasons.AUTH, "Server từ chối token (HTTP $code)")
        409 -> RemoteUnavailableException(Reasons.PROTOCOL, "Server khác phiên bản giao thức (HTTP 409)")
        else -> RemoteUnavailableException(Reasons.HTTP, "Server trả HTTP $code")
    }

    private suspend fun <T> decode(r: Response?, s: kotlinx.serialization.KSerializer<T>): T {
        r ?: throw RemoteUnavailableException(Reasons.SERVER, "Server không trả lời")
        val text = try {
            withContext(kotlinx.coroutines.Dispatchers.IO) { r.use { it.body.string() } }
        } catch (e: IOException) {
            throw RemoteUnavailableException(Reasons.NETWORK, "Đọc trả lời của server lỗi: ${e.message}", e)
        }
        return try {
            Protocol.json.decodeFromString(s, text)
        } catch (e: Exception) {
            throw RemoteUnavailableException(Reasons.SERVER, "Server trả lời không đọc được", e)
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
            },
        )
    }

    @Serializable
    private data class SavedJob(val id: String, val audioKey: String, val speakerKey: String?)

    private fun readJob(f: File): SavedJob? =
        runCatching { Protocol.json.decodeFromString(SavedJob.serializer(), f.readText()) }.getOrNull()

    private fun writeJob(f: File, j: SavedJob) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(Protocol.json.encodeToString(SavedJob.serializer(), j))
        f.delete()
        tmp.renameTo(f)
    }

    companion object {
        /** Mã job đang chạy trên server, trong thư mục tạm của tệp (E3). */
        const val JOB_FILE = "server_job.json"
        private const val TMP_DIR = "server_tai_ve"
        private val SAFE_NAME = Regex("""[A-Za-z0-9_.\-]{1,120}""")

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Đọc tar (ustar / pax, không nén) của `GET /jobs/{id}/result`: chỉ lấy tệp thường có tên trong [allowed]. */
internal object Tar {
    fun extract(input: InputStream, dir: File, allowed: Set<String>, onBytes: (Int) -> Unit = {}) {
        val header = ByteArray(512)
        val buf = ByteArray(1 shl 16)
        var paxName: String? = null
        while (true) {
            if (!readFully(input, header)) throw IOException("tar bị cắt cụt")
            if (header.all { it.toInt() == 0 }) return
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            var name = paxName ?: cString(header, 0, 100).let { n ->
                val prefix = cString(header, 345, 155)
                if (prefix.isNotEmpty()) "$prefix/$n" else n
            }
            paxName = null
            when (type) {
                'x' -> {
                    val data = ByteArray(size.toInt())
                    if (!readFully(input, data)) throw IOException("tar bị cắt cụt")
                    skip(input, pad(size))
                    paxName = parsePax(data)["path"]
                    continue
                }
                '0', '\u0000' -> {
                    name = name.removePrefix("./")
                    val out = if (name in allowed) File(dir, name).outputStream().buffered() else null
                    try {
                        var left = size
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw IOException("tar bị cắt cụt")
                            out?.write(buf, 0, n)
                            left -= n
                            onBytes(n)
                        }
                    } finally {
                        out?.close()
                    }
                    skip(input, pad(size))
                }
                else -> skip(input, size + pad(size))
            }
        }
    }

    private fun pad(size: Long) = (512 - size % 512) % 512

    private fun readFully(input: InputStream, b: ByteArray): Boolean {
        var p = 0
        while (p < b.size) {
            val n = input.read(b, p, b.size - p)
            if (n < 0) return false
            p += n
        }
        return true
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = input.skip(left)
            if (k <= 0) {
                if (input.read() < 0) throw IOException("tar bị cắt cụt")
                left--
            } else {
                left -= k
            }
        }
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long =
        cString(b, off, len).trim().ifEmpty { "0" }.toLong(8)

    private fun parsePax(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var p = 0
        val text = String(data, Charsets.UTF_8)
        while (p < text.length) {
            val sp = text.indexOf(' ', p)
            if (sp < 0) break
            val len = text.substring(p, sp).toIntOrNull() ?: break
            val rec = text.substring(sp + 1, (p + len - 1).coerceAtMost(text.length))
            val eq = rec.indexOf('=')
            if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
            p += len
        }
        return out
    }
}

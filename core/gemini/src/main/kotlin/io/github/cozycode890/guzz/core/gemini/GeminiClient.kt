package io.github.cozycode890.guzz.core.gemini

import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.log.LogLevel
import io.github.cozycode890.guzz.core.common.log.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow

/** Tham số thêm cho một lần gửi đoạn. Port các tham số từ khoá của google_ai.py: `Client.transcribe_raw`. */
data class TranscribeOptions(
    /** `null` = [GeminiConfig.model]. */
    val model: String? = null,
    val diarize: Boolean = false,
    val wordTimestamps: Boolean = false,
    /** Tệp văn bản tải lên cạnh âm thanh (chỉ model đa năng). */
    val attachment: File? = null,
    /** Chèn vào cuối prompt (thay cho [attachment]). */
    val attachedText: String? = null,
    /** > 0: lỗi quá tải / hết hạn mức chỉ thử chừng đó lần, vì còn model dự phòng để đổi (lỗi mạng vẫn thử đủ). */
    val maxAttempts: Int = 0,
)

/** Thứ [ChunkDispatcher] cần ở client: thật là [GeminiClient], test thay bằng client giả. */
interface ChunkTranscriber {
    val tracker: QuotaTracker

    /** Số token đầu vào của lần gửi thành công gần nhất (0 = chưa biết). */
    val lastTokenCount: Long get() = 0

    suspend fun transcribeRaw(
        audio: File,
        cfg: GeminiConfig,
        prompt: String?,
        audioSeconds: Double?,
        options: TranscribeOptions,
    ): JsonObject

    /** Phải chờ bao lâu (giây) trước khi gửi một đoạn cho [model]; `null` = client không biết (client giả). */
    fun quotaWaitTime(cfg: GeminiConfig, model: String, audioSeconds: Double?): Double? = null
}

/** Chờ [seconds] giây; huỷ coroutine (nút Dừng) thì dừng ngay, thay cho `sleep(should_stop)` của desktop. */
val coroutineSleep: suspend (Double) -> Unit = { seconds -> if (seconds > 0) delay((seconds * 1000).toLong()) }

/**
 * Gọi Google AI Studio (Gemini API) bằng REST qua OkHttp. Port của google_ai.py: `Client`.
 *
 * Mỗi đoạn âm thanh đi qua 3 bước: Files API (tải lên kiểu resumable: start rồi upload, finalize), Interactions API
 * (gửi yêu cầu với tệp vừa tải lên), Files API (xoá tệp nếu bật `delete_remote_file`).
 *
 * Key chỉ nằm ở header `x-goog-api-key`, không bao giờ ở URL hay log; thông điệp lỗi có lẫn key (Google trích lại yêu
 * cầu) cũng bị che bằng [maskApiKey] trước khi ra khỏi client.
 *
 * @param clock đồng hồ đơn điệu (giây) cho hạn chờ tệp xử lý xong.
 * @param sleep hàm chờ; test truyền hàm đẩy đồng hồ giả.
 */
class GeminiClient(
    private val apiKey: String,
    override val tracker: QuotaTracker = QuotaTracker(),
    private val limiters: RateLimiters = RateLimiters(),
    private val http: OkHttpClient = defaultHttpClient(),
    private val apiRoot: String = GeminiApi.API_ROOT,
    private val timeoutSeconds: Double = 600.0,
    private val clock: () -> Double = monotonicSeconds,
    private val sleep: suspend (Double) -> Unit = coroutineSleep,
    private val logger: Logger = GuzzLog,
) : ChunkTranscriber {
    private val root = apiRoot.trimEnd('/')
    private val version = GeminiApi.VERSION

    @Volatile
    override var lastTokenCount: Long = 0
        private set

    fun limiterFor(model: String): RateLimiter = limiters.forModel(model)

    /** (token / phút, yêu cầu / phút, yêu cầu / ngày) đang áp dụng cho [model]. */
    fun limit(cfg: GeminiConfig, model: String): Quota = tracker.limit(cfg, model)

    // ---------- HTTP ----------

    private class HttpResult(val code: Int, val headers: Headers, val body: ByteArray)

    private suspend fun call(
        method: String,
        url: String,
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
        jsonBody: JsonObject? = null,
        timeout: Double? = null,
    ): HttpResult {
        val builder = Request.Builder().url(url).header("x-goog-api-key", apiKey)
        val requestBody = jsonBody?.toString()?.toByteArray(Charsets.UTF_8)?.toRequestBody(JSON) ?: body
        for ((name, value) in headers) builder.header(name, value)
        builder.method(method, requestBody)
        val call = http.newCall(builder.build())
        call.timeout().timeout(((timeout ?: timeoutSeconds) * 1000).toLong(), TimeUnit.MILLISECONDS)
        val result = try {
            call.await()
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw GoogleAIException(scrub("Không kết nối được tới Google AI: ${e.message ?: e}"), ErrorKind.TEMPORARY)
        }
        if (result.code !in 200..299) {
            val error = errorFromHttp(result.code, result.body) { result.headers[it] }
            throw GoogleAIException(
                scrub(error.message), error.kind, error.httpStatus, error.retryAfter, error.violations,
            )
        }
        return result
    }

    /** Che key trong mọi chuỗi ra khỏi client (thông điệp lỗi, log). */
    private fun scrub(text: String): String =
        if (apiKey.isNotEmpty() && apiKey in text) text.replace(apiKey, maskApiKey(apiKey)) else text

    private suspend fun Call.await(): HttpResult = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { HttpResult(it.code, it.headers, it.body.bytes()) }
                    } catch (e: IOException) {
                        cont.resumeWithException(e)
                        return
                    }
                    cont.resume(result)
                }
            },
        )
    }

    // ---------- Files API ----------

    /** Tải [file] lên Files API (resumable: start → upload, finalize), trả về object `file` của Google. */
    suspend fun upload(file: File, mime: String? = null): JsonObject {
        val type = mime ?: guessMime(file.path)
        val size = file.length()
        val start = call(
            "POST", "$root/upload/$version/files",
            jsonBody = buildJsonObject { putJsonObject("file") { put("display_name", file.name) } },
            headers = mapOf(
                "X-Goog-Upload-Protocol" to "resumable",
                "X-Goog-Upload-Command" to "start",
                "X-Goog-Upload-Header-Content-Length" to size.toString(),
                "X-Goog-Upload-Header-Content-Type" to type,
            ),
            timeout = 60.0,
        )
        val uploadUrl = start.headers["X-Goog-Upload-URL"]?.takeIf { it.isNotEmpty() }
            ?: throw GoogleAIException("Google AI không trả về địa chỉ tải lên (X-Goog-Upload-URL).", ErrorKind.TEMPORARY)

        // Content-Length do OkHttp tự đặt theo cỡ tệp.
        val done = call(
            "POST", uploadUrl,
            body = file.asRequestBody(null),
            headers = mapOf("X-Goog-Upload-Offset" to "0", "X-Goog-Upload-Command" to "upload, finalize"),
        )
        val uploaded = parseJsonBody(done.body)["file"] as? JsonObject ?: JsonObject(emptyMap())
        if (!pyTruthy(uploaded["name"]) || !pyTruthy(uploaded["uri"])) {
            throw GoogleAIException("Tải lên xong nhưng Google AI không trả về tên / uri của tệp.", ErrorKind.TEMPORARY)
        }
        return uploaded
    }

    /** Chờ Google xử lý xong tệp vừa tải lên (`state` hết `PROCESSING`). */
    suspend fun waitFileReady(file: JsonObject, maxSeconds: Double = 300.0): JsonObject {
        var current = file
        val deadline = clock() + maxSeconds
        while (stateOf(current) == "PROCESSING") {
            if (clock() > deadline) {
                throw GoogleAIException(
                    "Tệp ${pyStrOr(current["name"])} vẫn đang xử lý sau ${"%.0f".format(maxSeconds)} giây.",
                    ErrorKind.TEMPORARY,
                )
            }
            sleep(2.0)
            val body = call("GET", "$root/$version/${pyStr(current["name"]!!)}", timeout = 60.0).body
            current = parseJsonBody(body).takeIf { it.isNotEmpty() } ?: current
        }
        if (pyStrOr(current["state"]).uppercase() == "FAILED") {
            throw GoogleAIException("Google AI không xử lý được tệp ${pyStrOr(current["name"])}.", ErrorKind.OTHER)
        }
        return current
    }

    private fun stateOf(file: JsonObject): String =
        (file["state"]?.takeIf(::pyTruthy)?.let(::pyStr) ?: "ACTIVE").uppercase()

    suspend fun deleteFile(name: String) {
        try {
            call("DELETE", "$root/$version/$name", timeout = 60.0)
        } catch (e: GoogleAIException) {
            // Không xoá được thì Google cũng tự xoá sau 48 giờ, không đáng để dừng việc.
            logger.warning("   Không xoá được $name trên Google AI (${e.message}).")
        }
    }

    // ---------- Models ----------

    /** Tên các model key dùng được (bỏ tiền tố `models/`). */
    suspend fun listModels(): List<String> {
        val names = mutableListOf<String>()
        var token = ""
        while (true) {
            val url = "$root/$version/models".toHttpUrl().newBuilder().addQueryParameter("pageSize", "1000")
            if (token.isNotEmpty()) url.addQueryParameter("pageToken", token)
            val data = parseJsonBody(call("GET", url.build().toString(), timeout = 30.0).body)
            for (m in data["models"] as? kotlinx.serialization.json.JsonArray ?: emptyList()) {
                val n = (m as? JsonObject)?.get("name")?.let(::pyStrOr).orEmpty()
                names += n.removePrefix("models/")
            }
            token = data["nextPageToken"]?.let(::pyStrOr).orEmpty()
            if (token.isEmpty()) return names.filter { it.isNotEmpty() }
        }
    }

    // ---------- Hạn mức ----------

    override fun quotaWaitTime(cfg: GeminiConfig, model: String, audioSeconds: Double?): Double {
        val (tpm, rpm, _) = limit(cfg, model)
        val limiter = limiterFor(model)
        return maxOf(limiter.waitTime(tpm, limiter.estimate(audioSeconds, tpm)), limiter.requestWaitTime(rpm))
    }

    /** Ngủ, đồng thời báo cho trang Hạn mức biết đang chờ gì, tới lúc nào. */
    private suspend fun waitFor(model: String, reason: String, sec: Double, details: String = "") {
        tracker.setWaiting(model, reason, tracker.clock() + sec, details)
        try {
            sleep(sec)
        } finally {
            tracker.clearWaiting()
        }
    }

    private suspend fun waitForQuota(cfg: GeminiConfig, model: String, audioSeconds: Double?) {
        val (tpm, rpm, _) = limit(cfg, model)
        val limiter = limiterFor(model)
        val expected = limiter.estimate(audioSeconds, tpm)
        val tokenWait = limiter.waitTime(tpm, expected)
        val requestWait = limiter.requestWaitTime(rpm)
        if (tokenWait <= 0 && requestWait <= 0) return
        if (tokenWait >= requestWait) {
            val estimate = if (limiter.tokensPerAudioSec != null) expected.toString() else "chưa rõ"
            logger.info(
                "   Chờ ${"%.0f".format(tokenWait)} giây cho khỏi vượt hạn mức $tpm token/phút của $model (60 giây " +
                    "qua đã dùng ${limiter.tokensUsed()} token, đoạn này ước $estimate token).",
            )
            waitFor(model, "tpm", tokenWait, "$tpm")
        } else {
            logger.info("   Chờ ${"%.0f".format(requestWait)} giây cho khỏi vượt hạn mức $rpm yêu cầu/phút của $model.")
            waitFor(model, "rpm", requestWait, "$rpm")
        }
    }

    /** Đoán lỗi 429 là hết hạn mức theo ngày hay theo phút; ghi nhớ hạn mức Google báo. Port của `_handle_429`. */
    private fun handle429(e: GoogleAIException, model: String) {
        val requests60s = limiterFor(model).requestCount()
        for (v in e.violations) {
            // "generate_content_free_tier_requests, limit: 25" không nói phút hay ngày. Trong 60 giây qua gửi chưa tới
            // 25 yêu cầu thì không thể là hạn mức phút: là hạn mức ngày (lỗi thật 2026-09-17, app gửi mỗi phút một
            // đoạn). Token thì Google giới hạn theo phút.
            val unit = v.unit
                ?: if (v.isToken || v.limit == 0 || requests60s >= v.limit) QuotaUnit.MINUTE else QuotaUnit.DAY
            if (unit == QuotaUnit.DAY) e.isDaily = true
            if (v.limit > 0) {
                val field = if (v.isToken) {
                    if (unit == QuotaUnit.MINUTE) "tpm" else ""
                } else {
                    if (unit == QuotaUnit.MINUTE) "rpm" else "rpd"
                }
                if (field.isNotEmpty()) tracker.learnQuota(v.model.ifEmpty { model }, field, v.limit)
            }
        }
    }

    private fun recordError(model: String, e: GoogleAIException) {
        tracker.recordError(model, if (e.isDaily) LockReason.DAILY_QUOTA else e.kind.code, e.message)
    }

    // ---------- Gõ chữ ----------

    private suspend fun transcribeOnce(
        audio: File,
        cfg: GeminiConfig,
        prompt: String?,
        audioSeconds: Double?,
        options: TranscribeOptions,
    ): JsonObject {
        val model = options.model ?: cfg.model
        val tpm = limit(cfg, model).tpm
        val limiter = limiterFor(model)
        // Chờ TRƯỚC khi tải lên, để tệp không nằm không trên Google trong lúc chờ.
        waitForQuota(cfg, model, audioSeconds)
        val uploaded = mutableListOf<String>()
        try {
            var file = upload(audio)
            uploaded += pyStr(file["name"]!!)
            var document: Document? = null
            options.attachment?.let { attachment ->
                var attached = upload(attachment, guessMime(attachment.path))
                uploaded += pyStr(attached["name"]!!)
                attached = waitFileReady(attached)
                document = Document(pyStrOr(attached["uri"]), mimeOf(attached) ?: guessMime(attachment.path))
            }
            file = waitFileReady(file)
            coroutineContext.ensureActive()
            val mime = mimeOf(file) ?: guessMime(audio.path)
            val request = buildRequest(
                cfg, pyStrOr(file["uri"]), mime, prompt, model = model, diarize = options.diarize,
                wordTimestamps = options.wordTimestamps, document = document, attachedText = options.attachedText,
            )
            if (logger.isEnabled(LogLevel.DEBUG)) logger.debug(scrub("   Yêu cầu: " + request.toString().pyTake(2000)))
            val sentAt = limiter.clock()
            val body = try {
                call(
                    "POST", "$root/$version/interactions", jsonBody = request,
                    timeout = cfg.timeoutSeconds.takeIf { it > 0 }?.toDouble() ?: timeoutSeconds,
                ).body
            } catch (e: GoogleAIException) {
                // Yêu cầu lỗi vẫn tính vào số yêu cầu / phút. Bị 429 thì coi cả phút này là đã dùng hết token.
                limiter.record(sentAt, if (e.httpStatus == 429) tpm.toLong() else 0, actual = false)
                if (e.httpStatus == 429) handle429(e, model)
                recordError(model, e)
                throw e
            }
            var response = parseJsonBody(body)
            lastTokenCount = parseInputTokens(response)
            limiter.record(
                sentAt, lastTokenCount.takeIf { it > 0 } ?: tpm.toLong(), audioSeconds, actual = lastTokenCount > 0,
            )
            response = try {
                checkStatus(response, logger)
            } catch (e: GoogleAIException) {
                recordError(model, e)
                throw e
            }
            tracker.recordRequest(model, lastTokenCount)
            return response
        } finally {
            // Bấm Dừng giữa chừng vẫn xoá tệp đã tải lên, như desktop (khối finally chạy cả khi Stopped).
            if (cfg.deleteRemoteFile && uploaded.isNotEmpty()) {
                withContext(NonCancellable) { uploaded.forEach { deleteFile(it) } }
            }
        }
    }

    private fun mimeOf(file: JsonObject): String? =
        (file["mimeType"]?.takeIf(::pyTruthy) ?: file["mime_type"]?.takeIf(::pyTruthy))?.let(::pyStr)

    /**
     * Gửi một đoạn âm thanh, trả về nguyên Interaction đã kiểm tra trạng thái. Port của google_ai.py:
     * `Client.transcribe_raw`.
     *
     * Trước khi gửi thì chờ đủ hạn mức token / phút và yêu cầu / phút. Lỗi tạm thời thì thử lại tối đa
     * `chunk_retries` lần, chờ đúng thời gian Google yêu cầu (hoặc lâu dần). Hết hạn mức trong ngày thì không thử lại
     * (ném [GoogleAIException] có `isDaily = true`).
     */
    override suspend fun transcribeRaw(
        audio: File,
        cfg: GeminiConfig,
        prompt: String?,
        audioSeconds: Double?,
        options: TranscribeOptions,
    ): JsonObject {
        val model = options.model ?: cfg.model
        val opts = options.copy(model = model)
        val attempts = maxOf(1, cfg.chunkRetries)
        val modelAttempts = if (options.maxAttempts > 0) minOf(attempts, options.maxAttempts) else attempts
        lastTokenCount = 0
        var attempt = 0
        while (true) {
            attempt++
            try {
                return transcribeOnce(audio, cfg, prompt, audioSeconds, opts)
            } catch (e: GoogleAIException) {
                val maxTries = if (e.kind == ErrorKind.OVERLOAD || e.kind == ErrorKind.QUOTA) modelAttempts else attempts
                if (!e.isRetryable || attempt >= maxTries) throw e
                // Google nói rõ phải chờ bao lâu thì chờ đúng chừng đó (thêm 2 giây dự phòng); không nói thì chờ lâu
                // dần 15, 30, 60... giây.
                val wait = if (e.retryAfter > 0) {
                    minOf(MAX_RETRY_WAIT_SEC, e.retryAfter + 2)
                } else {
                    minOf(MAX_RETRY_WAIT_SEC, 15 * 2.0.pow(attempt - 1))
                }
                logger.warning(
                    "   Lỗi tạm thời với $model (${e.message}). Thử lại lần ${attempt + 1}/$maxTries sau " +
                        "${"%.0f".format(wait)} giây.",
                )
                waitFor(model, "thu_lai", wait, e.kind.code)
            }
        }
    }

    /** Như [transcribeRaw] nhưng chỉ trả về chữ. */
    suspend fun transcribe(
        audio: File,
        cfg: GeminiConfig,
        prompt: String? = null,
        audioSeconds: Double? = null,
        options: TranscribeOptions = TranscribeOptions(),
    ): String = extractText(transcribeRaw(audio, cfg, prompt, audioSeconds, options))

    companion object {
        private const val MAX_RETRY_WAIT_SEC = 300.0
        private val JSON = "application/json".toMediaType()

        /**
         * Không đặt hạn đọc / ghi riêng: OkHttp mặc định 10 giây đọc, mà Google có thể nghĩ vài phút mới trả lời.
         * Hạn của từng lần gọi đặt bằng `Call.timeout()` (cả lần gọi), như `timeout` của urllib bên desktop.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}

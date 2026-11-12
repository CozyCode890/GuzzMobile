package io.github.cozycode890.guzz.core.gemini

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import java.io.Closeable
import java.util.Collections

/** Một yêu cầu đã nhận, để test kiểm đường dẫn, header và thân. */
class SeenRequest(val method: String, val path: String, val headers: Headers, val body: ByteArray) {
    val json: JsonObject get() = JsonSupport.json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
    fun header(name: String): String? = headers[name]
}

/** Trả lời của Interactions API: một Interaction (JSON) hoặc lỗi HTTP. */
sealed class Reply {
    class Ok(val body: JsonElement) : Reply()
    class Error(val code: Int, val body: String, val headers: Map<String, String> = emptyMap()) : Reply()

    companion object {
        /** Lỗi theo khuôn `{"error": {"code", "message"}}` như ServerByModel của test desktop. */
        fun error(code: Int, message: String) = Error(
            code,
            buildJsonObject { putJsonObject("error") { put("code", code); put("message", message) } }.toString(),
        )
    }
}

/**
 * Giả lập Gemini API trên MockWebServer, như `FakeServer` / `ServerByModel` của test desktop: ghi lại mọi yêu cầu, tải
 * lên luôn thành công (tệp `files/f1`, hoặc tên lấy lần lượt từ [fileNames]), Interactions trả lời theo [interaction].
 */
class FakeGemini(
    private val fileNames: Iterator<String> = generateSequence { "files/f1" }.iterator(),
    private val fileMime: (String) -> String = { "audio/flac" },
    private val onInteraction: () -> Unit = {},
    private val interaction: (model: String, call: Int) -> Reply,
) : Closeable {
    val server = MockWebServer()
    val requests: MutableList<SeenRequest> = Collections.synchronizedList(mutableListOf())
    private var interactionCount = 0

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    val root: String get() = server.url("/").toString().trimEnd('/')

    fun paths(): List<String> = requests.map { "${it.method} ${it.path}" }

    /** Chuỗi gọn như `ReplayServer.requests` của `gen_gemini.py`. */
    fun kinds(): List<String> = requests.map { r ->
        when {
            r.path == "/upload/v1beta/files" -> "upload_start"
            r.path.startsWith("/upload-session/") -> "upload_finalize"
            r.path == "/v1beta/interactions" -> "interaction " + r.json.getValue("model").jsonPrimitive.content
            r.method == "DELETE" -> "delete " + r.path.removePrefix("/v1beta/")
            else -> "${r.method} ${r.path}"
        }
    }

    fun interactionModels(): List<String> =
        requests.filter { it.path == "/v1beta/interactions" }.map { it.json.getValue("model").jsonPrimitive.content }

    private fun handle(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val seen = SeenRequest(request.method, path, request.headers, request.body?.toByteArray() ?: ByteArray(0))
        requests += seen
        return when {
            path == "/upload/v1beta/files" -> MockResponse.Builder()
                .setHeader("X-Goog-Upload-URL", server.url("/upload-session/abc").toString())
                .body("{}")
                .build()
            path.startsWith("/upload-session/") -> {
                val name = fileNames.next()
                json(
                    buildJsonObject {
                        putJsonObject("file") {
                            put("name", name)
                            put("uri", "https://g/$name")
                            put("mimeType", fileMime(name))
                            put("state", "ACTIVE")
                        }
                    },
                )
            }
            path == "/v1beta/interactions" -> {
                val model = seen.json.getValue("model").jsonPrimitive.content
                onInteraction()
                when (val reply = synchronized(this) { interaction(model, ++interactionCount) }) {
                    is Reply.Ok -> json(reply.body)
                    is Reply.Error -> MockResponse.Builder().code(reply.code).body(reply.body).apply {
                        reply.headers.forEach { (k, v) -> setHeader(k, v) }
                    }.build()
                }
            }
            request.method == "DELETE" -> json(JsonObject(emptyMap()))
            else -> MockResponse.Builder().code(500).body("không mong đợi: ${request.method} $path").build()
        }
    }

    private fun json(body: JsonElement) = MockResponse.Builder()
        .setHeader("Content-Type", "application/json")
        .body(body.toString())
        .build()

    override fun close() = server.close()
}

/** Interaction xong, có chữ (như `RESULT_DONE` của test desktop). */
fun resultDone(text1: String = "Xin chào ", text2: String = "các bạn.", tokens: Long? = null): JsonObject =
    JsonSupport.json.parseToJsonElement(
        """
        {"id": "int_1", "status": "completed", "steps": [
          {"type": "user_input", "content": [{"type": "text", "text": "KHONG LAY"}]},
          {"type": "model_output", "content": [{"type": "text", "text": "$text1"}, {"type": "text", "text": "$text2"}]}
        ]${if (tokens != null) ", \"usage\": {\"total_input_tokens\": $tokens, \"total_tokens\": ${tokens + 900}}" else ""}}
        """.trimIndent(),
    ).jsonObject

/** Interaction chỉ có một đoạn chữ. */
fun resultText(text: String): JsonObject = buildJsonObject {
    put("status", "completed")
    put(
        "steps",
        JsonSupport.json.parseToJsonElement(
            """[{"type": "model_output", "content": [{"type": "text", "text": ${kotlinx.serialization.json.JsonPrimitive(text)}}]}]""",
        ),
    )
}

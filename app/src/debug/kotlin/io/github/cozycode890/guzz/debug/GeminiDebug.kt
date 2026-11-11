package io.github.cozycode890.guzz.debug

import android.content.Context
import android.os.SystemClock
import io.github.cozycode890.guzz.AppContainer
import io.github.cozycode890.guzz.GuzzApp
import io.github.cozycode890.guzz.core.audio.ChunkPreparer
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.log.MemorySink
import io.github.cozycode890.guzz.core.gemini.ChunkDispatcher
import io.github.cozycode890.guzz.core.gemini.ConstraintException
import io.github.cozycode890.guzz.core.gemini.DefaultPrompt
import io.github.cozycode890.guzz.core.gemini.DiarizationMethod
import io.github.cozycode890.guzz.core.gemini.ErrorKind
import io.github.cozycode890.guzz.core.gemini.GeminiConfig
import io.github.cozycode890.guzz.core.gemini.GoogleAIException
import io.github.cozycode890.guzz.core.gemini.TranscribeOptions
import io.github.cozycode890.guzz.core.gemini.extractText
import io.github.cozycode890.guzz.core.gemini.extractWords
import io.github.cozycode890.guzz.core.gemini.isTranscribeModel
import io.github.cozycode890.guzz.core.gemini.isValidModelName
import io.github.cozycode890.guzz.core.gemini.maskApiKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * Gửi thử Gemini từ bản debug (Stage 3, D56): gửi một đoạn FLAC đã cắt bằng màn hình thử lõi âm thanh
 * (`files/debug-audio/<bài>/doan_001.flac`) qua [ChunkDispatcher] với key đã lưu ở trang Google AI, rồi ghi kết quả ra
 * `files/debug-gemini/<bài>_<model>_<chế độ>.json`. Cũng chạy `list_models` ra `files/debug-gemini/models.json`.
 *
 * Chế độ: `smart` / `verbatim` (model đa năng đọc prompt mặc định, `*-transcribe` dùng chế độ đó), `words` (mốc từng
 * từ, như cách Kết hợp), `diarize` (Google tự tách người nói). Tắt tự đổi model để gửi đúng model muốn thử.
 *
 * Gọi từ [AudioDebugActivity] (nút, hoặc adb: lệnh ở KDoc của activity và `tools/device/gemini_debug.ps1`).
 */
object GeminiDebug {
    data class State(val running: Boolean = false, val line: String = "")

    private val state = MutableStateFlow(State())
    val runState: StateFlow<State> get() = state

    val MODES = listOf("smart", "verbatim", "words", "diarize")

    private fun outDir(context: Context) = File(context.getExternalFilesDir("debug-gemini"), "").apply { mkdirs() }

    fun resultFile(context: Context, folder: String, model: String, mode: String): File =
        File(outDir(context), "${folder}_${model}_$mode".replace(Regex("[^\\p{L}\\p{N}._-]"), "_") + ".json")

    /** Ghi lại phương thức, đường dẫn (không query), mã HTTP của mọi lần gọi; không ghi header nên không có key. */
    private class HttpRecorder : Interceptor {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val t0 = SystemClock.elapsedRealtime()
            try {
                val response = chain.proceed(request)
                calls += "${request.method} ${request.url.encodedPath} ${response.code} ${SystemClock.elapsedRealtime() - t0} ms"
                return response
            } catch (e: IOException) {
                calls += "${request.method} ${request.url.encodedPath} lỗi ${e.javaClass.simpleName}"
                throw e
            }
        }
    }

    fun listModels(context: Context) {
        val container = (context.applicationContext as GuzzApp).container
        container.appScope.launch {
            val json = JSONObject()
            state.value = State(true, "list_models…")
            val recorder = HttpRecorder()
            try {
                val client = container.geminiClient(http = container.httpClient.newBuilder().addInterceptor(recorder).build())
                if (client == null) {
                    json.put("ket_qua", "loi").put("loai_loi", ErrorKind.CONFIG.code).put("loi", "Chưa lưu API key.")
                } else {
                    val models = client.listModels()
                    json.put("ket_qua", "ok").put("so_model", models.size).put("model", JSONArray(models))
                }
            } catch (e: GoogleAIException) {
                json.put("ket_qua", "loi").put("loai_loi", e.kind.code).put("loi", e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                json.put("ket_qua", "loi").put("loai_loi", "khac").put("loi", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                json.put("http", JSONArray(recorder.calls.toList()))
                File(outDir(context), "models.json").writeText(json.toString(1), Charsets.UTF_8)
                val line = "list_models: ${json.optString("ket_qua")} ${json.optInt("so_model")} ${json.optString("loi")}"
                GuzzLog.info("Gửi thử Gemini: $line")
                state.value = State(false, line)
            }
        }
    }

    /**
     * @param folder thư mục bài trong `files/debug-audio/`; [chunk] tệp đoạn trong đó.
     * @param model `null` = `GOOGLE_AI.model` trong cài đặt.
     */
    fun send(context: Context, folder: String, chunk: String, model: String?, mode: String) {
        val container = (context.applicationContext as GuzzApp).container
        if (state.value.running) {
            GuzzLog.warning("Gửi thử Gemini: đang gửi, bỏ qua lệnh mới")
            return
        }
        if (!model.isNullOrBlank() && !isValidModelName(model.trim())) {
            // Không ghi lại chuỗi đã gõ: có thể là API key dán nhầm ô.
            val line = "Tên model không hợp lệ (chỉ chữ thường, số, '.', '_', '-'); không gửi."
            GuzzLog.warning("Gửi thử Gemini: $line")
            state.value = State(false, line)
            return
        }
        state.value = State(true, "$folder/$chunk → ${model ?: "model chính"} ($mode)…")
        container.appScope.launch {
            val line = try {
                runSend(context, container, folder, chunk, model, mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                GuzzLog.error("Gửi thử Gemini: lỗi", e)
                "${e.javaClass.simpleName}: ${e.message}"
            }
            state.value = State(false, line)
        }
    }

    private suspend fun runSend(
        context: Context,
        container: AppContainer,
        folder: String,
        chunk: String,
        modelArg: String?,
        mode: String,
    ): String {
        val base = GeminiConfig.load(container.settings)
        val model = modelArg?.takeIf { it.isNotBlank() } ?: base.model
        val cfg = when (mode) {
            "smart", "verbatim" -> base.copy(model = model, transcribeMode = mode, diarizationEnabled = false)
            "words" -> base.copy(model = model, diarizationEnabled = true, diarizationMethod = DiarizationMethod.HYBRID)
            "diarize" -> base.copy(model = model, diarizationEnabled = true, diarizationMethod = DiarizationMethod.GEMINI)
            else -> error("chế độ lạ '$mode', dùng một trong $MODES")
        }.copy(autoSwitchModel = false)
        val options = when (mode) {
            "words" -> TranscribeOptions(wordTimestamps = true)
            "diarize" -> TranscribeOptions(diarize = true, wordTimestamps = true)
            else -> TranscribeOptions()
        }
        val out = resultFile(context, folder, model, mode)
        out.delete()
        val json = JSONObject().put("thu_muc", folder).put("doan", chunk).put("model_yeu_cau", model).put("che_do", mode)
        val logs = MemorySink()
        val recorder = HttpRecorder()
        val tracker = container.quotaTracker
        val startedAt = tracker.clock()
        val t0 = SystemClock.elapsedRealtime()
        GuzzLog.addSink(logs)
        try {
            val audioDir = File(context.getExternalFilesDir("debug-audio"), folder)
            val audio = File(audioDir, chunk)
            val seconds = ChunkPreparer.readChunkList(audioDir)?.chunks?.firstOrNull { it.file == chunk }
                ?.let { it.endSeconds - it.startSeconds }
            json.put("giay_am_thanh", seconds ?: JSONObject.NULL).put("byte", audio.length())
            val key = container.apiKeyStore.read()
            when {
                key == null -> json.put("ket_qua", "loi").put("loai_loi", ErrorKind.CONFIG.code)
                    .put("loi", "Chưa lưu API key: nhập ở trang Google AI.")
                !audio.isFile -> json.put("ket_qua", "loi").put("loai_loi", ErrorKind.OTHER.code).put("loi", "Không thấy $audio")
                else -> {
                    json.put("key", maskApiKey(key))
                    val client = container.geminiClient(key, container.httpClient.newBuilder().addInterceptor(recorder).build())!!
                    val prompt = if (isTranscribeModel(model)) null else DefaultPrompt.text
                    try {
                        val dispatcher = ChunkDispatcher(cfg, client)
                        val r = dispatcher.send(audio, seconds ?: 0.0, { m -> (if (isTranscribeModel(m)) null else prompt) to options })
                        val text = extractText(r.response)
                        val words = extractWords(r.response)
                        json.put("ket_qua", "ok").put("model", r.model)
                            .put("so_ky_tu", text.length).put("chu", text)
                            .put("so_tu_co_moc", words.size)
                            .put("tu_dau", JSONArray(words.take(8).map { "${it.start}-${it.end} ${it.speaker ?: ""} ${it.text}" }))
                            .put("nguoi_noi", JSONArray(words.mapNotNull { it.speaker }.distinct()))
                            .put("token", client.lastTokenCount)
                            .put("usage", r.response["usage"]?.toString() ?: JSONObject.NULL)
                    } catch (e: GoogleAIException) {
                        json.put("ket_qua", "loi").put("loai_loi", e.kind.code).put("loi", e.message)
                    } catch (e: ConstraintException) {
                        json.put("ket_qua", "loi").put("loai_loi", ErrorKind.CONFIG.code).put("loi", e.message)
                    }
                }
            }
        } finally {
            GuzzLog.removeSink(logs)
            val snapshot = tracker.snapshot().data
            json.put("giay_xu_ly", (SystemClock.elapsedRealtime() - t0) / 1000.0)
                .put("http", JSONArray(recorder.calls.toList()))
                .put("su_kien_tracker", JSONArray(snapshot.events.filter { it.time >= startedAt - 1 }
                    .map { "${it.kind} ${it.model} ${it.reason ?: it.errorKind ?: ""} ${it.content.take(120)}" }))
                .put("yeu_cau_hom_nay", snapshot.models[model]?.requestsToday ?: 0)
                .put("token_hom_nay", snapshot.models[model]?.tokensToday ?: 0)
                .put("log", JSONArray(logs.lines))
            out.writeText(json.toString(1), Charsets.UTF_8)
        }
        val line = if (json.optString("ket_qua") == "ok") {
            "${json.optString("model")} ($mode): ${json.optInt("so_ky_tu")} ký tự, ${json.optInt("so_tu_co_moc")} từ có mốc, " +
                "${json.optLong("token")} token, ${"%.1f".format(json.optDouble("giay_xu_ly"))} s"
        } else {
            "$model ($mode): lỗi ${json.optString("loai_loi")}: ${json.optString("loi")}"
        }
        GuzzLog.info("Gửi thử Gemini: $line → ${out.name}")
        return line
    }
}

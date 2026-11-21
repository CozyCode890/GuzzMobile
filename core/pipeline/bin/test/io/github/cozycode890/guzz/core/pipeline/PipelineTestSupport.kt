package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.audio.ChunkEncoder
import io.github.cozycode890.guzz.core.audio.ChunkList
import io.github.cozycode890.guzz.core.audio.ChunkPreparer
import io.github.cozycode890.guzz.core.audio.ChunkSettings
import io.github.cozycode890.guzz.core.audio.CleanSettings
import io.github.cozycode890.guzz.core.audio.PrepareListener
import io.github.cozycode890.guzz.core.audio.PrepareStep
import io.github.cozycode890.guzz.core.audio.SampleRange
import io.github.cozycode890.guzz.core.audio.pcm.FloatArraySource
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.gemini.ChunkTranscriber
import io.github.cozycode890.guzz.core.gemini.GeminiConfig
import io.github.cozycode890.guzz.core.gemini.QuotaTracker
import io.github.cozycode890.guzz.core.gemini.TranscribeOptions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

const val SR = 16_000

/** Giây có "tiếng nói" trong tệp 60 giây, như `VOICED` của tests/test_transcribe.py. */
val VOICED = listOf(2 to 20, 25 to 40, 45 to 58)

/**
 * Cùng hình dạng `make_audio` của test desktop: nhiễu nhỏ + ba khoảng sin 220 Hz điều biên 3 Hz. Không cần trùng từng
 * mẫu (bộ sinh số ngẫu nhiên khác), vì ranh giới đoạn đã có golden ở Stage 2.
 */
fun makeAudio(): FloatArray {
    val rng = Random(1)
    val y = FloatArray(60 * SR) { (rng.nextGaussian() * 0.0005).toFloat() }
    for ((a, b) in VOICED) {
        for (k in 0 until (b - a) * SR) {
            val t = k.toDouble() / SR
            y[a * SR + k] += (0.3 * sin(2 * PI * 220 * t) * (1 + sin(2 * PI * 3 * t))).toFloat()
        }
    }
    return y
}

/** Ghi đoạn ra WAV 16-bit mono (test không cần FLAC: client giả không đọc âm thanh). */
object WavChunkEncoder : ChunkEncoder {
    override fun encode(pcm: Pcm16File, range: SampleRange, sampleRate: Int, out: File) {
        val shorts = ShortArray(range.length.toInt())
        pcm.readShorts(range.start, shorts, count = shorts.size)
        val data = shorts.size * 2
        val buf = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(data)
        shorts.forEach { buf.putShort(it) }
        val part = File(out.path + ".part")
        part.writeBytes(buf.array())
        out.delete()
        part.renameTo(out)
    }
}

/** Tệp nguồn tổng hợp; đếm số lần phải xử lý âm thanh để kiểm cache. */
class SyntheticSource(
    private val name: String = "bai giang.wav",
    private val samples: FloatArray = makeAudio(),
    private val modifiedMillis: Long = 1_789_660_800_000,
    private val mime: String? = null,
    private val noAudio: Boolean = false,
) : ChunkSource {
    var prepareCalls = 0
    var lastClean: CleanSettings? = null
    var lastSaveExtracted: Boolean? = null

    override fun info() = SourceInfo("/test/$name", name, samples.size * 2L + 44, modifiedMillis, mime)

    override fun prepare(
        folder: File,
        digest: String,
        chunk: ChunkSettings,
        clean: CleanSettings,
        saveClean: Boolean,
        saveExtracted: Boolean,
        audioTrack: String,
        listener: SourceListener,
    ): ChunkList {
        prepareCalls++
        lastClean = clean
        if (noAudio) throw NoAudioTrackException("Video $name không có luồng âm thanh nào.")
        // Giả bước giải mã để kiểm D66: xong thì goc.pcm bị xoá.
        File(folder, ChunkCache.RAW_PCM_FILE).writeBytes(ByteArray(16))
        return ChunkPreparer(chunk, WavChunkEncoder, "wav").prepare(
            FloatArraySource(samples), SR, info().id, folder,
            object : PrepareListener {
                override fun onProgress(step: PrepareStep, fraction: Double) =
                    listener.onProgress((step.ordinal + fraction) / PrepareStep.entries.size, step.name)

                override fun checkCancelled() = listener.checkCancelled()
            },
            digest,
            clean = clean,
            log = listener::log,
        ).also {
            // "Mã hoá" âm thanh sạch: chép sach.pcm (test không có FLAC).
            if (saveClean) File(folder, ChunkPreparer.CLEAN_PCM_FILE).copyTo(File(folder, ChunkSource.CLEAN_AUDIO_FILE), true)
            if (saveExtracted) File(folder, ChunkCache.RAW_PCM_FILE).copyTo(File(folder, ChunkSource.EXTRACTED_AUDIO_FILE), true)
            lastSaveExtracted = saveExtracted
        }
    }
}

/** Một lần gọi client giả: tệp, prompt và tham số như `FakeClient.calls` của test desktop. */
data class Call(
    val file: String,
    val prompt: String?,
    val model: String,
    val maxAttempts: Int,
    val options: TranscribeOptions = TranscribeOptions(),
    /** Nội dung tệp bản đồ người nói gửi kèm (`ban_do` của test desktop). */
    val map: String? = null,
)

/**
 * Client giả như `FakeClient` của tests/test_transcribe.py: [reply] (số thứ tự lần gọi, model) trả về Interaction
 * hoặc lỗi để ném.
 */
class FakeClient(
    override val tracker: QuotaTracker = QuotaTracker(),
    private val reply: (call: Int, model: String) -> Any,
) : ChunkTranscriber {
    val calls = mutableListOf<Call>()

    override suspend fun transcribeRaw(
        audio: File,
        cfg: GeminiConfig,
        prompt: String?,
        audioSeconds: Double?,
        options: TranscribeOptions,
    ): JsonObject {
        val model = options.model ?: cfg.model
        calls += Call(audio.name, prompt, model, options.maxAttempts, options, options.attachment?.readText(Charsets.UTF_8))
        return when (val r = reply(calls.size, model)) {
            is Exception -> throw r
            else -> r as JsonObject
        }
    }
}

/** Interaction chỉ có chữ, như `text()` của test desktop. */
fun text(s: String): JsonObject = buildJsonObject {
    put("status", "completed")
    putJsonArray("steps") {
        add(
            buildJsonObject {
                put("type", "model_output")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", JsonPrimitive(s)) }) })
            },
        )
    }
}

/**
 * Cấu hình như setUp của TestTranscribe: ghim model và thứ tự dự phòng, đoạn 0,4 phút, LF. Tắt các bước làm sạch đầy đủ
 * (Stage 7) để các ca cũ giữ nguyên đầu ra; ca làm sạch đủ tự bật.
 */
fun testConfig() = PipelineConfig(
    newline = "lf",
    clean = CleanSettings.OFF,
    chunk = ChunkSettings(
        minSilenceLen = 1000, silenceThreshOffset = 20, keepSilence = 400,
        chunkMinutes = 0.4, searchSeconds = 3.0, minLastChunkSeconds = 5.0,
    ),
    gemini = GeminiConfig(
        model = "gemini-3.5-transcribe",
        fallbackModels = listOf("gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.5-transcribe"),
        tokenRateLimit = 0,
    ),
)

/** `[(chữ, nhãn, bắt đầu, kết thúc)]` → Interaction có `word_info`, như `words()` của test desktop. */
fun words(items: List<Triple<String, String?, Pair<Double, Double>>>): JsonObject = buildJsonObject {
    put("status", "completed")
    putJsonArray("steps") {
        add(
            buildJsonObject {
                put("type", "model_output")
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", items.joinToString(" ") { it.first })
                                putJsonArray("annotations") {
                                    for ((w, spk, ab) in items) add(
                                        buildJsonObject {
                                            put("type", "word_info")
                                            put("text", w)
                                            put("speaker", spk)
                                            put("start_offset", "${ab.first}s")
                                            put("end_offset", "${ab.second}s")
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
        )
    }
}

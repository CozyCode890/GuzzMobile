package io.github.cozycode890.guzz.server

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationCallback
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.speaker.DiarizationSettings
import io.github.cozycode890.guzz.core.speaker.LabeledTurn
import io.github.cozycode890.guzz.core.speaker.Piece
import io.github.cozycode890.guzz.core.speaker.PieceEngine
import io.github.cozycode890.guzz.core.speaker.SherpaPiece
import java.io.Closeable
import java.io.File

/**
 * Bản server của `SherpaPieceDiarizer` (Stage 11 C4): sherpa-onnx 2.0.1 qua Java API (`sherpa-onnx-jvm` + JNI
 * linux-x64 / win-x64), cùng model và cấu hình D74, D80, D81; phần dựng [Piece] dùng chung ([SherpaPiece]). Khác
 * điện thoại: chạy ngay trong process (container chỉ làm một job), số luồng = [DiarizationSettings.threads] mà server đặt
 * theo số nhân của container (không nằm trong mã cache).
 *
 * @param modelDir thư mục có `<segmentation>.onnx` và `<embedding>.onnx`.
 */
class SherpaJvmPieceEngine(
    modelDir: File,
    private val settings: DiarizationSettings,
) : PieceEngine, Closeable {
    private val segmentationPath = File(modelDir, "${settings.segmentation}.onnx").absolutePath
    private val embeddingPath = File(modelDir, "${settings.embedding}.onnx").absolutePath
    private val byClusters = HashMap<Int, OfflineSpeakerDiarization>()
    private var extractorOrNull: SpeakerEmbeddingExtractor? = null

    init {
        for (p in listOf(segmentationPath, embeddingPath)) require(File(p).isFile) { "Thiếu model $p" }
    }

    private fun embeddingConfig() = SpeakerEmbeddingExtractorConfig.builder()
        .setModel(embeddingPath).setNumThreads(settings.threads).setDebug(false).build()

    private val extractor: SpeakerEmbeddingExtractor
        get() = extractorOrNull ?: SpeakerEmbeddingExtractor(embeddingConfig()).also { extractorOrNull = it }

    private fun config(numClusters: Int) = OfflineSpeakerDiarizationConfig.builder()
        .setSegmentation(
            OfflineSpeakerSegmentationModelConfig.builder()
                .setPyannote(
                    OfflineSpeakerSegmentationPyannoteModelConfig.builder()
                        .setModel(segmentationPath).setWindowShiftRatio(settings.windowShiftRatio).build(),
                )
                .setNumThreads(settings.threads)
                .setDebug(false)
                .build(),
        )
        .setEmbedding(embeddingConfig())
        .setClustering(
            FastClusteringConfig.builder()
                .setNumClusters(if (numClusters > 0) numClusters else -1)
                .setThreshold(settings.threshold.toFloat())
                .build(),
        )
        .setMinDurationOn(settings.minDurationOn)
        .setMinDurationOff(settings.minDurationOff)
        .build()

    private fun engine(numClusters: Int): OfflineSpeakerDiarization =
        byClusters.getOrPut(numClusters.coerceAtLeast(0)) { OfflineSpeakerDiarization(config(numClusters)) }

    override suspend fun run(pcm: File, a: Long, b: Long, numClusters: Int, onProgress: (Int, Int) -> Unit): Piece =
        runBlocking(pcm, a, b, numClusters, onProgress)

    /** Tách giọng mẫu [a, b) của [pcm], chặn luồng gọi (sherpa-onnx không dừng giữa chừng được, Stage 1 D7). */
    fun runBlocking(pcm: File, a: Long, b: Long, numClusters: Int, onProgress: (Int, Int) -> Unit): Piece {
        val samples = FloatArray((b - a).toInt())
        Pcm16File(pcm).use { it.read(a, samples, 0, samples.size) }
        val raw = engine(numClusters).processWithCallback(samples, Progress(onProgress), 0L)
            .map { LabeledTurn(it.start.toDouble(), it.end.toDouble(), it.speaker) }
        return SherpaPiece.build(samples, a, b, settings.sampleRate, raw, ::embedding)
    }

    private fun embedding(all: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(all, settings.sampleRate)
            stream.inputFinished()
            if (!extractor.isReady(stream)) return null
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    override fun close() {
        byClusters.values.forEach { it.release() }
        byClusters.clear()
        extractorOrNull?.release()
        extractorOrNull = null
    }

    /** Java API nhận interface riêng (không phải hàm Kotlin như AAR); giá trị trả về bị bỏ qua. */
    private class Progress(private val onProgress: (Int, Int) -> Unit) : OfflineSpeakerDiarizationCallback {
        override fun invoke(done: Int, total: Int, arg: Long): Int {
            onProgress(done, total)
            return 0
        }
    }
}

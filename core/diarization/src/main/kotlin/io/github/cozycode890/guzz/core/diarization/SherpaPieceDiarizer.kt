package io.github.cozycode890.guzz.core.diarization

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.speaker.DiarizationSettings
import io.github.cozycode890.guzz.core.speaker.LabeledTurn
import io.github.cozycode890.guzz.core.speaker.Piece
import io.github.cozycode890.guzz.core.speaker.SherpaPiece
import java.io.Closeable
import java.io.File

/**
 * Tách giọng một phần bằng sherpa-onnx 2.0.1 (chạy trong process `:diarize`). Cấu hình như `run_sherpa.py` của Stage 1:
 * segmentation-3.0, CAM++ zh_en advanced, `windowShiftRatio` 0,1, `minDurationOn` 0,2 / `minDurationOff` 0,5, 4 luồng.
 * Model đọc từ assets (`sherpa/…`) qua [AssetManager]; test thiết bị truyền `null` và đường dẫn tệp.
 *
 * Không an toàn đa luồng: process `:diarize` chỉ chạy mỗi lần một phần.
 */
class SherpaPieceDiarizer(
    private val assets: AssetManager?,
    private val settings: DiarizationSettings = DiarizationSettings(),
    private val segmentationPath: String = "$ASSET_DIR/${settings.segmentation}.onnx",
    private val embeddingPath: String = "$ASSET_DIR/${settings.embedding}.onnx",
) : Closeable {
    private val byClusters = HashMap<Int, OfflineSpeakerDiarization>()
    private var extractorOrNull: SpeakerEmbeddingExtractor? = null
    private val extractor: SpeakerEmbeddingExtractor
        get() = extractorOrNull ?: SpeakerEmbeddingExtractor(
            assets, SpeakerEmbeddingExtractorConfig(model = embeddingPath, numThreads = settings.threads),
        ).also { extractorOrNull = it }

    private fun config(numClusters: Int) = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                model = segmentationPath, windowShiftRatio = settings.windowShiftRatio,
            ),
            numThreads = settings.threads,
        ),
        embedding = SpeakerEmbeddingExtractorConfig(model = embeddingPath, numThreads = settings.threads),
        clustering = FastClusteringConfig(
            numClusters = if (numClusters > 0) numClusters else -1,
            threshold = settings.threshold.toFloat(),
        ),
        minDurationOn = settings.minDurationOn,
        minDurationOff = settings.minDurationOff,
    )

    private fun engine(numClusters: Int): OfflineSpeakerDiarization =
        byClusters.getOrPut(numClusters.coerceAtLeast(0)) { OfflineSpeakerDiarization(assets, config(numClusters)) }

    /**
     * Tách giọng mẫu [a, b) của [pcm]: lượt nói đã bỏ chồng lấn (giây trên toàn bài) và embedding trung bình (≤ 30 s
     * tiếng nói, chuẩn hoá L2) của từng nhãn, như `run_sherpa.py`. Phần dựng kết quả dùng chung với server ([SherpaPiece]).
     */
    fun run(pcm: File, a: Long, b: Long, numClusters: Int, onProgress: (Int, Int) -> Unit): Piece {
        val sr = settings.sampleRate
        val samples = FloatArray((b - a).toInt())
        Pcm16File(pcm).use { it.read(a, samples, 0, samples.size) }
        val raw = engine(numClusters).processWithCallback(samples, ProgressCallback(onProgress), 0L)
            .map { LabeledTurn(it.start.toDouble(), it.end.toDouble(), it.speaker) }
        return SherpaPiece.build(samples, a, b, sr, raw, ::embedding)
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

    /**
     * Callback tiến độ phải là lớp viết rõ, không dùng lambda: JNI của sherpa-onnx tìm method
     * `invoke(IIJ)Ljava/lang/Integer;`, mà lambda của Kotlin 2.x chỉ có bản `invoke(Object, Object, Object)`, nên JNI
     * ném NoSuchMethodError và process bị abort (Stage 1 D2). Giá trị trả về bị sherpa-onnx bỏ qua (Stage 1 D7): muốn
     * dừng thì giết process `:diarize`.
     */
    private class ProgressCallback(private val onProgress: (Int, Int) -> Unit) : (Int, Int, Long) -> Int {
        override fun invoke(done: Int, total: Int, arg: Long): Int {
            onProgress(done, total)
            return 0
        }
    }

    companion object {
        const val ASSET_DIR = "sherpa"
    }
}

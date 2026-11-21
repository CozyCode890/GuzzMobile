package io.github.cozycode890.guzz.core.audio

import io.github.cozycode890.guzz.core.audio.dsp.NoiseReducer
import io.github.cozycode890.guzz.core.audio.pcm.F32File
import io.github.cozycode890.guzz.core.audio.pcm.F32Writer
import io.github.cozycode890.guzz.core.audio.pcm.SampleSource
import java.io.Closeable
import java.io.File

/**
 * Các bước làm sạch đầy đủ của Stage 7, trước chuẩn hoá và cắt lặng. Port phần giữa của audio_processing.py:
 * `clean_audio`, đúng thứ tự desktop: lọc dải tần → ghìm tiếng động (lượt 1) → khử ồn → ghìm tiếng động (lượt 2) →
 * cân bằng âm lượng. `denoise = false` thì bỏ lọc dải tần, khử ồn và lượt ghìm 1 nhưng vẫn ghìm một lượt và cân bằng
 * âm lượng nếu bật, như desktop.
 *
 * Dòng chảy (D43): mỗi bước đọc nguồn theo khối và ghi `tam_NN.f32` mới, xong bước sau thì xoá tệp của bước trước. Bước
 * bị bỏ qua (tắt, hay desktop trả nguyên tín hiệu) không ghi tệp nào. [close] xoá tệp cuối cùng.
 *
 * @param log nhận các dòng nhật ký như `report` của desktop (đã dịch, có dấu).
 */
class AudioCleaner(
    private val settings: CleanSettings,
    private val sampleRate: Int,
    private val folder: File,
    private val listener: PrepareListener = PrepareListener.NONE,
    private val log: (String) -> Unit = {},
    /** Cỡ khối của noisereduce; chỉ test mới đổi. */
    private val noiseChunk: Pair<Int, Int> = 600_000 to 30_000,
) : Closeable {
    private var current: F32File? = null
    private var counter = 0

    /** Chạy các bước trên [raw]; trả nguồn đã làm sạch (chính [raw] nếu không bước nào chạy). */
    fun clean(raw: SampleSource): SampleSource {
        var src: SampleSource = raw
        val s = settings
        if (s.denoise) {
            log("[2/6] Đang lọc dải tần giọng nói (bandpass ${s.bandpassLow}-${s.bandpassHigh} Hz)...")
            src = step(src, PrepareStep.BANDPASS) { input, sink, progress ->
                Bandpass.filter(input, sampleRate, s.bandpassLow, s.bandpassHigh, sink, progress)
            }
        } else {
            log("[2/6] Lọc dải tần: tắt, bỏ qua.")
        }

        // Ghìm hai lượt quanh khử ồn (lý do: comment trong clean_audio của desktop).
        val clamp = s.transientClampDb > 0
        var regionsBefore: List<ClampRegion> = emptyList()
        if (clamp) {
            log("   Đang ghìm tiếng động đột ngột (to hơn mức thường ${fmt(s.transientClampDb)} dB)...")
            if (s.denoise) {
                src = step(src, PrepareStep.TRANSIENTS, part = 0 to 2) { input, sink, progress ->
                    val r = TransientSuppressor(sampleRate, s.transientClampDb).apply(input, sink, progress)
                    regionsBefore = r ?: emptyList()
                    !r.isNullOrEmpty()
                }
            }
        }

        if (s.denoise) {
            log("[3/6] Đang giảm tiếng ồn nền...")
            src = step(src, PrepareStep.DENOISE) { input, sink, progress ->
                val reducer = NoiseReducer(
                    sampleRate, maxOf(0.2, s.noiseSampleSec), s.propDecrease,
                    chunkSize = noiseChunk.first, padding = noiseChunk.second,
                )
                reducer.process(input, { buf, n -> sink.write(buf, n) }) { done, total ->
                    progress.update(done.toDouble() / total)
                }
                true
            }
        } else {
            log("[3/6] Giảm tiếng ồn nền: tắt, bỏ qua.")
        }

        if (clamp) {
            var regionsAfter: List<ClampRegion> = emptyList()
            src = step(src, PrepareStep.TRANSIENTS, part = (if (s.denoise) 1 else 0) to (if (s.denoise) 2 else 1)) { input, sink, progress ->
                val r = TransientSuppressor(sampleRate, s.transientClampDb).apply(input, sink, progress)
                regionsAfter = r ?: emptyList()
                !r.isNullOrEmpty()
            }
            val regions = TransientSuppressor.mergeRegions(regionsBefore, regionsAfter)
            if (regions.isNotEmpty()) {
                val loudest = regions.sortedByDescending { it.depthDb }.take(5)
                log(
                    "   Đã ghìm ${regions.size} tiếng động, tổng ${"%.1f".format(regions.sumOf { it.end - it.start })} s, " +
                        "sâu nhất ${"%.0f".format(loudest[0].depthDb)} dB " +
                        "(to nhất lúc ${loudest.joinToString(", ") { TransientSuppressor.formatTimestamp(it.peakAt) }}).",
                )
            } else {
                log("   Không có tiếng động nào vượt mức đó.")
            }
        }

        if (s.levelWindowSec > 0) {
            log("[4/6] Đang cân bằng âm lượng lúc giảng viên đi xa/gần (cửa sổ ${fmt(s.levelWindowSec)} s)...")
            src = step(src, PrepareStep.LEVEL) { input, sink, progress ->
                LevelLoudness.apply(input, sampleRate, s.levelWindowSec, sink, progress)
            }
        } else {
            log("[4/6] Cân bằng âm lượng theo thời gian: tắt, bỏ qua.")
        }
        return src
    }

    /**
     * Chạy một bước từ [input] ra tệp `tam_NN.f32` mới. [body] trả `false` khi không ghi gì (nguồn giữ nguyên): khi đó
     * bỏ tệp mới, dùng tiếp [input]. Xong thì xoá tệp của bước trước. [part] = (lượt thứ mấy, tổng số lượt) của cùng
     * [step] để tiến độ chạy liền.
     */
    private fun step(
        input: SampleSource,
        step: PrepareStep,
        part: Pair<Int, Int> = 0 to 1,
        body: (SampleSource, SampleSink, StepProgress) -> Boolean,
    ): SampleSource {
        listener.onProgress(step, part.first.toDouble() / part.second)
        val file = File(folder, "tam_%02d.f32".format(++counter))
        val writer = F32Writer(file)
        val wrote = try {
            body(input, { buf, n -> writer.write(buf, 0, n) }) { f ->
                listener.checkCancelled()
                listener.onProgress(step, (part.first + f.coerceIn(0.0, 1.0)) / part.second)
            }
        } catch (e: Throwable) {
            writer.abort()
            throw e
        }
        if (!wrote) {
            writer.abort()
            return input
        }
        writer.close()
        check(file.length() == input.size * F32File.BYTES_PER_SAMPLE) { "${file.name}: số mẫu ghi ra khác số mẫu vào" }
        val previous = current
        val next = F32File(file)
        current = next
        previous?.let { it.close(); it.file.delete() }
        return next
    }

    /** Đóng và xoá tệp tạm cuối cùng (gọi sau khi đã ghi xong `sach.pcm`, kể cả khi dừng / lỗi). */
    override fun close() {
        current?.let { it.close(); it.file.delete() }
        current = null
        folder.listFiles { f -> f.name.startsWith("tam_") && (f.name.endsWith(".f32") || f.name.endsWith(".f32.part")) }
            ?.forEach { it.delete() }
    }

    /** Số kiểu `%g` của Python: 15.0 → "15", 1.5 → "1.5". */
    private fun fmt(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
}

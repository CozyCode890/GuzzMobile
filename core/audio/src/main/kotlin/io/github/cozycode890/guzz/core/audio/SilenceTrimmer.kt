package io.github.cozycode890.guzz.core.audio

import io.github.cozycode890.guzz.core.audio.pcm.SampleSource
import io.github.cozycode890.guzz.core.common.audio.TimeMap

/** Khoảng mẫu `[start, end)` trên nguồn. */
data class SampleRange(val start: Long, val end: Long) {
    val length: Long get() = end - start
}

/** Kết quả cắt lặng: các khoảng mẫu giữ lại (theo thứ tự, không chồng nhau) và bản đồ thời gian tương ứng. */
class TrimResult(val kept: List<SampleRange>, val timeMap: TimeMap, val trimmed: Boolean) {
    /** Số mẫu sau khi cắt. */
    val keptSamples: Long get() = kept.sumOf { it.length }
}

/** Cắt các khoảng lặng dài. */
object SilenceTrimmer {

    /**
     * Cắt các đoạn im lặng dài, giữ [keepSilence] ms đệm hai đầu, gộp các đoạn đè lên nhau. Không còn gì để cắt (hoặc
     * cả bài là lặng) thì giữ nguyên cả bài.
     * Port của audio_processing.py: `trim_silence`, cộng phần dựng TimeMap của `clean_audio`.
     *
     * Không ghép âm thanh ở đây (desktop `np.concatenate`): chỉ trả các khoảng giữ lại, bước sau đọc lại nguồn theo các
     * khoảng đó. [source] phải là tín hiệu ĐÃ chuẩn hoá âm lượng, vì ngưỡng tính theo dBFS của chính nó.
     */
    fun trim(
        source: SampleSource,
        sr: Int,
        silenceThreshOffset: Int = 16,
        minSilenceLen: Int = 700,
        keepSilence: Int = 300,
    ): TrimResult {
        val len = source.size
        val whole = listOf(SampleRange(0, len))
        val untrimmed = TrimResult(whole, TimeMap.untrimmed(len.toDouble() / sr), trimmed = false)

        val threshold = Loudness.measureDbfs(source) - silenceThreshOffset
        val ranges = SpeechSegments.find(source, sr, minSilenceLen, threshold)
        if (ranges.isEmpty()) return untrimmed

        val samplesPerMs = sr / 1000.0
        val merged = ArrayList<LongArray>()
        for ((startMs, endMs) in ranges) {
            // int() của Python cắt về 0, kể cả số âm: toLong() cũng vậy.
            val a = maxOf(0L, ((startMs - keepSilence) * samplesPerMs).toLong())
            val b = minOf(len, ((endMs + keepSilence) * samplesPerMs).toLong())
            if (b <= a) continue
            val last = merged.lastOrNull()
            if (last != null && a <= last[1]) {
                last[1] = maxOf(last[1], b)
            } else {
                merged += longArrayOf(a, b)
            }
        }
        if (merged.isEmpty() || (merged.size == 1 && merged[0][0] == 0L && merged[0][1] == len)) return untrimmed

        val kept = merged.map { SampleRange(it[0], it[1]) }
        val timeMap = TimeMap(kept.map { it.start.toDouble() / sr to it.end.toDouble() / sr }, len.toDouble() / sr)
        return TrimResult(kept, timeMap, trimmed = true)
    }
}

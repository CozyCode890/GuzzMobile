package io.github.cozycode890.guzz.core.audio

import io.github.cozycode890.guzz.core.audio.pcm.SampleSource
import io.github.cozycode890.guzz.core.audio.pcm.forEachBlock
import kotlin.math.log10
import kotlin.math.pow

/** Đo và chỉnh âm lượng tổng thể. Port các hàm âm lượng của audio_processing.py. */
object Loudness {

    /**
     * dBFS trung bình của cả nguồn, cùng cách tính như pydub; nguồn rỗng hoặc toàn số 0 thì `-∞`.
     * Port của audio_processing.py: `measure_dbfs` (kèm `_sum_squares`).
     *
     * Desktop cộng bình phương bằng `np.dot` float32 theo khối (BLAS, thứ tự cộng tuỳ máy) nên không tái tạo đúng từng bit
     * được; ở đây cộng bằng double. Lệch cỡ 1e-5 dB, chỉ đổi kết quả khi một cửa sổ nằm sát ngưỡng im lặng.
     */
    fun measureDbfs(source: SampleSource): Double {
        if (source.size == 0L) return Double.NEGATIVE_INFINITY
        var total = 0.0
        source.forEachBlock { buf, n, _ ->
            var block = 0.0
            for (i in 0 until n) {
                val v = buf[i].toDouble()
                block += v * v
            }
            total += block
        }
        // Python: (tổng / số mẫu) ** 0.5, tức pow chứ không phải sqrt.
        val rms = (total / source.size).pow(0.5)
        return if (rms > 0) 20.0 * log10(rms) else Double.NEGATIVE_INFINITY
    }

    /**
     * Hệ số khuếch đại (float32) để âm lượng tổng thể về [targetDbfs]; `null` khi nguồn câm (dBFS = −∞), desktop khi đó
     * trả nguyên tín hiệu. Port phần tính hệ số của audio_processing.py: `boost_and_normalize`.
     */
    fun gainFor(currentDbfs: Double, targetDbfs: Double): Float? {
        if (currentDbfs == Double.NEGATIVE_INFINITY) return null
        return 10.0.pow((targetDbfs - currentDbfs) / 20.0).toFloat()
    }
}

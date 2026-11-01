package io.github.cozycode890.guzz.spike.media

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Đổi tần số lấy mẫu bằng windowed-sinc polyphase, cửa sổ Kaiser, Kotlin thuần (D38). Chạy theo khối: [process] nhận
 * từng khối mẫu, [flush] lấy nốt phần cuối. Lọc đối xứng quanh vị trí của mẫu ra nên không có độ trễ: mẫu ra thứ n ứng
 * với thời điểm n / outRate của đầu vào (trước mẫu đầu tiên coi như là 0).
 *
 * @param zeroCrossings số lần sinc cắt 0 mỗi bên, nhiều thì dốc hơn mà chậm hơn.
 * @param rolloff tần số cắt so với Nyquist của bên có tần số thấp hơn (0,94 × 8 kHz = 7,52 kHz khi ra 16 kHz).
 * @param beta tham số Kaiser; 8,6 cho dải chặn khoảng −86 dB.
 */
class SincResampler(
    val inRate: Int,
    val outRate: Int,
    val zeroCrossings: Int = 32,
    val rolloff: Double = 0.94,
    val beta: Double = 8.6,
) {
    // Khai báo trước init: init dựng bảng hệ số bằng kaiser(), cần giá trị này.
    private val i0Beta = bessel0(beta)
    private val up: Int
    private val down: Int
    private val half: Int
    private val taps: Array<FloatArray>

    // Bộ đệm đầu vào; buf[0] là mẫu có chỉ số tuyệt đối bufStart (âm lúc đầu: các số 0 đệm trước bài).
    private var buf = FloatArray(1 shl 16)
    private var bufLen = 0
    private var bufStart = 0L
    private var received = 0L
    private var produced = 0L

    init {
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        // Tần số cắt tính theo Nyquist của đầu vào; nhân ρ cho đáp ứng xung h(t) = ρ·sinc(ρt), DC = 1.
        val rho = min(1.0, outRate.toDouble() / inRate) * rolloff
        val halfWidth = zeroCrossings / rho
        half = ceil(halfWidth).toInt()
        taps = Array(up) { p ->
            val frac = p.toDouble() / up
            val row = DoubleArray(2 * half) { m ->
                val t = frac + (half - 1) - m
                val x = t / halfWidth
                if (x <= -1.0 || x >= 1.0) 0.0 else rho * sinc(rho * t) * kaiser(x)
            }
            // Mỗi pha chuẩn hoá tổng hệ số = 1 để DC đi qua đúng biên độ ở mọi pha.
            val s = row.sum()
            FloatArray(row.size) { (row[it] / s).toFloat() }
        }
        bufLen = half - 1
        bufStart = -(half - 1).toLong()
    }

    /** Thêm [n] mẫu từ [input]; trả về các mẫu ra tính được tới giờ. */
    fun process(input: FloatArray, n: Int = input.size): FloatArray {
        if (inRate == outRate) return input.copyOf(n)
        append(input, n)
        received += n
        return produce(Long.MAX_VALUE)
    }

    /** Hết đầu vào: đệm số 0 rồi lấy nốt, tổng số mẫu ra = ceil(số mẫu vào × outRate / inRate). */
    fun flush(): FloatArray {
        if (inRate == outRate) return FloatArray(0)
        val expected = (received * up + down - 1) / down
        append(FloatArray(half + 1), half + 1)
        return produce(expected)
    }

    private fun produce(limit: Long): FloatArray {
        val out = FloatArray(maxOf(0, ((bufStart + bufLen) * up / down - produced + 2).toInt()))
        var k = 0
        while (produced < limit) {
            val pos = produced * down
            val i = pos / up
            val p = (pos % up).toInt()
            if (i + half > bufStart + bufLen - 1) break
            val row = taps[p]
            val base = (i - half + 1 - bufStart).toInt()
            var acc = 0f
            for (m in row.indices) acc += row[m] * buf[base + m]
            if (k == out.size) break
            out[k++] = acc
            produced++
        }
        compact((produced * down) / up - half + 1)
        return if (k == out.size) out else out.copyOf(k)
    }

    private fun append(input: FloatArray, n: Int) {
        if (bufLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + n))
        input.copyInto(buf, bufLen, 0, n)
        bufLen += n
    }

    /** Bỏ các mẫu trước chỉ số tuyệt đối [keepFrom], vì không mẫu ra nào sau này cần tới. */
    private fun compact(keepFrom: Long) {
        val drop = (keepFrom - bufStart).toInt()
        if (drop <= 0 || drop < buf.size / 2) return
        buf.copyInto(buf, 0, drop, bufLen)
        bufLen -= drop
        bufStart += drop
    }

    private companion object {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        /** Hàm Bessel loại 1 bậc 0, chuỗi luỹ thừa. */
        fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            var k = 1
            while (term > 1e-12 * sum) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }

    private fun kaiser(x: Double): Double = bessel0(beta * sqrt(1 - x * x)) / i0Beta
}

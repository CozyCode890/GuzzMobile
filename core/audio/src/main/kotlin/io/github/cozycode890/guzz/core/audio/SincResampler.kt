package io.github.cozycode890.guzz.core.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Đổi tần số lấy mẫu bằng windowed-sinc polyphase, cửa sổ Kaiser, Kotlin thuần (D40). Chạy theo khối: [process] nhận
 * từng khối mẫu, [flush] lấy nốt phần cuối. Lọc đối xứng quanh vị trí của mẫu ra nên không có độ trễ: mẫu ra thứ n ứng
 * với thời điểm n / outRate của đầu vào (trước mẫu đầu tiên coi như là 0).
 *
 * Port của bản spike Stage 1 (`spikes/android/.../media/SincResampler.kt`), viết lại cho nhanh: bộ đệm ra do người gọi
 * cấp (không cấp phát mỗi khối), vòng nhân cộng 4 tổng riêng để CPU không phải chờ từng phép cộng trước, hàng hệ số đệm
 * số 0 cho đủ bội 4. Trên PC (Ryzen 7 7435HS) đổi 2 giờ 48 kHz → 16 kHz mất khoảng 10 s, tức hơn 600 lần thời gian thực.
 *
 * Tỉ lệ rút gọn up / down (48 k → 16 k là 1 / 3, 44,1 k → 16 k là 160 / 441). Bảng hệ số tính sẵn cho `up` pha; tần số
 * lạ làm `up` > [MAX_TABLE_PHASES] thì tính hệ số tại chỗ cho từng mẫu ra (chậm, xem [usesPhaseTable]).
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
    init {
        require(inRate > 0 && outRate > 0) { "tần số phải dương: $inRate → $outRate" }
    }

    private val i0Beta = bessel0(beta)
    private val up: Int
    private val down: Int
    private val rho: Double
    private val halfWidth: Double
    private val half: Int

    /** Số hệ số mỗi pha, đã làm tròn lên bội 4 (phần thừa là số 0). */
    private val tapCount: Int
    private val taps: Array<FloatArray>?
    private val scratchRow: FloatArray

    // Bộ đệm đầu vào; buf[0] là mẫu có chỉ số tuyệt đối bufStart (âm lúc đầu: các số 0 đệm trước bài).
    private var buf = FloatArray(1 shl 16)
    private var bufLen = 0
    private var bufStart = 0L
    private var received = 0L
    private var produced = 0L
    private var flushed = false

    /** `false` khi tần số lạ quá, hệ số phải tính tại chỗ cho từng mẫu ra (chậm hơn nhiều lần). */
    val usesPhaseTable: Boolean

    val isPassthrough: Boolean get() = inRate == outRate

    init {
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        // Tần số cắt tính theo Nyquist của đầu vào; nhân ρ cho đáp ứng xung h(t) = ρ·sinc(ρt), DC = 1.
        rho = min(1.0, outRate.toDouble() / inRate) * rolloff
        halfWidth = zeroCrossings / rho
        half = ceil(halfWidth).toInt()
        tapCount = (2 * half + 3) / 4 * 4
        usesPhaseTable = up <= MAX_TABLE_PHASES
        taps = if (usesPhaseTable && !isPassthrough) Array(up) { p -> row(p.toDouble() / up) } else null
        scratchRow = FloatArray(tapCount)
        bufLen = half - 1
        bufStart = -(half - 1).toLong()
    }

    /** Hệ số cho mẫu ra lệch [frac] (0 ≤ frac < 1) sau mẫu vào gần nhất phía trước. */
    private fun row(frac: Double, into: FloatArray = FloatArray(tapCount)): FloatArray {
        val row = DoubleArray(2 * half) { m ->
            val t = frac + (half - 1) - m
            val x = t / halfWidth
            if (x <= -1.0 || x >= 1.0) 0.0 else rho * sinc(rho * t) * kaiser(x)
        }
        // Mỗi pha chuẩn hoá tổng hệ số = 1 để DC đi qua đúng biên độ ở mọi pha.
        val s = row.sum()
        for (m in row.indices) into[m] = (row[m] / s).toFloat()
        for (m in row.size until tapCount) into[m] = 0f
        return into
    }

    /** Số mẫu ra tối đa [process] có thể trả về khi nhận thêm [count] mẫu. */
    fun maxOutput(count: Int): Int =
        if (isPassthrough) count else ((bufStart + bufLen + count + tapCount) * up / down - produced + 2).toInt()

    /**
     * Thêm `input[offset until offset + count]`; ghi các mẫu ra tính được tới giờ vào `output` từ `outOffset`, trả về số
     * mẫu đã ghi. `output` phải còn ít nhất [maxOutput]`(count)` chỗ.
     */
    fun process(input: FloatArray, offset: Int, count: Int, output: FloatArray, outOffset: Int = 0): Int {
        check(!flushed) { "đã flush" }
        if (isPassthrough) {
            input.copyInto(output, outOffset, offset, offset + count)
            received += count
            return count
        }
        append(input, offset, count)
        received += count
        return produce(Long.MAX_VALUE, output, outOffset)
    }

    /** Tiện cho test: cấp phát mảng ra. */
    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        val out = FloatArray(maxOutput(count))
        return out.copyOf(process(input, 0, count, out))
    }

    /** Số mẫu ra tối đa của [flush]. */
    fun maxFlushOutput(): Int = if (isPassthrough) 0 else maxOutput(half + 1 + tapCount)

    /** Hết đầu vào: đệm số 0 rồi lấy nốt; tổng số mẫu ra = ceil(số mẫu vào × outRate / inRate). */
    fun flush(output: FloatArray, outOffset: Int = 0): Int {
        check(!flushed) { "đã flush" }
        flushed = true
        if (isPassthrough) return 0
        val expected = (received * up + down - 1) / down
        val pad = half + 1 + tapCount
        append(FloatArray(pad), 0, pad)
        return produce(expected, output, outOffset)
    }

    fun flush(): FloatArray {
        val out = FloatArray(maxFlushOutput())
        return out.copyOf(flush(out))
    }

    private fun produce(limit: Long, out: FloatArray, outOffset: Int): Int {
        var k = outOffset
        val available = bufStart + bufLen
        while (produced < limit) {
            val pos = produced * down
            val i = pos / up
            val p = (pos % up).toInt()
            val base = i - half + 1
            // Cần đủ tapCount mẫu bắt đầu từ base (kể cả phần hệ số 0 đệm thêm).
            if (base + tapCount > available) break
            val row = taps?.get(p) ?: row(p.toDouble() / up, scratchRow)
            out[k++] = dot(row, buf, (base - bufStart).toInt())
            produced++
        }
        compact((produced * down) / up - half + 1)
        return k - outOffset
    }

    private fun dot(row: FloatArray, x: FloatArray, from: Int): Float {
        // 4 tổng riêng: phép cộng sau không phải chờ phép cộng trước. Trên PC nhanh hơn bản spike (1 tổng) 2,4 lần; 8 tổng
        // không nhanh hơn nữa.
        var a0 = 0f
        var a1 = 0f
        var a2 = 0f
        var a3 = 0f
        var m = 0
        var j = from
        while (m < tapCount) {
            a0 += row[m] * x[j]
            a1 += row[m + 1] * x[j + 1]
            a2 += row[m + 2] * x[j + 2]
            a3 += row[m + 3] * x[j + 3]
            m += 4
            j += 4
        }
        return (a0 + a1) + (a2 + a3)
    }

    private fun append(input: FloatArray, offset: Int, n: Int) {
        if (bufLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + n))
        input.copyInto(buf, bufLen, offset, offset + n)
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

    private fun kaiser(x: Double): Double = bessel0(beta * sqrt(1 - x * x)) / i0Beta

    companion object {
        /** Quá số pha này thì không dựng bảng (bảng sẽ nặng hàng chục MB). 44,1 k → 16 k cần 160 pha. */
        const val MAX_TABLE_PHASES = 1024

        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        /** Hàm Bessel loại 1 bậc 0, chuỗi luỹ thừa. */
        private fun bessel0(x: Double): Double {
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
}

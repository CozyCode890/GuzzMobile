package io.github.cozycode890.guzz.core.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Số phức tối giản cho phần thiết kế bộ lọc (vài chục phép tính, không cần nhanh). */
internal data class Cx(val re: Double, val im: Double = 0.0) {
    operator fun plus(o: Cx) = Cx(re + o.re, im + o.im)
    operator fun minus(o: Cx) = Cx(re - o.re, im - o.im)
    operator fun times(o: Cx) = Cx(re * o.re - im * o.im, re * o.im + im * o.re)
    operator fun times(d: Double) = Cx(re * d, im * d)

    /** Chia như numpy (thuật toán Smith), để thương sai lệch ít nhất có thể. */
    operator fun div(o: Cx): Cx {
        return if (abs(o.re) >= abs(o.im)) {
            val r = o.im / o.re
            val d = o.re + o.im * r
            Cx((re + im * r) / d, (im - re * r) / d)
        } else {
            val r = o.re / o.im
            val d = o.re * r + o.im
            Cx((re * r + im) / d, (im * r - re) / d)
        }
    }

    fun conj() = Cx(re, -im)
    fun abs() = hypot(re, im)
    val isReal get() = im == 0.0

    /** Căn bậc hai nhánh chính như `numpy.sqrt` của số phức. */
    fun sqrt(): Cx {
        if (re == 0.0 && im == 0.0) return Cx(0.0, im)
        val t = sqrt((abs(re) + hypot(re, im)) / 2)
        return if (re >= 0) Cx(t, im / (2 * t)) else Cx(abs(im) / (2 * t), if (im >= 0) t else -t)
    }

    companion object {
        fun expI(theta: Double) = Cx(cos(theta), sin(theta))
    }
}

/**
 * Thiết kế bộ lọc Butterworth số dạng SOS (D84). Port của scipy 1.18.1 `scipy.signal.butter(..., output="sos")`:
 * `buttap` → `lp2lp_zpk` / `lp2bp_zpk` → `bilinear_zpk` → `zpk2sos` (ghép cặp `nearest`). Đúng từng bước của scipy, nên
 * tần số nào cũng ra hệ số như desktop (preset Tuỳ chỉnh).
 */
object Butterworth {

    /** Thông thấp bậc [order], tần số cắt [wn] chuẩn hoá theo Nyquist (0 < wn < 1). */
    fun lowpass(order: Int, wn: Double): Array<DoubleArray> {
        require(wn > 0 && wn < 1) { "Wn phải trong (0, 1): $wn" }
        val (z, p, k) = buttap(order)
        val warped = 4.0 * tan(PI * wn / 2.0)
        // lp2lp_zpk
        val zl = z.map { it * warped }
        val pl = p.map { it * warped }
        val kl = k * Math.pow(warped, (p.size - z.size).toDouble())
        return zpk2sos(bilinear(zl, pl, kl))
    }

    /** Thông dải bậc [order] (ra 2 × order cực), [low] < [high] chuẩn hoá theo Nyquist. */
    fun bandpass(order: Int, low: Double, high: Double): Array<DoubleArray> {
        require(low > 0 && high < 1 && low < high) { "Wn phải 0 < low < high < 1: $low, $high" }
        val (z, p, k) = buttap(order)
        val w0 = 4.0 * tan(PI * low / 2.0)
        val w1 = 4.0 * tan(PI * high / 2.0)
        val bw = w1 - w0
        val wo = sqrt(w0 * w1)
        // lp2bp_zpk
        val degree = p.size - z.size
        val zLp = z.map { it * (bw / 2) }
        val pLp = p.map { it * (bw / 2) }
        val wo2 = Cx(wo * wo)
        val zBp = zLp.map { it + (it * it - wo2).sqrt() } + zLp.map { it - (it * it - wo2).sqrt() } +
            List(degree) { Cx(0.0) }
        val pBp = pLp.map { it + (it * it - wo2).sqrt() } + pLp.map { it - (it * it - wo2).sqrt() }
        val kBp = k * Math.pow(bw, degree.toDouble())
        return zpk2sos(bilinear(zBp, pBp, kBp))
    }

    private data class Zpk(val z: List<Cx>, val p: List<Cx>, val k: Double)

    /** Port của `buttap`: cực −e^{iπm/(2N)}, m = −N+1, −N+3, …, N−1 (m = 0 cho đúng cực thực −1). */
    private fun buttap(n: Int): Zpk {
        require(n >= 1) { "bậc phải ≥ 1" }
        val p = (0 until n).map { i ->
            val m = (-n + 1 + 2 * i).toDouble()
            val e = Cx.expI(PI * m / (2 * n))
            Cx(-e.re, -e.im)
        }
        return Zpk(emptyList(), p, 1.0)
    }

    /** Port của `bilinear_zpk` với fs = 2 (fs2 = 4). */
    private fun bilinear(z: List<Cx>, p: List<Cx>, k: Double): Zpk {
        val fs2 = Cx(4.0)
        val degree = p.size - z.size
        val zz = z.map { (fs2 + it) / (fs2 - it) } + List(degree) { Cx(-1.0) }
        val pz = p.map { (fs2 + it) / (fs2 - it) }
        var num = Cx(1.0)
        for (v in z) num *= (fs2 - v)
        var den = Cx(1.0)
        for (v in p) den *= (fs2 - v)
        return Zpk(zz, pz, k * (num / den).re)
    }

    /** Port của `_cplxreal`: (một nửa các cặp liên hợp, đã lấy trung bình; các số thực), mỗi nhóm đã sắp. */
    private fun cplxreal(input: List<Cx>): Pair<List<Cx>, List<Double>> {
        if (input.isEmpty()) return emptyList<Cx>() to emptyList()
        val tol = 100 * Math.ulp(1.0)
        // lexsort((|imag|, real)): sắp theo real rồi |imag|; sortedWith ổn định như lexsort.
        val z = input.sortedWith(compareBy<Cx>({ it.re }, { abs(it.im) }))
        val realMask = z.map { abs(it.im) <= tol * it.abs() }
        val zr = z.filterIndexed { i, _ -> realMask[i] }.map { it.re }
        if (zr.size == z.size) return emptyList<Cx>() to zr
        val rest = z.filterIndexed { i, _ -> !realMask[i] }
        val zp = rest.filter { it.im > 0 }.toMutableList()
        val zn = rest.filter { it.im < 0 }.toMutableList()
        require(zp.size == zn.size) { "Số phức không có cặp liên hợp" }
        // Các đoạn cùng phần thực (gần đúng) thì sắp theo |imag|.
        var start = 0
        while (start < zp.size) {
            var stop = start
            while (stop + 1 < zp.size && zp[stop + 1].re - zp[stop].re <= tol * zp[stop].abs()) stop++
            if (stop > start) {
                val a = zp.subList(start, stop + 1).sortedBy { abs(it.im) }
                val b = zn.subList(start, stop + 1).sortedBy { abs(it.im) }
                for (i in a.indices) {
                    zp[start + i] = a[i]
                    zn[start + i] = b[i]
                }
            }
            start = stop + 1
        }
        val zc = zp.indices.map { i -> (zp[i] + zn[i].conj()) * 0.5 }
        return zc to zr
    }

    /** Port của `zpk2sos(z, p, k, pairing="nearest")` cho hệ số (digital). Mỗi hàng: b0 b1 b2 a0 a1 a2. */
    private fun zpk2sos(zpk: Zpk): Array<DoubleArray> {
        var z = zpk.z
        var p = zpk.p
        if (z.isEmpty() && p.isEmpty()) return arrayOf(doubleArrayOf(zpk.k, 0.0, 0.0, 1.0, 0.0, 0.0))
        p = p + List(maxOf(z.size - p.size, 0)) { Cx(0.0) }
        z = z + List(maxOf(p.size - z.size, 0)) { Cx(0.0) }
        val nSections = (maxOf(p.size, z.size) + 1) / 2
        if (p.size % 2 == 1) {
            p = p + Cx(0.0)
            z = z + Cx(0.0)
        }
        val (zc, zr) = cplxreal(z)
        val (pc, pr) = cplxreal(p)
        val zs = (zc + zr.map { Cx(it) }).toMutableList()
        val ps = (pc + pr.map { Cx(it) }).toMutableList()

        // "Tệ nhất" = gần vòng tròn đơn vị nhất; argmin lấy phần tử đầu khi bằng nhau.
        fun idxWorst(list: List<Cx>): Int {
            var best = 0
            for (i in list.indices) if (abs(1 - list[i].abs()) < abs(1 - list[best].abs())) best = i
            return best
        }

        // Port của `_nearest_real_complex_idx`: argsort theo |fro − to| (khoảng cách bằng nhau thì giữ thứ tự).
        fun nearest(fro: List<Cx>, to: Cx, which: String): Int {
            val order = fro.indices.sortedBy { (fro[it] - to).abs() }
            return when (which) {
                "any" -> order[0]
                "real" -> order.first { fro[it].isReal }
                else -> order.first { !fro[it].isReal }
            }
        }

        val sos = Array(nSections) { DoubleArray(6) }
        for (si in nSections - 1 downTo 0) {
            val p1 = ps.removeAt(idxWorst(ps))
            if (p1.isReal && ps.none { it.isReal }) {
                // Trường hợp riêng (1): cực thực cuối cùng.
                val z1 = zs.removeAt(nearest(zs, p1, "real"))
                sos[si] = singleZpkSos(listOf(z1, Cx(0.0)), listOf(p1, Cx(0.0)))
            } else if (ps.size + 1 == zs.size && !p1.isReal && ps.count { it.isReal } == 1 && zs.count { it.isReal } == 1) {
                // Trường hợp riêng (2): còn một cực thực và một không điểm thực, phải ghép với không điểm phức.
                val z1 = zs.removeAt(nearest(zs, p1, "complex"))
                sos[si] = singleZpkSos(listOf(z1, z1.conj()), listOf(p1, p1.conj()))
            } else {
                val p2 = if (p1.isReal) {
                    val realIdx = ps.indices.filter { ps[it].isReal }
                    val idx = realIdx[idxWorst(realIdx.map { ps[it] })]
                    ps.removeAt(idx)
                } else {
                    p1.conj()
                }
                if (zs.isNotEmpty()) {
                    val z1 = zs.removeAt(nearest(zs, p1, "any"))
                    sos[si] = if (!z1.isReal) {
                        singleZpkSos(listOf(z1, z1.conj()), listOf(p1, p2))
                    } else if (zs.isNotEmpty()) {
                        val z2 = zs.removeAt(nearest(zs, p1, "real"))
                        singleZpkSos(listOf(z1, z2), listOf(p1, p2))
                    } else {
                        singleZpkSos(listOf(z1), listOf(p1, p2))
                    }
                } else {
                    sos[si] = singleZpkSos(emptyList(), listOf(p1, p2))
                }
            }
        }
        check(ps.isEmpty() && zs.isEmpty())
        for (i in 0 until 3) sos[0][i] *= zpk.k
        return sos
    }

    /** Port của `_single_zpksos`: hệ số b, a từ nghiệm (np.poly), canh phải vào 3 ô như scipy. */
    private fun singleZpkSos(z: List<Cx>, p: List<Cx>): DoubleArray {
        val sos = DoubleArray(6)
        val b = poly(z)
        val a = poly(p)
        for (i in b.indices) sos[3 - b.size + i] = b[i]
        for (i in a.indices) sos[6 - a.size + i] = a[i]
        return sos
    }

    /** Port của `np.poly`: nhân dồn (1 − z_i x) bằng tích chập, lấy phần thực (nghiệm luôn có cặp liên hợp ở đây). */
    private fun poly(roots: List<Cx>): DoubleArray {
        var a = listOf(Cx(1.0))
        for (r in roots) {
            val next = MutableList(a.size + 1) { Cx(0.0) }
            // np.convolve(a, [1, -r]): c[i] = a[i]*1 + a[i-1]*(-r)
            for (i in next.indices) {
                var s = Cx(0.0)
                if (i < a.size) s += a[i]
                if (i >= 1) s += a[i - 1] * Cx(-r.re, -r.im)
                next[i] = s
            }
            a = next
        }
        return DoubleArray(a.size) { a[it].re }
    }
}

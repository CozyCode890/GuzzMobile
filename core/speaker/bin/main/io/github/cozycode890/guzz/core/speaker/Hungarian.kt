package io.github.cozycode890.guzz.core.speaker

/**
 * Ghép cặp chi phí nhỏ nhất (thuật toán Hungary, O(n²m)) cho ma trận chữ nhật, như
 * `scipy.optimize.linear_sum_assignment`: trả về min(hàng, cột) cặp (hàng, cột), xếp theo hàng tăng dần.
 * Port của app spike Stage 1 (`spikes/android/.../diarize/Hungarian.kt`).
 */
fun linearSumAssignment(cost: Array<DoubleArray>): List<Pair<Int, Int>> {
    val rows = cost.size
    if (rows == 0) return emptyList()
    val cols = cost[0].size
    if (cols == 0) return emptyList()
    if (rows > cols) {
        val t = Array(cols) { j -> DoubleArray(rows) { i -> cost[i][j] } }
        return linearSumAssignment(t).map { (r, c) -> c to r }.sortedBy { it.first }
    }
    // n hàng ≤ m cột; mảng đánh số từ 1, cột 0 là cột giả.
    val n = rows
    val m = cols
    val u = DoubleArray(n + 1)
    val v = DoubleArray(m + 1)
    val p = IntArray(m + 1)
    val way = IntArray(m + 1)
    for (i in 1..n) {
        p[0] = i
        var j0 = 0
        val minv = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        val used = BooleanArray(m + 1)
        do {
            used[j0] = true
            val i0 = p[j0]
            var delta = Double.POSITIVE_INFINITY
            var j1 = 0
            for (j in 1..m) {
                if (!used[j]) {
                    val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minv[j]) {
                        minv[j] = cur
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
            }
            for (j in 0..m) {
                if (used[j]) {
                    u[p[j]] += delta
                    v[j] -= delta
                } else {
                    minv[j] -= delta
                }
            }
            j0 = j1
        } while (p[j0] != 0)
        do {
            val j1 = way[j0]
            p[j0] = p[j1]
            j0 = j1
        } while (j0 != 0)
    }
    val result = ArrayList<Pair<Int, Int>>()
    for (j in 1..m) if (p[j] != 0) result.add(p[j] - 1 to j - 1)
    return result.sortedBy { it.first }
}

package io.github.cozycode890.guzz.core.speaker

/**
 * Phần chung của tách giọng một phần bằng sherpa-onnx, không phụ thuộc kiểu API (Stage 11 B2): bản Android
 * (`SherpaPieceDiarizer`, AAR Kotlin) và bản server (`SherpaJvmPieceEngine`, jar Java) chỉ khác ở chỗ gọi sherpa-onnx,
 * nên phần dựng [Piece] nằm ở đây để hai bên ra cùng kết quả. Như `run_sherpa.py` của Stage 1.
 */
object SherpaPiece {
    /**
     * @param samples mẫu `[a, b)` của bài.
     * @param raw lượt nói sherpa-onnx trả về (giây trong phần, còn chồng lấn).
     * @param embed embedding của một dãy mẫu, `null` khi sherpa-onnx chưa đủ mẫu (`isReady` sai).
     * @return lượt nói đã bỏ chồng lấn (giây trên toàn bài) và embedding trung bình (≤ 30 s tiếng nói, chuẩn hoá L2) của
     * từng nhãn.
     */
    fun build(
        samples: FloatArray,
        a: Long,
        b: Long,
        sampleRate: Int,
        raw: List<LabeledTurn>,
        embed: (FloatArray) -> FloatArray?,
    ): Piece {
        val excl = Exclusive.toExclusive(raw.sortedBy { it.start })
        val emb = LinkedHashMap<Int, DoubleArray?>()
        for (l in excl.map { it.label }.distinct().sorted()) {
            val ranges = Exclusive.embeddingRanges(
                excl.filter { it.label == l }.map { it.start to it.end }, sampleRate, samples.size,
            )
            emb[l] = embedding(samples, ranges, embed)
        }
        val off = a.toDouble() / sampleRate
        return Piece(off, b.toDouble() / sampleRate, excl.map { LabeledTurn(it.start + off, it.end + off, it.label) }, emb)
    }

    private fun embedding(samples: FloatArray, ranges: List<IntRange>, embed: (FloatArray) -> FloatArray?): DoubleArray? {
        if (ranges.isEmpty()) return null
        val all = FloatArray(ranges.sumOf { it.last - it.first + 1 })
        var p = 0
        for (r in ranges) {
            samples.copyInto(all, p, r.first, r.last + 1)
            p += r.last - r.first + 1
        }
        val v = embed(all) ?: return null
        return PieceLinker.normalize(DoubleArray(v.size) { v[it].toDouble() })
    }
}

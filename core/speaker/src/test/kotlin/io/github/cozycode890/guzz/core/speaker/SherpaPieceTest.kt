package io.github.cozycode890.guzz.core.speaker

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SherpaPieceTest {
    private val sr = 16_000

    @Test
    fun `lượt nói cộng mốc đầu phần, embedding chuẩn hoá L2`() {
        val samples = FloatArray(10 * sr) { 0.1f }
        val raw = listOf(LabeledTurn(5.0, 8.0, 1), LabeledTurn(1.0, 4.0, 0))
        val seen = ArrayList<Int>()
        val p = SherpaPiece.build(samples, 60L * sr, 70L * sr, sr, raw) {
            seen += it.size
            floatArrayOf(3f, 4f)
        }
        assertEquals(60.0, p.a)
        assertEquals(70.0, p.b)
        assertEquals(listOf(0, 1), p.turns.map { it.label })
        assertEquals(61.0, p.turns[0].start, 1e-9)
        assertEquals(68.0, p.turns[1].end, 1e-9)
        assertEquals(listOf(0.6, 0.8), p.emb[0]!!.toList().map { Math.round(it * 1e9) / 1e9 })
        assertEquals(2, seen.size)
        assertTrue(seen.all { it > 0 })
    }

    @Test
    fun `sherpa-onnx chưa đủ mẫu thì embedding null`() {
        val p = SherpaPiece.build(FloatArray(5 * sr), 0, 5L * sr, sr, listOf(LabeledTurn(0.5, 2.0, 0))) { null }
        assertNull(p.emb[0])
    }
}

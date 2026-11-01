package io.github.cozycode890.guzz.spike.diarize

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnsTest {
    @Test
    fun hungarianMaximisesOverlap() {
        // Ma trận thời gian trùng nhau 3 × 2: cặp tối ưu là (0, 1) và (2, 0), tổng 9 + 8.
        val m = arrayOf(doubleArrayOf(1.0, 9.0), doubleArrayOf(2.0, 3.0), doubleArrayOf(8.0, 7.0))
        val neg = Array(3) { r -> DoubleArray(2) { c -> -m[r][c] } }
        assertEquals(listOf(0 to 1, 2 to 0), linearSumAssignment(neg))
    }

    @Test
    fun hungarianSquare() {
        val cost = arrayOf(doubleArrayOf(4.0, 1.0, 3.0), doubleArrayOf(2.0, 0.0, 5.0), doubleArrayOf(3.0, 2.0, 2.0))
        // Như scipy.optimize.linear_sum_assignment: (0, 1), (1, 0), (2, 2), tổng 5.
        assertEquals(listOf(0 to 1, 1 to 0, 2 to 2), linearSumAssignment(cost))
    }

    @Test
    fun exclusiveGivesOverlapToLongerTurn() {
        val segs = listOf(Seg(0.0, 10.0, 0), Seg(8.0, 9.0, 1), Seg(9.5, 12.0, 1))
        assertEquals(listOf(Seg(0.0, 10.0, 0), Seg(10.0, 12.0, 1)), toExclusive(segs))
        val (ov, sp) = overlapSeconds(segs)
        assertEquals(1.5, ov, 1e-9)
        assertEquals(12.0, sp, 1e-9)
    }

    @Test
    fun pieceBoundsOverlap() {
        assertEquals(listOf(0L to 100L, 80L to 180L, 160L to 200L), pieceBounds(200, 100, 20))
        assertEquals(listOf(0L to 50L), pieceBounds(50, 100, 20))
    }
}

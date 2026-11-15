package io.github.cozycode890.guzz.core.speaker

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Luật min–max (D72) với bản giả của tách giọng từng phần: phần nào được chạy lại, với số cụm bao nhiêu. */
class SpeakerCountRuleTest {
    private val voices = List(8) { i -> DoubleArray(8) { j -> if (j == i) 1.0 else 0.0 } }

    /** Phần k dài 600 s, chồng lấn 60 s; [labelsFor] cho số nhãn theo số cụm yêu cầu. */
    private class Fake(val voices: List<DoubleArray>, val labelsFor: (Int, Int) -> Int) : PieceRunner {
        val calls = ArrayList<Pair<Int, Int>>()
        override suspend fun run(index: Int, numClusters: Int): Piece {
            calls += index to numClusters
            val a = index * 540.0
            val n = labelsFor(index, numClusters)
            val turns = (0 until 60).map { i -> LabeledTurn(a + i * 10.0, a + i * 10.0 + 9.0, i % n) }
            return Piece(a, a + 600.0, turns, (0 until n).associateWith { voices[it] })
        }
    }

    @Test
    fun pieceAboveMaxIsReclusteredWithMax() = runBlocking {
        val fake = Fake(voices) { k, n -> if (n > 0) n else if (k == 1) 7 else 2 }
        val out = SpeakerCountRule.run(3, SpeakerBounds(1, 6), 0.5, fake)
        assertEquals(listOf(0 to 0, 1 to 0, 2 to 0, 1 to 6), fake.calls)
        assertTrue(PieceLinker.speakerCount(out.turns) <= 6)
    }

    @Test
    fun belowMinReclustersAllPieces() = runBlocking {
        val fake = Fake(voices) { _, n -> if (n > 0) n else 1 }
        val out = SpeakerCountRule.run(3, SpeakerBounds.of(3, 1, 6), 0.5, fake)
        assertEquals(listOf(0 to 0, 1 to 0, 2 to 0, 0 to 3, 1 to 3, 2 to 3), fake.calls)
        assertEquals(3, PieceLinker.speakerCount(out.turns))
        assertTrue(out.notes.any { "tối thiểu 3" in it })
    }

    @Test
    fun aboveMaxAfterLinkingMergesSmallest() = runBlocking {
        // Mỗi phần 2 nhãn nhưng giọng khác nhau giữa các phần, nối bằng ngưỡng 0 → nhiều người; max 2 thì gộp.
        val fake = object : PieceRunner {
            override suspend fun run(index: Int, numClusters: Int): Piece {
                val a = index * 1000.0
                val turns = listOf(LabeledTurn(a, a + 100, 0), LabeledTurn(a + 100, a + 101 + index, 1))
                return Piece(a, a + 500, turns, mapOf(0 to voices[2 * index], 1 to voices[2 * index + 1]))
            }
        }
        val out = SpeakerCountRule.run(3, SpeakerBounds(0, 2), 0.0, fake)
        assertEquals(6, PieceLinker.speakerCount(out.link.turns))
        assertEquals(2, PieceLinker.speakerCount(out.turns))
    }

    @Test
    fun noSpeechDoesNotRecluster() = runBlocking {
        val fake = object : PieceRunner {
            var calls = 0
            override suspend fun run(index: Int, numClusters: Int): Piece {
                calls++
                return Piece(0.0, 10.0, emptyList(), emptyMap())
            }
        }
        val out = SpeakerCountRule.run(1, SpeakerBounds(2, 6), 0.5, fake)
        assertEquals(1, fake.calls)
        assertTrue(out.turns.isEmpty())
    }
}

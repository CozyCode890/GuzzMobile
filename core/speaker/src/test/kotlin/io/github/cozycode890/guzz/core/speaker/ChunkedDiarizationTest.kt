package io.github.cozycode890.guzz.core.speaker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tách giọng chia phần với bản giả của sherpa-onnx: cache từng phần, Dừng giữa chừng, chạy lại. */
class ChunkedDiarizationTest {
    @TempDir
    lateinit var dir: File

    /** PCM giả dài [minutes] phút (chỉ cần cỡ tệp). */
    private fun pcm(minutes: Double): File = File(dir, "sach.pcm").also {
        RandomAccessFile(it, "rw").use { f -> f.setLength((minutes * 60 * 16_000).toLong() * 2) }
    }

    /** Mỗi phần: người 0 nói liên tục, người 1 nói 10 s ở đầu phần. Embedding cố định theo nhãn. */
    private class FakeEngine(val blockAt: Int = -1) : PieceEngine {
        val calls = ArrayList<Pair<Long, Int>>()
        val reached = CompletableDeferred<Unit>()
        override suspend fun run(pcm: File, a: Long, b: Long, numClusters: Int, onProgress: (Int, Int) -> Unit): Piece {
            if (calls.size == blockAt) {
                reached.complete(Unit)
                awaitCancellation()
            }
            calls += a to numClusters
            onProgress(1, 2)
            onProgress(2, 2)
            val s = a / 16_000.0
            val e = b / 16_000.0
            val turns = listOf(LabeledTurn(s, s + 10, 1), LabeledTurn(s + 10, e, 0))
            return Piece(s, e, turns, mapOf(0 to doubleArrayOf(1.0, 0.0), 1 to doubleArrayOf(0.0, 1.0)))
        }
    }

    @Test
    fun writesResultAndReusesPieces() = runBlocking {
        val req = DiarizeRequest(pcm(50.0), File(dir, "nguoi_noi.abc.json"), SpeakerBounds(1, 6))
        val logs = ArrayList<String>()
        val engine = FakeEngine()
        val progress = ArrayList<Double>()
        val d = ChunkedDiarization(engine, log = { logs += it })
        val r = d.diarize(req) { progress += it }
        // 50 phút, P = 20, O = 1: [0, 20), [19, 39), [38, 50).
        assertEquals(3, engine.calls.size)
        assertEquals(2, r.numSpeakers)
        assertTrue(req.outFile.isFile)
        assertEquals(r, DiarizationJson.decode(req.outFile.readText()))
        assertEquals(progress.sorted(), progress, "tiến độ tăng dần")
        assertEquals(1.0, progress.last())
        assertTrue(File(dir, "nguoi_noi.abc.phan_01.json").isFile)

        // Chạy lại: không gọi sherpa-onnx lần nào.
        val again = FakeEngine()
        ChunkedDiarization(again, log = { logs += it }).diarize(req)
        assertEquals(0, again.calls.size)
        assertEquals(3, logs.count { it.startsWith("Dùng lại phần") })
        d.cleanPieces(req)
        assertTrue(dir.listFiles()!!.none { "phan_" in it.name })
    }

    @Test
    fun stopMidwayKeepsFinishedPieces() = runBlocking {
        val req = DiarizeRequest(pcm(50.0), File(dir, "nguoi_noi.x.json"), SpeakerBounds(1, 6))
        val engine = FakeEngine(blockAt = 1)
        val job = async { ChunkedDiarization(engine).diarize(req) }
        engine.reached.await()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(File(dir, "nguoi_noi.x.phan_01.json").isFile)
        assertTrue(!File(dir, "nguoi_noi.x.phan_02.json").exists())
        assertTrue(!req.outFile.exists())

        val again = FakeEngine()
        ChunkedDiarization(again).diarize(req)
        assertEquals(listOf(19L * 60 * 16_000 to 0, 38L * 60 * 16_000 to 0), again.calls)
    }

    @Test
    fun knownCountReclustersAndCachesSeparately() = runBlocking {
        val req = DiarizeRequest(pcm(10.0), File(dir, "nguoi_noi.k.json"), SpeakerBounds.of(3, 1, 6))
        val engine = FakeEngine()
        val r = ChunkedDiarization(engine).diarize(req)
        assertEquals(listOf(0L to 0, 0L to 3), engine.calls)
        assertTrue(File(dir, "nguoi_noi.k.phan_01.k3.json").isFile)
        assertEquals(2, r.numSpeakers, "bản giả luôn trả 2 nhãn")
    }
}

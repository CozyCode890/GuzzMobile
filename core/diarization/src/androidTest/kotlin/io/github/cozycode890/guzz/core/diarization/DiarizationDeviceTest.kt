package io.github.cozycode890.guzz.core.diarization

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cozycode890.guzz.core.speaker.ChunkedDiarization
import io.github.cozycode890.guzz.core.speaker.DiarizationJson
import io.github.cozycode890.guzz.core.speaker.DiarizationSettings
import io.github.cozycode890.guzz.core.speaker.DiarizeRequest
import io.github.cozycode890.guzz.core.speaker.LabeledTurn
import io.github.cozycode890.guzz.core.speaker.SpeakerBounds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Test trên máy của `:core:diarization` (Stage 5 D1–D3) với tệp thử bốn người nói của sherpa-onnx (tải bằng plugin
 * guzz.deps, không commit):
 * - D1: sherpa-onnx trên máy ra trùng `run_sherpa.py` trên PC (`testdata/golden/diarization/`), mốc ±2 ms, cùng nhãn;
 * - D2: process `:diarize` bị giết giữa phần 2 → process chính sống, báo lỗi rõ, phần 1 còn tệp; chạy lại chỉ làm 2–4;
 * - D3: huỷ coroutine → `:diarize` mất trong ≤ 1 s.
 *
 *     .\gradlew :core:diarization:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DiarizationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var dir: File
    private lateinit var pcm: File

    /** P = 20 s, O = 2 s trên tệp 57 s: 4 phần, đủ để thử Dừng / giết giữa phần 2. */
    private val small = DiarizationSettings(pieceMinutes = 20.0 / 60, overlapSeconds = 2.0)

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "diarize-test").apply { deleteRecursively(); mkdirs() }
        pcm = File(dir, "sach.pcm")
        val wav = instrumentation.context.assets.open("testwav/0-four-speakers-zh.wav").use { it.readBytes() }
        pcm.writeBytes(pcmData(wav))
    }

    /** Phần `data` của WAV 16-bit mono 16 kHz. */
    private fun pcmData(wav: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (id == "data") return wav.copyOfRange(pos + 8, pos + 8 + size)
            pos += 8 + size + (size and 1)
        }
        error("WAV không có data")
    }

    private fun expected(name: String): List<LabeledTurn> {
        val text = instrumentation.context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
        return DiarizationJson.decode(text).turns.map { LabeledTurn(it.start, it.end, it.speaker.removePrefix("SPEAKER_").toInt()) }
    }

    /** Đánh số nhãn lại theo thứ tự xuất hiện, như `run_sherpa.py`. */
    private fun renumber(turns: List<LabeledTurn>): List<LabeledTurn> {
        val order = LinkedHashMap<Int, Int>()
        return turns.map { it.copy(label = order.getOrPut(it.label) { order.size }) }
    }

    private fun assertSame(exp: List<LabeledTurn>, got: List<LabeledTurn>) {
        assertEquals("số lượt", exp.size, got.size)
        for (i in exp.indices) {
            assertEquals("nhãn lượt $i", exp[i].label, got[i].label)
            assertTrue("mốc lượt $i: ${exp[i]} ≠ ${got[i]}", abs(exp[i].start - got[i].start) <= 0.002 && abs(exp[i].end - got[i].end) <= 0.002)
        }
    }

    @Test
    fun d1SameAsPc() {
        SherpaPieceDiarizer(context.assets).use { d ->
            val n = pcm.length() / 2
            for ((clusters, file) in listOf(0 to "four_speakers_thr.json", 4 to "four_speakers_k4.json")) {
                val piece = d.run(pcm, 0, n, clusters) { _, _ -> }
                assertSame(expected(file), renumber(piece.turns))
                assertTrue("có embedding", piece.emb.values.all { it != null && it.size > 100 })
            }
        }
    }

    private fun diarizePid(): Int {
        val am = context.getSystemService(ActivityManager::class.java)
        return am.runningAppProcesses?.firstOrNull { it.processName == context.packageName + ":diarize" }?.pid ?: 0
    }

    private fun waitGone(timeoutMs: Long): Long {
        val t0 = SystemClock.elapsedRealtime()
        while (diarizePid() != 0) {
            if (SystemClock.elapsedRealtime() - t0 > timeoutMs) return -1
            Thread.sleep(10)
        }
        return SystemClock.elapsedRealtime() - t0
    }

    @Test
    fun d2KilledProcessReportsAndResumes() = runBlocking {
        val req = DiarizeRequest(pcm, File(dir, "nguoi_noi.d2.json"), SpeakerBounds(1, 6))
        val inPiece2 = CompletableDeferred<Unit>()
        val logs = ArrayList<String>()
        RemotePieceEngine(context).use { engine ->
            val chunked = ChunkedDiarization(engine, small, { logs += it })
            // Giết từ một coroutine khác; ngoại lệ bắt bằng try quanh withContext (async sẽ huỷ cả runBlocking).
            val killer = launch(Dispatchers.Default) {
                inPiece2.await()
                val pid = diarizePid()
                assertTrue(pid > 0)
                Process.killProcess(pid)
            }
            try {
                withContext(Dispatchers.Default) { chunked.diarize(req) { if (it > 0.4) inPiece2.complete(Unit) } }
                fail("phải báo lỗi khi :diarize chết")
            } catch (e: DiarizeProcessDiedException) {
                // đúng
            }
            killer.join()
        }
        assertTrue(File(dir, "nguoi_noi.d2.phan_01.json").isFile)
        assertFalse(req.outFile.exists())
        waitGone(5_000)

        val runs = ArrayList<Long>()
        val counting = object : io.github.cozycode890.guzz.core.speaker.PieceEngine {
            val inner = RemotePieceEngine(context)
            override suspend fun run(pcm: File, a: Long, b: Long, numClusters: Int, onProgress: (Int, Int) -> Unit) =
                inner.run(pcm, a, b, numClusters, onProgress).also { runs += a }
        }
        val r = ChunkedDiarization(counting, small, { logs += it }).diarize(req)
        counting.inner.close()
        assertEquals("chỉ chạy lại phần 2–4: chạy $runs, log $logs", 3, runs.size)
        assertTrue(logs.any { it.startsWith("Dùng lại phần 1/4") })
        assertTrue(r.turns.isNotEmpty())
    }

    @Test
    fun d3CancelKillsDiarizeQuickly() = runBlocking {
        val req = DiarizeRequest(pcm, File(dir, "nguoi_noi.d3.json"), SpeakerBounds(1, 6))
        val started = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            AndroidSpeakerDiarizer(context, small).diarize(req) { if (it > 0.05) started.complete(Unit) }
        }
        started.await()
        assertTrue(diarizePid() > 0)
        val t0 = SystemClock.elapsedRealtime()
        job.cancel()
        withContext(Dispatchers.Default) { runCatching { job.await() } }
        val gone = waitGone(1_000)
        val total = SystemClock.elapsedRealtime() - t0
        assertTrue("process :diarize phải mất trong ≤ 1 s (đo $gone ms)", gone >= 0)
        assertTrue("coroutine phải kết thúc ngay ($total ms)", total <= 1_500)
        assertFalse(req.outFile.exists())
    }
}

package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.audio.ChunkPreparer
import io.github.cozycode890.guzz.core.common.audio.TimeMap
import io.github.cozycode890.guzz.core.common.log.LogLevel
import io.github.cozycode890.guzz.core.common.log.MemorySink
import io.github.cozycode890.guzz.core.common.log.SinkLogger
import io.github.cozycode890.guzz.core.gemini.TranscribeOptions
import io.github.cozycode890.guzz.core.speaker.DiarizationJson
import io.github.cozycode890.guzz.core.speaker.DiarizeRequest
import io.github.cozycode890.guzz.core.speaker.SpeakerDiarizer
import io.github.cozycode890.guzz.core.speaker.Speakers
import io.github.cozycode890.guzz.core.speaker.TurnJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pipeline có nhận diện người nói (Stage 5 E1–E4) với tách giọng giả. Bốn ca đầu port từ tests/test_transcribe.py, giữ
 * tên ca của desktop.
 */
class SpeakerPipelineTest {
    @TempDir
    lateinit var tmp: File

    private val log = MemorySink()
    private val logger = SinkLogger().apply { level = LogLevel.DEBUG; addSink(log) }
    private var cfg = testConfig()
    private val source = SyntheticSource()
    private val out by lazy { DirectoryOutputFolder(File(tmp, "out")) }

    /** Như `_fake_pyannote` của test desktop: ghi lượt nói cho sẵn, kiểm PCM không rỗng. */
    private inner class FakeDiarizer(val turns: List<Triple<Double, Double, String>>) : SpeakerDiarizer {
        var calls = 0
        var pcmSeen: File? = null
        var block: CompletableDeferred<Unit>? = null
        var fail: Exception? = null

        override suspend fun diarize(request: DiarizeRequest, onProgress: (Double) -> Unit): DiarizationJson {
            calls++
            pcmSeen = request.pcm
            assertTrue(request.pcm.length() > 0, "PCM cho tách giọng phải còn")
            onProgress(0.5)
            block?.let {
                it.complete(Unit)
                awaitCancellation()
            }
            fail?.let { throw it }
            val d = DiarizationJson("gia", durationSec = 0.0, processingSec = 0.0, numSpeakers = 0,
                turns = turns.map { (a, b, s) -> TurnJson(a, b, s) })
            DiarizationJson.write(request.outFile, d)
            return d
        }
    }

    private fun pipeline(d: SpeakerDiarizer?) = FilePipeline(File(tmp, "tmp"), logger, sleep = {}, diarizer = d)

    private fun run(d: SpeakerDiarizer?, client: FakeClient, progress: (FileProgress) -> Unit = {}) =
        runBlocking { pipeline(d).run(source, out, cfg, client, onProgress = progress) }

    private fun read(path: String) = File(path).readText(Charsets.UTF_8)
    private val tempDir get() = pipeline(null).tempDirFor(source.info())

    private fun speakers(method: String, model: String? = null, f: SpeakerConfig.() -> SpeakerConfig = { this }) {
        cfg = cfg.copy(
            gemini = cfg.gemini.copy(diarizationEnabled = true, diarizationMethod = method, model = model ?: cfg.gemini.model),
            speakers = cfg.speakers.f(),
        )
    }

    private fun w(t: String, s: String?, a: Double, b: Double) = Triple(t, s, a to b)

    @Test
    fun test_pyannote_sends_map_and_maps_times_to_original() {
        speakers("pyannote", "gemini-3.8-flash") { copy(options = options.copy(densityMinSec = 5.0)) }
        cfg = cfg.copy(deleteTempWhenDone = false, addHeader = true)
        // Lượt nói trên âm thanh ĐÃ LÀM SẠCH: giảng viên cả buổi, người hỏi ở giây 20–30.
        val d = FakeDiarizer(listOf(Triple(0.0, 20.0, "SPEAKER_01"), Triple(20.0, 30.0, "SPEAKER_00"), Triple(30.0, 48.0, "SPEAKER_01")))
        val client = FakeClient { _, _ -> text("[00:01] Giảng viên: Mở đầu.\n\n[00:03] Người hỏi 1: Câu hỏi?") }
        val progress = ArrayList<FileProgress>()
        val result = run(d, client) { progress += it }

        val first = client.calls[0]
        assertEquals("gemini-3.8-flash", first.model)
        assertContains(first.prompt!!, "Speakers in this clip: Giảng viên")
        assertContains(first.prompt!!, "\"[03:15] Giảng viên: ...\"")
        assertContains(first.map!!, "00:00.0 - ")
        assertTrue(client.calls.any { "Người hỏi 1" in it.map.orEmpty() })

        val list = ChunkPreparer.readChunkList(tempDir)!!
        val tmap = TimeMap(list.timeMap.map { it[0] to it[1] })
        val content = read(result.outFile!!)
        assertContains(content.lines()[2], "Người nói: Giảng viên")
        val firstTs = Speakers.hhMmSs(tmap.toOriginal(list.chunks[0].startSeconds + 1))
        assertContains(content, "[$firstTs] Giảng viên: Mở đầu.")
        assertEquals("00:00:02", firstTs) // khoảng lặng đầu tệp bị cắt, mốc vẫn theo tệp gốc
        assertEquals(Stage.CLEAN, progress.first().stage)
        assertTrue(progress.any { it.stage == Stage.DIARIZE })
        assertEquals(1.0, progress.last().fraction)
        assertEquals(progress.map { it.fraction }.sorted(), progress.map { it.fraction })
    }

    @Test
    fun test_pyannote_inlines_map_when_text_delivery() {
        speakers("pyannote", "gemini-3.8-flash") { copy(mapDelivery = "text", timestamps = false) }
        val client = FakeClient { _, _ -> text("Giảng viên: A") }
        val result = run(FakeDiarizer(listOf(Triple(0.0, 48.0, "S0"))), client)
        assertContains(client.calls[0].options.attachedText!!, "00:00.0")
        assertFalse("square brackets" in client.calls[0].prompt!!)
        assertTrue(read(result.outFile!!).startsWith("Giảng viên: A\n\nGiảng viên: A"))
    }

    @Test
    fun test_gemini_names_speakers_per_chunk() {
        speakers("gemini")
        val client = FakeClient { _, _ ->
            // Đoạn nào cũng: spk_2 nói lâu (giảng viên), spk_1 hỏi một câu ngắn trước.
            words(listOf(w("Hỏi", "spk_1", 0.0, 0.5), w("gì?", "spk_1", 0.5, 1.0), w("Trả", "spk_2", 1.5, 2.0), w("lời", "spk_2", 2.0, 6.0)))
        }
        val result = run(null, client)
        for (c in client.calls) {
            assertEquals("gemini-3.5-transcribe", c.model)
            assertEquals(TranscribeOptions(diarize = true, wordTimestamps = true), c.options.copy(model = null, maxAttempts = 0))
        }
        val blocks = read(result.outFile!!).split("\n\n")
        assertTrue(Regex("""^\[00:00:0\d] Người hỏi 1: Hỏi gì\?$""").matches(blocks[0]), blocks[0])
        assertTrue(Regex("""^\[00:00:0\d] Giảng viên: Trả lời$""").matches(blocks[1]), blocks[1])
        assertEquals(listOf("Giảng viên", "Người hỏi 1"), result.minutesPerSpeaker.keys.toList())
    }

    @Test
    fun test_hybrid_assigns_names_from_pyannote() {
        speakers("hybrid") { copy(options = options.copy(smooth = false), paragraphTemplate = "{speaker} ({time}): {text}") }
        val client = FakeClient { _, _ -> words(listOf(w("một", null, 0.0, 1.0), w("hai", null, 5.0, 6.0))) }
        val result = run(FakeDiarizer(listOf(Triple(0.0, 4.0, "A"), Triple(4.0, 100.0, "B"))), client)
        assertEquals(true, client.calls[0].options.wordTimestamps)
        val content = read(result.outFile!!)
        // Đoạn 1: "một" (0–1) thuộc A, "hai" (5–6) thuộc B người nói lâu nhất → Giảng viên.
        assertTrue(Regex("""^Người hỏi 1 \(00:00:0\d\): một\n\nGiảng viên \(00:00:\d\d\): hai""").containsMatchIn(content), content)
        assertEquals(1, Regex("Người hỏi 1").findAll(content).count())
    }

    // ---------------------------------------------------------------- E1–E2 (mobile)

    @Test
    fun `xong thì không còn PCM, chạy lại dùng lại kết quả tách giọng và không giữ PCM`() {
        speakers("pyannote", "gemini-3.8-flash")
        cfg = cfg.copy(deleteTempWhenDone = false)
        val d = FakeDiarizer(listOf(Triple(0.0, 48.0, "S0")))
        run(d, FakeClient { _, _ -> text("Giảng viên: A") })
        assertEquals(ChunkPreparer.CLEAN_PCM_FILE, d.pcmSeen!!.name)
        assertTrue(tempDir.listFiles()!!.none { it.name.endsWith(".pcm") })
        assertTrue(tempDir.listFiles()!!.any { it.name.startsWith("nguoi_noi.") && it.name.endsWith(".json") })

        val d2 = FakeDiarizer(emptyList())
        log.clear()
        run(d2, FakeClient { _, _ -> text("x") })
        assertEquals(0, d2.calls)
        assertEquals(1, source.prepareCalls, "không giải mã lại")
        assertTrue(log.lines.any { "Dùng lại kết quả nhận diện người nói đã có." in it })
    }

    @Test
    fun `chạy trên âm thanh gốc thì giữ goc pcm và đổi mốc về âm thanh sạch`() {
        speakers("hybrid") { copy(runOn = "original", options = options.copy(smooth = false)) }
        cfg = cfg.copy(deleteTempWhenDone = false)
        // Trên tệp gốc: A nói 0–10 s (khoảng lặng đầu 0–~1,6 s bị cắt), B nói phần còn lại.
        val d = FakeDiarizer(listOf(Triple(0.0, 10.0, "A"), Triple(10.0, 60.0, "B")))
        val result = run(d, FakeClient { _, _ -> words(listOf(w("một", null, 0.0, 1.0))) })
        assertEquals(ChunkCache.RAW_PCM_FILE, d.pcmSeen!!.name)
        assertTrue(result.minutesPerSpeaker.isNotEmpty())
    }

    @Test
    fun `dừng giữa lúc tách giọng thì PCM còn, chạy lại không xử lý âm thanh lại`() {
        speakers("pyannote", "gemini-3.8-flash")
        val d = FakeDiarizer(listOf(Triple(0.0, 48.0, "S0"))).apply { block = CompletableDeferred() }
        assertFailsWith<CancellationException> {
            runBlocking {
                val job = async { pipeline(d).run(source, out, cfg, FakeClient { _, _ -> text("x") }) }
                d.block!!.await()
                job.cancel()
                job.await()
            }
        }
        assertTrue(File(tempDir, ChunkPreparer.CLEAN_PCM_FILE).length() > 0, "PCM còn sau khi Dừng")
        d.block = null
        run(d, FakeClient { _, _ -> text("Giảng viên: A") })
        assertEquals(1, source.prepareCalls, "dùng lại các đoạn và PCM đã có")
        assertEquals(2, d.calls)
    }

    @Test
    fun `có đoạn trong cache mà thiếu PCM thì giải mã lại`() {
        speakers("pyannote", "gemini-3.8-flash")
        cfg = cfg.copy(deleteTempWhenDone = false)
        val d = FakeDiarizer(listOf(Triple(0.0, 48.0, "S0"))).apply { fail = IllegalStateException("hỏng") }
        assertFailsWith<IllegalStateException> { run(d, FakeClient { _, _ -> text("x") }) }
        File(tempDir, ChunkPreparer.CLEAN_PCM_FILE).delete()
        d.fail = null
        run(d, FakeClient { _, _ -> text("Giảng viên: A") })
        assertEquals(2, source.prepareCalls)
    }

    @Test
    fun `lưu bản đồ người nói cạnh bản chép lời`() {
        speakers("pyannote", "gemini-3.8-flash") { copy(saveSpeakerMap = true) }
        val result = run(FakeDiarizer(listOf(Triple(0.0, 20.0, "S0"), Triple(20.0, 48.0, "S1"))), FakeClient { _, _ -> text("Giảng viên: A") })
        val map = File(File(tmp, "out"), result.outName!!.removeSuffix(".txt") + ".nguoi_noi.txt")
        assertTrue(map.isFile)
        assertTrue(Regex("""(?m)^\[00:00:0\d - 00:00:\d\d] (Giảng viên|Người hỏi 1)$""").containsMatchIn(map.readText()))
    }
}

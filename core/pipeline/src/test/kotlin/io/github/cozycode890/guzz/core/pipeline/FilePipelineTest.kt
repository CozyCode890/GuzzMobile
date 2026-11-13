package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.common.log.LogLevel
import io.github.cozycode890.guzz.core.common.log.MemorySink
import io.github.cozycode890.guzz.core.common.log.SinkLogger
import io.github.cozycode890.guzz.core.gemini.ClientHarness
import io.github.cozycode890.guzz.core.gemini.ConstraintException
import io.github.cozycode890.guzz.core.gemini.ErrorKind
import io.github.cozycode890.guzz.core.gemini.FakeGemini
import io.github.cozycode890.guzz.core.gemini.GoogleAIException
import io.github.cozycode890.guzz.core.gemini.LockReason
import io.github.cozycode890.guzz.core.gemini.Quota
import io.github.cozycode890.guzz.core.gemini.QuotaTracker
import io.github.cozycode890.guzz.core.gemini.Reply
import io.github.cozycode890.guzz.core.gemini.UsageEventKind
import io.github.cozycode890.guzz.core.gemini.nextDailyReset
import io.github.cozycode890.guzz.core.gemini.resultText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port của tests/test_transcribe.py (các ca không có người nói) qua [FilePipeline] trên âm thanh tổng hợp. Tên ca giữ tên
 * desktop để đối chiếu. Khác desktop: bản chép lời ghi vào thư mục ra (D59) thay vì cạnh tệp nguồn.
 */
class FilePipelineTest {
    @TempDir
    lateinit var tmp: File

    private val log = MemorySink()
    private val logger = SinkLogger().apply { level = LogLevel.DEBUG; addSink(log) }
    private var cfg = testConfig()
    private val source = SyntheticSource()
    private val out by lazy { DirectoryOutputFolder(File(tmp, "out")) }
    private val sleeps = mutableListOf<Double>()
    private var onSleep: (Double) -> Unit = {}
    private val pipeline by lazy {
        FilePipeline(File(tmp, "tmp"), logger, sleep = { sleeps += it; onSleep(it) })
    }

    private fun run(client: FakeClient?, progress: (FileProgress) -> Unit = {}) =
        runBlocking { pipeline.run(source, out, cfg, client, onProgress = progress) }

    private fun read(path: String) = File(path).readText(Charsets.UTF_8)
    private val tempDir get() = pipeline.tempDirFor(source.info())

    // ---------------------------------------------------------------- TestTranscribe

    @Test
    fun test_no_speakers_writes_next_to_source_crlf_bom() {
        cfg = cfg.copy(newline = "crlf", encoding = "utf-8-sig", markChunks = true)
        val client = FakeClient { i, _ -> text("Đoạn $i.") }
        val progress = mutableListOf<Double>()
        val result = run(client) { progress += it.fraction }
        assertEquals(File(tmp, "out/bai giang.txt").path, result.outFile)
        assertTrue(result.chunkCount >= 2)
        val raw = File(result.outFile!!).readBytes()
        assertTrue(raw.take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        assertContains(raw.toString(Charsets.UTF_8), "\r\n\r\n")
        assertContains(raw.toString(Charsets.UTF_8), "----- Đoạn 2/")
        assertTrue(client.calls.all { it.model == "gemini-3.5-transcribe" && it.prompt == null })
        assertFalse(tempDir.exists())
        // Tiến độ tăng dần và kết thúc ở 1,0.
        assertEquals(progress.sorted(), progress)
        assertEquals(1.0, progress.last())

        // Lần hai: đánh số; skip thì không gọi Google.
        val result2 = run(client)
        assertTrue(result2.outFile!!.endsWith("bai giang (2).txt"))
        cfg = cfg.copy(onConflict = "skip")
        val attempts = client.calls.size
        assertTrue(run(client).skipped)
        assertEquals(attempts, client.calls.size)
    }

    @Test
    fun test_output_dir_and_template() {
        cfg = cfg.copy(filenameTemplate = "{name}_{model}", extension = ".md")
        val result = run(FakeClient { _, _ -> text("x") })
        assertEquals(File(tmp, "out/bai giang_gemini-3.5-transcribe.md").path, result.outFile)
    }

    @Test
    fun test_resume_after_error_does_not_resend_done_chunks() {
        val error = GoogleAIException("quá tải", ErrorKind.TEMPORARY)
        val client = FakeClient { i, _ -> if (i == 2) error else text("lan $i") }
        assertFailsWith<GoogleAIException> { run(client) }
        val client2 = FakeClient { i, _ -> text("lan sau $i") }
        val result = run(client2)
        assertEquals(result.chunkCount - 1, client2.calls.size)
        assertTrue(read(result.outFile!!).startsWith("lan 1\n\nlan sau 1"))
        // C1: lần hai dùng lại các đoạn đã cắt, không xử lý âm thanh lại; có dòng log "dùng lại".
        assertEquals(1, source.prepareCalls)
        assertTrue(log.lines.any { "Đoạn 1/${result.chunkCount}: dùng lại kết quả đã có" in it })
    }

    // ---------------------------------------------------------------- C1: cache đoạn âm thanh

    @Test
    fun `cùng tham số thì không xử lý âm thanh lại, đổi chunk_minutes hoặc mất một đoạn thì làm lại`() {
        cfg = cfg.copy(deleteTempWhenDone = false)
        val client = FakeClient { _, _ -> text("x") }
        run(client)
        assertEquals(1, source.prepareCalls)
        // D66: không còn PCM trong thư mục tạm.
        assertFalse(File(tempDir, ChunkCache.RAW_PCM_FILE).exists())
        assertFalse(File(tempDir, "sach.pcm").exists())
        run(client)
        assertEquals(1, source.prepareCalls)

        cfg = cfg.copy(chunk = cfg.chunk.copy(chunkMinutes = 0.3))
        run(client)
        assertEquals(2, source.prepareCalls)

        File(tempDir, "doan_002.wav").delete()
        run(client)
        assertEquals(3, source.prepareCalls)
    }

    @Test
    fun `video không có tiếng thì skip_silent bỏ qua tệp, tắt thì báo lỗi`() {
        val video = SyntheticSource(name = "video.mp4", noAudio = true)
        val r = runBlocking { pipeline.run(video, out, cfg, FakeClient { _, _ -> text("x") }) }
        assertTrue(r.skipped)
        cfg = cfg.copy(skipSilent = false)
        assertFailsWith<NoAudioTrackException> {
            runBlocking { pipeline.run(video, out, cfg, FakeClient { _, _ -> text("x") }) }
        }
    }

    @Test
    fun `chưa có key hoặc bật người nói thì dừng hàng đợi trước khi xử lý âm thanh`() {
        val e = assertFailsWith<GoogleAIException> { run(null) }
        assertTrue(e.stopQueue)
        cfg = cfg.copy(gemini = cfg.gemini.copy(diarizationEnabled = true, diarizationMethod = "gemini"))
        val e2 = assertFailsWith<PipelineException> { run(FakeClient { _, _ -> text("x") }) }
        assertTrue(e2.stopQueue)
        assertEquals(0, source.prepareCalls)
    }

    @Test
    fun `dừng giữa lúc chép lời thì ném CancellationException, chạy lại không gửi lại đoạn đã xong`() {
        var job: kotlinx.coroutines.Job? = null
        val client = FakeClient { i, _ -> if (i == 2) job!!.cancel(); text("lan $i") }
        assertFailsWith<CancellationException> {
            runBlocking {
                job = coroutineContext[kotlinx.coroutines.Job]
                pipeline.run(source, out, cfg, client)
            }
        }
        val client2 = FakeClient { i, _ -> text("sau $i") }
        val result = run(client2)
        // Đoạn 2 trả về đúng lúc bị huỷ (như Dừng lúc client đang xoá tệp tải lên): Google đã trả lời nên vẫn phải lưu,
        // chạy lại không gửi lại đoạn 1 lẫn đoạn 2.
        assertTrue(client2.calls.none { it.file == "doan_001.wav" || it.file == "doan_002.wav" })
        assertTrue(read(result.outFile!!).startsWith("lan 1"))
    }

    @Test
    fun `kết quả của Gemini giả nằm dưới mã riêng, chạy thật không dùng lại`() {
        cfg = cfg.copy(deleteTempWhenDone = false)
        runBlocking { pipeline.run(source, out, cfg, FakeClient { i, _ -> text("giả $i") }, cacheSalt = "gemini_gia") }
        val real = FakeClient { i, _ -> text("thật $i") }
        val result = run(real)
        assertEquals(result.chunkCount, real.calls.size)
        assertTrue(read(result.outFile!!).startsWith("thật 1"))
        // Mã khi không có salt vẫn đúng công thức của desktop (golden ở PipelineGoldenTest).
        assertTrue(CacheKeys.sharedKey("k", null, cfg.gemini) != CacheKeys.sharedKey("k", null, cfg.gemini, "gemini_gia"))
    }

    // ---------------------------------------------------------------- TestAutoSwitchModel

    @Nested
    inner class TestAutoSwitchModel {
        @Test
        fun test_daily_quota_locks_model_and_switches_to_fallback() {
            val error = GoogleAIException("HTTP 429: Quota exceeded ... limit: 25", ErrorKind.QUOTA, 429).apply {
                isDaily = true
            }
            val client = FakeClient { i, m -> if (m == "gemini-3.5-transcribe" && i >= 2) error else text("$m $i") }
            val result = run(client)
            val models = client.calls.map { it.model }
            assertEquals(listOf("gemini-3.5-transcribe", "gemini-3.5-transcribe", "gemini-3.5-flash"), models.take(3))
            assertTrue(models.drop(3).all { it == "gemini-3.5-flash" }) // đã khoá: không thử lại
            val status = client.tracker.lockStatus("gemini-3.5-transcribe")
            assertEquals(true to LockReason.DAILY_QUOTA, status.locked to status.reason)
            assertEquals(nextDailyReset(System.currentTimeMillis() / 1000.0), status.until)
            assertEquals(1, result.models["gemini-3.5-transcribe"])
            // Model dự phòng đa năng nhận prompt, model *-transcribe thì không.
            assertNull(client.calls[0].prompt)
            assertTrue(!client.calls[2].prompt.isNullOrEmpty())
            assertEquals(cfg.gemini.retriesBeforeSwitch, client.calls[2].maxAttempts)
        }

        /** C2: lỗi thật 2026-09-18, model trả về bản chép lời RỖNG. */
        @Test
        fun test_empty_transcript_retries_with_another_model() {
            val client = FakeClient { i, m -> if (m == "gemini-3.5-transcribe") text("") else text("Có chữ $i.") }
            val result = run(client)
            val models = client.calls.map { it.model }
            assertEquals("gemini-3.5-transcribe", models[0])
            assertEquals("gemini-3.5-flash", models[1])
            assertEquals(2 * result.chunkCount, client.calls.size)
            assertContains(read(result.outFile!!), "Có chữ")
            assertNull(result.models["gemini-3.5-transcribe"])
        }

        /** C2: nhớ kết quả rỗng = chạy lại vẫn bỏ qua đoạn đó vĩnh viễn. */
        @Test
        fun test_empty_result_is_not_cached() {
            val client = FakeClient { _, _ -> text("") }
            cfg = cfg.copy(gemini = cfg.gemini.copy(autoSwitchModel = false))
            run(client)
            val firstCalls = client.calls.size
            assertTrue(firstCalls > 0)
            if (tempDir.isDirectory) assertEquals(emptyList(), tempDir.list()!!.filter { it.endsWith(".json") && it.startsWith("doan_") })
            // Chạy lại: phải gọi Google lại chứ không đọc cache rỗng.
            cfg = cfg.copy(onConflict = "overwrite")
            run(client)
            assertEquals(2 * firstCalls, client.calls.size)
        }

        @Test
        fun test_filter_blocking_one_chunk_switches_model_for_that_chunk_only() {
            cfg = cfg.copy(gemini = cfg.gemini.copy(model = "gemini-3.8-flash"))
            val blocked = GoogleAIException(
                "HTTP 400: Input blocked: This request was blocked by Gemini's filters.", ErrorKind.BLOCKED, 400,
            )
            val client = FakeClient { i, m -> if (m == "gemini-3.8-flash" && i == 1) blocked else text(m) }
            val result = run(client)
            assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.8-flash"), client.calls.take(3).map { it.model })
            assertFalse(client.tracker.isLocked("gemini-3.8-flash"))
            assertTrue(read(result.outFile!!).startsWith("gemini-3.5-flash\n\ngemini-3.8-flash"))
        }

        @Test
        fun test_overload_locks_briefly_and_keeps_fallback() {
            cfg = cfg.copy(gemini = cfg.gemini.copy(model = "gemini-3.8-flash", returnToMainModel = false))
            val overloaded = GoogleAIException(
                "HTTP 500: gemini-3.8-flash is currently experiencing high demand", ErrorKind.OVERLOAD, 500,
            )
            val client = FakeClient { _, m -> if (m == "gemini-3.8-flash") overloaded else text("ok") }
            run(client)
            assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash"), client.calls.take(2).map { it.model })
            assertTrue(client.calls.drop(1).all { it.model == "gemini-3.5-flash" })
            assertEquals(LockReason.OVERLOADED, client.tracker.lockStatus("gemini-3.8-flash").reason)
        }

        @Test
        fun test_overload_lock_off_still_switches_for_current_chunk() {
            cfg = cfg.copy(gemini = cfg.gemini.copy(model = "gemini-3.8-flash", overloadLockMinutes = 0.0))
            val overloaded = GoogleAIException("HTTP 500: high demand", ErrorKind.OVERLOAD, 500)
            val client = FakeClient { i, m -> if (m == "gemini-3.8-flash" && i == 1) overloaded else text("ok") }
            run(client)
            assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.8-flash"), client.calls.take(3).map { it.model })
            assertFalse(client.tracker.isLocked("gemini-3.8-flash"))
        }

        @Test
        fun test_filter_blocks_every_model_fails_file_but_not_queue() {
            cfg = cfg.copy(gemini = cfg.gemini.copy(model = "gemini-3.8-flash", fallbackModels = listOf("gemini-3.5-flash")))
            val blocked = GoogleAIException("HTTP 400: Input blocked", ErrorKind.BLOCKED, 400)
            val client = FakeClient { _, _ -> blocked }
            val e = assertFailsWith<GoogleAIException> { run(client) }
            assertEquals(ErrorKind.BLOCKED, e.kind)
            assertFalse(e.stopQueue)
            assertEquals(2, client.calls.size)
        }

        /**
         * Desktop bật `method = gemini` chỉ để chuỗi còn một model `*-transcribe`; Stage 4 chưa có người nói nên tắt tự
         * đổi model để được cùng chuỗi một model.
         */
        @Test
        fun test_all_models_locked_long_stops_queue() {
            cfg = cfg.copy(gemini = cfg.gemini.copy(autoSwitchModel = false))
            val client = FakeClient { _, _ -> text("x") }
            client.tracker.lock(
                "gemini-3.5-transcribe", System.currentTimeMillis() / 1000.0 + 5 * 3600, LockReason.DAILY_QUOTA, "429",
            )
            val e = assertFailsWith<GoogleAIException> { run(client) }
            assertTrue(e.stopQueue)
            assertContains(e.message!!, "gemini-3.5-transcribe")
            assertEquals(emptyList(), client.calls)
        }

        /** Như ca trên: tắt tự đổi model thay cho `method = gemini` của desktop. */
        @Test
        fun test_short_lock_waits_then_continues() {
            cfg = cfg.copy(gemini = cfg.gemini.copy(autoSwitchModel = false))
            val client = FakeClient { _, _ -> text("x") }
            client.tracker.lock(
                "gemini-3.5-transcribe", System.currentTimeMillis() / 1000.0 + 90, LockReason.OVERLOADED, "500",
            )
            onSleep = { client.tracker.unlock("gemini-3.5-transcribe") }
            run(client)
            assertTrue(sleeps.last() >= 90)
            assertTrue(client.calls.isNotEmpty())
        }

        @Test
        fun test_reaching_daily_quota_locks_before_sending() {
            cfg = cfg.copy(gemini = cfg.gemini.copy(modelQuotas = mapOf("gemini-3.5-transcribe" to Quota(0, 0, 1))))
            val client = FakeClient { _, m -> text(m) }
            client.tracker.recordRequest("gemini-3.5-transcribe") // client thật tự ghi; client giả thì ghi tay
            run(client)
            assertTrue(client.calls.all { it.model == "gemini-3.5-flash" })
            assertEquals(LockReason.REACHED_DAILY, client.tracker.lockStatus("gemini-3.5-transcribe").reason)
        }

        @Test
        fun test_model_method_mismatch_fails_immediately() {
            // Model mặc định là *-transcribe.
            cfg = cfg.copy(gemini = cfg.gemini.copy(diarizationEnabled = true, diarizationMethod = "pyannote"))
            assertFailsWith<ConstraintException> { run(FakeClient { _, _ -> text("x") }) }
            assertEquals(0, source.prepareCalls)
        }
    }

    // ---------------------------------------------------------------- TestReplayRealLog

    /** Chuỗi lỗi thật trong guzz.log ngày 2026-09-17, chạy qua client thật (chỉ giả HTTP bằng MockWebServer). */
    @Nested
    inner class TestReplayRealLog {
        private val outOf25Requests = 429 to (
            "You exceeded your current quota, please check your plan and billing details.\n" +
                "* Quota exceeded for metric: generativelanguage.googleapis.com/" +
                "generate_content_free_tier_requests, limit: 25, model: gemini-3.5-transcribe\n" +
                "Please retry in 27.888016836s."
            )
        private val highDemand = 500 to (
            "gemini-3.8-flash is currently experiencing high demand, spikes in demand are usually " +
                "temporary. Please try again later."
            )
        private val inputBlocked = 400 to (
            "Input blocked: This request was blocked by Gemini's filters. They can occasionally " +
                "trigger by mistake on safe coding, security, or biology-related queries."
            )
        private val tracker = QuotaTracker()

        /** Mỗi model trả lời theo kịch bản riêng, lấy lần lượt, phần tử cuối lặp lại (như `ServerByModel`). */
        private fun runPipeline(script: Map<String, List<Any>>): Pair<FileResult, List<String>> {
            cfg = cfg.copy(chunk = cfg.chunk.copy(chunkMinutes = 0.25)) // đủ đoạn để thấy cả lúc đổi lẫn lúc giữ model
            val queues = script.mapValues { ArrayDeque(it.value) }
            FakeGemini { model, _ ->
                val items = queues.getValue(model)
                when (val r = if (items.size > 1) items.removeFirst() else items.first()) {
                    is Pair<*, *> -> Reply.error(r.first as Int, r.second as String)
                    else -> Reply.Ok(resultText("$model: $r"))
                }
            }.use { fake ->
                val harness = ClientHarness(fake, tracker = tracker)
                val result = runBlocking { pipeline.run(source, out, cfg, harness.client) }
                return result to fake.interactionModels()
            }
        }

        @Test
        fun test_daily_quota_then_high_demand_moves_to_flash_lite() {
            cfg = cfg.copy(gemini = cfg.gemini.copy(fallbackModels = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite")))
            val (result, models) = runPipeline(
                mapOf(
                    "gemini-3.5-transcribe" to listOf("ok", outOf25Requests),
                    "gemini-3.8-flash" to listOf(highDemand),
                    "gemini-3.5-flash-lite" to listOf("ok"),
                ),
            )
            assertTrue(result.chunkCount >= 3)
            // Đoạn 1 transcribe; đoạn 2: 429 hạn mức NGÀY → không thử lại; 3.8-flash quá tải thử 2 lần → flash-lite.
            assertEquals(
                listOf(
                    "gemini-3.5-transcribe", "gemini-3.5-transcribe", "gemini-3.8-flash", "gemini-3.8-flash",
                    "gemini-3.5-flash-lite",
                ),
                models.take(5),
            )
            // Các đoạn sau không gọi lại model đã khoá.
            assertEquals(setOf("gemini-3.5-flash-lite"), models.drop(5).toSet())
            assertEquals(LockReason.DAILY_QUOTA, tracker.lockStatus("gemini-3.5-transcribe").reason)
            assertEquals(LockReason.OVERLOADED, tracker.lockStatus("gemini-3.8-flash").reason)
            assertEquals(mapOf("rpd" to 25), tracker.learnedLimits("gemini-3.5-transcribe"))
            assertEquals(
                mapOf("gemini-3.5-transcribe" to 1, "gemini-3.5-flash-lite" to result.chunkCount - 1),
                result.models,
            )
            assertTrue(tracker.snapshot().data.events.any { it.kind == UsageEventKind.SWITCH_MODEL })
        }

        @Test
        fun test_input_blocked_does_not_stop_file() {
            cfg = cfg.copy(
                gemini = cfg.gemini.copy(
                    model = "gemini-3.8-flash", fallbackModels = listOf("gemini-3.5-flash-lite"),
                    diarizationEnabled = false, diarizationMethod = "pyannote",
                ),
            )
            val (result, models) = runPipeline(
                mapOf(
                    "gemini-3.8-flash" to listOf("ok", inputBlocked, "ok"),
                    "gemini-3.5-flash-lite" to listOf("ok"),
                ),
            )
            assertEquals(
                listOf("gemini-3.8-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash"),
                models.take(4),
            )
            assertFalse(tracker.isLocked("gemini-3.8-flash"))
            assertContains(read(result.outFile!!), "gemini-3.5-flash-lite: ok")
            assertEquals(mapOf("bi_chan" to 1), tracker.snapshot().data.models.getValue("gemini-3.8-flash").errorCounts)
        }
    }
}

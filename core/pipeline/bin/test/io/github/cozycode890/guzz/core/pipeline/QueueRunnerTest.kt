package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.common.log.LogLevel
import io.github.cozycode890.guzz.core.common.log.MemorySink
import io.github.cozycode890.guzz.core.common.log.SinkLogger
import io.github.cozycode890.guzz.core.gemini.ChunkTranscriber
import io.github.cozycode890.guzz.core.gemini.ErrorKind
import io.github.cozycode890.guzz.core.gemini.GoogleAIException
import io.github.cozycode890.guzz.core.gemini.LockReason
import io.github.cozycode890.guzz.core.gemini.QuotaTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueRunnerTest {
    @TempDir
    lateinit var tmp: File

    private val log = MemorySink()
    private val logger = SinkLogger().apply { level = LogLevel.DEBUG; addSink(log) }

    private inner class Env(var cfg: PipelineConfig = testConfig(), var client: ChunkTranscriber? = null) :
        QueueEnvironment {
        val sources = HashMap<String, SyntheticSource>()
        override suspend fun loadConfig() = cfg
        override fun client(cfg: PipelineConfig) = client
        override val tracker = QuotaTracker()
        override fun source(item: QueueItem) = sources.getOrPut(item.source) { SyntheticSource(item.displayName) }
        override fun output(cfg: PipelineConfig, item: QueueItem) = DirectoryOutputFolder(File(tmp, "out"))
    }

    private val storeFile get() = File(tmp, "queue/${QueueStore.FILENAME}")
    private fun runner(env: Env) =
        QueueRunner(QueueStore(storeFile, logger), env, FilePipeline(File(tmp, "tmp"), logger, sleep = {}), logger)

    private fun item(name: String) = QueueItem(source = "/test/$name", displayName = name)
    private fun QueueRunner.status() = state.value.items.map { it.displayName to it.status }

    @Test
    fun `chạy lần lượt, thêm trùng tệp đang chờ thì bỏ qua`() {
        val env = Env(client = FakeClient { i, _ -> text("Đoạn $i.") })
        val r = runner(env)
        assertEquals(2, r.add(listOf(item("a.wav"), item("b.wav"), item("a.wav"))).size)
        assertEquals(0, r.add(listOf(item("a.wav"))).size)
        val summary = runBlocking { r.run() }
        assertEquals(QueueSummary(done = 2, failed = 0, skipped = 0, stopped = false), summary)
        assertEquals(listOf("a.wav" to ItemStatus.DONE, "b.wav" to ItemStatus.DONE), r.status())
        assertFalse(r.state.value.running)
        assertTrue(File(tmp, "out/a.txt").isFile && File(tmp, "out/b.txt").isFile)
        // Lưu xuống tệp: dựng lại store thấy y nguyên.
        assertEquals(r.state.value, QueueStore(storeFile, logger).state.value)
    }

    @Test
    fun `lỗi dừng hàng đợi thì tệp sau vẫn chờ, lỗi thường thì chạy tiếp hoặc dừng theo on_file_error`() {
        // Key sai: stopQueue.
        val env = Env(client = FakeClient { _, _ -> GoogleAIException("HTTP 400 API key not valid", ErrorKind.CONFIG, 400) })
        val r = runner(env)
        r.add(listOf(item("a.wav"), item("b.wav")))
        runBlocking { r.run() }
        assertEquals(listOf("a.wav" to ItemStatus.ERROR, "b.wav" to ItemStatus.WAITING), r.status())
        assertEquals(ErrorKind.CONFIG.code, r.state.value.items[0].errorKind)
        assertFalse(r.state.value.running)

        // Lỗi thường + continue: tệp lỗi, tệp sau vẫn chạy.
        env.client = FakeClient { _, m -> if (m.isNotEmpty()) GoogleAIException("HTTP 400 bad", ErrorKind.OTHER, 400) else text("") }
        val r2 = runner(env)
        runBlocking { r2.run() }
        assertEquals(listOf("a.wav" to ItemStatus.ERROR, "b.wav" to ItemStatus.ERROR), r2.status())

        // Lỗi thường + stop: dừng sau tệp đầu.
        env.cfg = env.cfg.copy(onFileError = "stop")
        val r3 = runner(env)
        runBlocking { r3.run() }
        assertEquals(listOf("a.wav" to ItemStatus.ERROR, "b.wav" to ItemStatus.WAITING), r3.status())

        // Ràng buộc model ↔ người nói: dừng hàng đợi.
        env.cfg = testConfig().copy(gemini = testConfig().gemini.copy(diarizationEnabled = true, diarizationMethod = "pyannote"))
        env.client = FakeClient { _, _ -> text("x") }
        val r4 = runner(env)
        runBlocking { r4.run() }
        assertEquals(listOf("a.wav" to ItemStatus.ERROR, "b.wav" to ItemStatus.WAITING), r4.status())
        assertEquals("rang_buoc", r4.state.value.items[0].errorKind)
    }

    @Test
    fun `trùng tên mà on_conflict = skip thì bỏ qua`() {
        val env = Env(client = FakeClient { _, _ -> text("x") })
        File(tmp, "out").mkdirs()
        File(tmp, "out/a.txt").writeText("cũ")
        env.cfg = env.cfg.copy(onConflict = "skip")
        val r = runner(env)
        r.add(listOf(item("a.wav")))
        assertEquals(1, runBlocking { r.run() }.skipped)
        assertEquals(listOf("a.wav" to ItemStatus.SKIPPED), r.status())
    }

    @Test
    fun `Dừng thì tệp về dung, dừng vì hệ thống thì về cho và giữ cờ đang chạy`() {
        for (resume in listOf(false, true)) {
            storeFile.delete()
            File(tmp, "tmp").deleteRecursively()
            var job: Job? = null
            lateinit var r: QueueRunner
            val env = Env(client = FakeClient { i, _ -> if (i == 2) { r.prepareStop(resume); job!!.cancel() }; text("x") })
            r = runner(env)
            r.add(listOf(item("a.wav")))
            assertFailsWith<CancellationException> {
                runBlocking {
                    job = coroutineContext[Job]
                    r.run()
                }
            }
            val expected = if (resume) ItemStatus.WAITING else ItemStatus.STOPPED
            assertEquals(listOf("a.wav" to expected), r.status())
            assertEquals(resume, r.state.value.running)
        }
    }

    @Test
    fun `bị giết giữa chừng thì dựng lại từ tệp, chạy tiếp đúng tệp, đoạn đã xong không gửi lại`() {
        lateinit var snapshot: String
        val env = Env()
        env.client = FakeClient { i, _ ->
            // Lúc đang gửi đoạn 2 của tệp đầu: chụp tệp hàng đợi, như thể tiến trình bị giết đúng lúc này.
            if (i == 2) snapshot = storeFile.readText()
            text("lan $i")
        }
        val r = runner(env)
        r.add(listOf(item("a.wav"), item("b.wav")))
        runBlocking { r.run() }
        // Thư mục tạm của tệp đầu đã bị xoá khi xong; giữ lại để giả lập: chạy lại với delete_temp_when_done = false.
        storeFile.writeText(snapshot)
        File(tmp, "out").deleteRecursively()

        val env2 = Env(cfg = testConfig().copy(deleteTempWhenDone = false))
        val calls = FakeClient { i, _ -> text("sau $i") }
        env2.client = calls
        val r2 = runner(env2)
        assertEquals(
            listOf("a.wav" to ItemStatus.RUNNING, "b.wav" to ItemStatus.WAITING), r2.status(),
        )
        assertTrue(r2.recover())
        assertEquals(listOf("a.wav" to ItemStatus.WAITING, "b.wav" to ItemStatus.WAITING), r2.status())
        runBlocking { r2.run() }
        assertEquals(listOf("a.wav" to ItemStatus.DONE, "b.wav" to ItemStatus.DONE), r2.status())
    }

    @Test
    fun `bị giết lúc đang chép lời thì đoạn đã lưu không gửi lại`() {
        lateinit var snapshot: String
        val env = Env(cfg = testConfig().copy(deleteTempWhenDone = false))
        var killed = false
        env.client = FakeClient { i, _ ->
            if (i == 2) {
                snapshot = storeFile.readText()
                killed = true
                throw IllegalStateException("giả lập tiến trình chết")
            }
            text("lan $i")
        }
        val r = runner(env)
        r.add(listOf(item("a.wav")))
        runBlocking { r.run() }
        assertTrue(killed)
        storeFile.writeText(snapshot)

        val client2 = FakeClient { i, _ -> text("sau $i") }
        val r2 = runner(Env(client = client2))
        assertTrue(r2.recover())
        runBlocking { r2.run() }
        val done = r2.state.value.items.single()
        assertEquals(ItemStatus.DONE, done.status)
        assertTrue(client2.calls.none { it.file == "doan_001.wav" })
        assertTrue(File(done.outFile!!).readText().startsWith("lan 1"))
        assertTrue(log.lines.any { "Đoạn 1/" in it && "dùng lại kết quả đã có" in it })
    }

    @Test
    fun `tệp hàng đợi hỏng thì hàng đợi rỗng và ghi log, không ném lỗi`() {
        storeFile.parentFile.mkdirs()
        storeFile.writeText("{ hỏng")
        val store = QueueStore(storeFile, logger)
        assertEquals(QueueState(), store.state.value)
        assertTrue(log.lines.any { "hỏng" in it })
        assertTrue(File(storeFile.path + ".hong").isFile)
    }

    @Test
    fun `bỏ khỏi danh sách thì xoá thư mục tạm, không bỏ được tệp đang chạy`() {
        val env = Env(client = FakeClient { _, _ -> GoogleAIException("HTTP 500 lỗi tạm", ErrorKind.TEMPORARY, 500) })
        val r = runner(env)
        val a = r.add(listOf(item("a.wav"))).single()
        runBlocking { r.run() }
        val temp = File(r.state.value.items.single().tempDir!!)
        assertTrue(temp.isDirectory)
        assertEquals(a.id, r.remove(a.id)?.id)
        assertFalse(temp.exists())
        assertTrue(r.state.value.items.isEmpty())
    }

    @Test
    fun `kiểm trước khi bắt đầu như _start của desktop`() {
        val env = Env()
        val r = runner(env)
        assertIs<StartProblem.NothingToDo>(runBlocking { r.checkStart() })
        r.add(listOf(item("a.wav")))
        assertIs<StartProblem.NoApiKey>(runBlocking { r.checkStart() })
        env.client = FakeClient { _, _ -> text("x") }
        assertNull(runBlocking { r.checkStart() })
        env.cfg = env.cfg.copy(gemini = env.cfg.gemini.copy(autoSwitchModel = false))
        env.tracker.lock("gemini-3.5-transcribe", env.tracker.clock() + 5 * 3600, LockReason.DAILY_QUOTA, "429")
        assertIs<StartProblem.AllModelsLocked>(runBlocking { r.checkStart() })
        env.cfg = env.cfg.copy(gemini = env.cfg.gemini.copy(diarizationEnabled = true, diarizationMethod = "pyannote"))
        assertIs<StartProblem.Constraint>(runBlocking { r.checkStart() })
    }

    @Test
    fun `bỏ mục cũ của cùng tệp thì không xoá thư mục tạm bản thêm lại đang dùng`() {
        val env = Env(cfg = testConfig().copy(deleteTempWhenDone = false), client = FakeClient { _, _ -> text("x") })
        val r = runner(env)
        val old = r.add(listOf(item("a.wav"))).single()
        runBlocking { r.run() }
        val again = r.add(listOf(item("a.wav"))).single()
        r.store.updateItem(again.id) { it.copy(status = ItemStatus.RUNNING, tempDir = r.state.value.items[0].tempDir) }
        val temp = File(r.state.value.items[0].tempDir!!)
        r.clearFinished()
        assertTrue(temp.isDirectory)
        assertEquals(listOf(again.id), r.state.value.items.map { it.id })
        assertNull(r.remove(again.id)) // đang chạy: không bỏ được
        assertTrue(old.id != again.id)
    }

    @Test
    fun `thêm lại tệp còn lỗi hoặc đã dừng thì bỏ qua, tệp đã xong thì thêm được`() {
        val env = Env(client = FakeClient { _, _ -> GoogleAIException("HTTP 500 lỗi tạm", ErrorKind.TEMPORARY, 500) })
        val r = runner(env)
        r.add(listOf(item("a.wav")))
        runBlocking { r.run() }
        assertEquals(listOf("a.wav" to ItemStatus.ERROR), r.status())
        assertEquals(0, r.add(listOf(item("a.wav"))).size)
        env.client = FakeClient { _, _ -> text("x") }
        runBlocking { r.run() }
        assertEquals(listOf("a.wav" to ItemStatus.DONE), r.status())
        assertEquals(1, r.add(listOf(item("a.wav"))).size)
    }
}

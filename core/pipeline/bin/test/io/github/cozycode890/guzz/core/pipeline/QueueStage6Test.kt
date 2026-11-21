package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.common.log.LogLevel
import io.github.cozycode890.guzz.core.common.log.MemorySink
import io.github.cozycode890.guzz.core.common.log.SinkLogger
import io.github.cozycode890.guzz.core.gemini.ChunkTranscriber
import io.github.cozycode890.guzz.core.gemini.QuotaTracker
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stage 6 A3–A4: hàng đợi mang `request_id`, ghi đè cài đặt theo tệp, số ký tự / model khi xong. */
class QueueStage6Test {
    @TempDir
    lateinit var tmp: File

    private val log = MemorySink()
    private val logger = SinkLogger().apply { level = LogLevel.DEBUG; addSink(log) }

    private inner class Env(val client: ChunkTranscriber) : QueueEnvironment {
        val seen = mutableListOf<PipelineConfig>()
        val salts = mutableListOf<String>()
        override suspend fun loadConfig() = testConfig()
        override fun client(cfg: PipelineConfig): ChunkTranscriber {
            seen += cfg
            return client
        }
        override val tracker = QuotaTracker()
        override fun source(item: QueueItem) = SyntheticSource(item.displayName)
        override fun output(cfg: PipelineConfig, item: QueueItem) = DirectoryOutputFolder(File(tmp, "out"))
        override fun cacheSalt(cfg: PipelineConfig) = (if (cfg.debugFakeGemini == true) "gia" else "").also { salts += it }
    }

    private val storeFile get() = File(tmp, "queue/${QueueStore.FILENAME}")
    private fun runner(env: Env) =
        QueueRunner(QueueStore(storeFile, logger), env, FilePipeline(File(tmp, "tmp"), logger, sleep = {}), logger)

    @Test
    fun `tệp hàng đợi cũ (chưa có trường của Stage 6) vẫn đọc được, trường mới giữ qua lần ghi`() {
        storeFile.parentFile.mkdirs()
        storeFile.writeText(
            """{"running": false, "items": [{"id": "x", "source": "/a.wav", "displayName": "a.wav", "status": "cho"}]}""",
        )
        val store = QueueStore(storeFile, logger)
        val old = store.state.value.items.single()
        assertNull(old.requestId)
        assertNull(old.overrides)
        assertEquals(false, old.ownedCopy)
        store.updateItem("x") {
            it.copy(requestId = "r1", overrides = ItemOverrides(speakers = "off"), ownedCopy = true, charCount = 5)
        }
        assertEquals(store.state.value, QueueStore(storeFile, logger).state.value)
    }

    @Test
    fun `ghi đè chỉ áp cho tệp của nó, tệp xong có số ký tự và model`() {
        val env = Env(FakeClient { i, _ -> text("Đoạn $i.") })
        val r = runner(env)
        r.add(
            listOf(
                QueueItem(
                    source = "/t/a.wav", displayName = "a.wav", requestId = "r1",
                    overrides = ItemOverrides(speakers = "gemini", numSpeakers = 2, fakeGemini = true),
                ),
                QueueItem(source = "/t/b.wav", displayName = "b.wav"),
            ),
        )
        runBlocking { r.run() }
        val (a, b) = r.state.value.items
        assertEquals(ItemStatus.DONE, a.status)
        assertEquals(ItemStatus.DONE, b.status)
        assertEquals("r1", a.requestId)
        // Lần gọi client đầu tiên của mỗi tệp mang cấu hình của tệp đó.
        val first = env.seen.first { it.gemini.diarizationEnabled }
        assertEquals("gemini", first.gemini.diarizationMethod)
        assertEquals(2, first.speakers.numSpeakers)
        assertTrue(env.seen.last().gemini.diarizationEnabled.not())
        assertEquals(listOf("gia", ""), env.salts)
        val textA = File(a.outFile!!).readText()
        assertTrue(a.charCount!! > 0 && a.charCount!! <= textA.length)
        assertTrue(a.modelsUsed!!.isNotEmpty())
        assertTrue(log.lines.any { "ghi đè cài đặt speakers=gemini" in it })
        // Chạy lại thì xoá số liệu cũ.
        r.rerun(a.id)
        assertNull(r.state.value.items.first().charCount)
    }

    @Test
    fun `ghi đè không hợp lệ thì chỉ tệp đó lỗi, tệp sau vẫn chạy`() {
        val env = Env(FakeClient { i, _ -> text("Đoạn $i.") })
        val r = runner(env)
        r.add(
            listOf(
                QueueItem(source = "/t/a.wav", displayName = "a.wav", overrides = ItemOverrides(preset = "abc")),
                QueueItem(source = "/t/b.wav", displayName = "b.wav"),
            ),
        )
        val summary = runBlocking { r.run() }
        assertEquals(1, summary.failed)
        assertEquals(1, summary.done)
        val a = r.state.value.items.first()
        assertEquals(ItemStatus.ERROR, a.status)
        assertEquals("cau_hinh", a.errorKind)
    }
}

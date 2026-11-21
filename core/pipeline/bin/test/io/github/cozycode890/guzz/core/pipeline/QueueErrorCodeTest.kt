package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.common.log.LogLevel
import io.github.cozycode890.guzz.core.common.log.MemorySink
import io.github.cozycode890.guzz.core.common.log.SinkLogger
import io.github.cozycode890.guzz.core.gemini.ChunkTranscriber
import io.github.cozycode890.guzz.core.gemini.DiarizationMethod
import io.github.cozycode890.guzz.core.gemini.ErrorKind
import io.github.cozycode890.guzz.core.gemini.GoogleAIException
import io.github.cozycode890.guzz.core.gemini.QuotaTracker
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stage 8 G1: tệp lỗi có mã + tham số để giao diện dịch, `error` tiếng Việt vẫn giữ cho log và sự kiện Stage 6. */
class QueueErrorCodeTest {
    @TempDir
    lateinit var tmp: File

    private val logger = SinkLogger().apply { level = LogLevel.DEBUG; addSink(MemorySink()) }

    private inner class Env(
        val cfg: PipelineConfig = testConfig(),
        val client: ChunkTranscriber = FakeClient { i, _ -> text("Đoạn $i.") },
        val makeSource: (QueueItem) -> ChunkSource = { SyntheticSource(it.displayName) },
    ) : QueueEnvironment {
        override suspend fun loadConfig(): PipelineConfig = cfg
        override fun client(cfg: PipelineConfig): ChunkTranscriber = client
        override val tracker = QuotaTracker()
        override fun source(item: QueueItem): ChunkSource = makeSource(item)
        override fun output(cfg: PipelineConfig, item: QueueItem) = DirectoryOutputFolder(File(tmp, "out"))
        override fun cacheSalt(cfg: PipelineConfig) = ""
    }

    private fun runOne(env: Env, name: String = "a.wav"): QueueItem {
        val r = QueueRunner(QueueStore(null, logger), env, FilePipeline(File(tmp, "tmp"), logger, sleep = {}), logger)
        r.add(listOf(QueueItem(source = "/t/$name", displayName = name)))
        runBlocking { r.run() }
        return r.state.value.items.single()
    }

    /** Nguồn ném lỗi ngay khi hỏi thông tin tệp. */
    private fun failingSource(e: Exception) = object : ChunkSource by SyntheticSource() {
        override fun info(): SourceInfo = throw e
    }

    @Test
    fun `tệp nguồn mất thì có mã source_missing kèm tên tệp`() {
        val item = runOne(Env(makeSource = { failingSource(FileNotFoundException("Không thấy tệp /t/a.wav")) }))
        assertEquals(ItemStatus.ERROR, item.status)
        assertEquals(ErrorCodes.SOURCE_MISSING, item.errorCode)
        assertEquals(listOf("a.wav"), item.errorArgs)
        assertTrue(item.error!!.contains("Không thấy tệp"))
    }

    @Test
    fun `mẫu tên tệp sai thì có mã filename_template kèm mẫu`() {
        val item = runOne(Env(cfg = testConfig().copy(filenameTemplate = "{khong_co}")))
        assertEquals(ErrorCodes.FILENAME_TEMPLATE, item.errorCode)
        assertEquals(listOf("{khong_co}"), item.errorArgs)
        assertEquals("cau_hinh", item.errorKind)
    }

    @Test
    fun `model không hợp cách nhận diện thì có mã ràng buộc kèm model`() {
        val cfg = testConfig().let {
            it.copy(gemini = it.gemini.copy(diarizationEnabled = true, diarizationMethod = DiarizationMethod.PYANNOTE))
        }
        val item = runOne(Env(cfg = cfg))
        assertEquals(ErrorCodes.CONSTRAINT_ON_DEVICE, item.errorCode)
        assertEquals(listOf("gemini-3.5-transcribe"), item.errorArgs)
        assertEquals("rang_buoc", item.errorKind)
    }

    @Test
    fun `lỗi Google AI có mã google_ai, loại lỗi ở errorKind`() {
        val e = GoogleAIException("HTTP 403: API key không hợp lệ", ErrorKind.CONFIG, 403)
        val item = runOne(Env(client = FakeClient { _, _ -> throw e }))
        assertEquals(ErrorCodes.GOOGLE_AI, item.errorCode)
        assertEquals("cau_hinh", item.errorKind)
        assertEquals("HTTP 403: API key không hợp lệ", item.error)
    }

    @Test
    fun `lỗi lạ không có mã, giao diện hiện error nguyên văn`() {
        val item = runOne(Env(makeSource = { failingSource(IllegalStateException("hỏng")) }))
        assertNull(item.errorCode)
        assertEquals(emptyList(), item.errorArgs)
        assertEquals("IllegalStateException: hỏng", item.error)
    }

    @Test
    fun `chạy lại thì xoá mã lỗi cũ`() {
        var broken = true
        val env = Env(makeSource = { if (broken) failingSource(FileNotFoundException("mất")) else SyntheticSource(it.displayName) })
        val r = QueueRunner(QueueStore(null, logger), env, FilePipeline(File(tmp, "tmp"), logger, sleep = {}), logger)
        r.add(listOf(QueueItem(source = "/t/a.wav", displayName = "a.wav")))
        runBlocking { r.run() }
        assertEquals(ErrorCodes.SOURCE_MISSING, r.state.value.items.single().errorCode)
        broken = false
        runBlocking { r.run() }
        val item = r.state.value.items.single()
        assertEquals(ItemStatus.DONE, item.status)
        assertNull(item.errorCode)
        assertEquals(emptyList(), item.errorArgs)
    }
}

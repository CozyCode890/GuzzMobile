package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.audio.ChunkSettings
import io.github.cozycode890.guzz.core.audio.CleanSettings
import io.github.cozycode890.guzz.core.audio.NoSpeechException
import io.github.cozycode890.guzz.core.common.log.LogLevel
import io.github.cozycode890.guzz.core.common.log.MemorySink
import io.github.cozycode890.guzz.core.common.log.SinkLogger
import io.github.cozycode890.guzz.core.remote.JobErrors
import io.github.cozycode890.guzz.core.remote.JobRequest
import io.github.cozycode890.guzz.core.remote.Reasons
import io.github.cozycode890.guzz.core.remote.RemoteJobFailedException
import io.github.cozycode890.guzz.core.remote.RemoteProcessor
import io.github.cozycode890.guzz.core.remote.RemoteSteps
import io.github.cozycode890.guzz.core.remote.RemoteUnavailableException
import io.github.cozycode890.guzz.core.remote.UploadSource
import io.github.cozycode890.guzz.core.speaker.DiarizationJson
import io.github.cozycode890.guzz.core.speaker.DiarizeRequest
import io.github.cozycode890.guzz.core.speaker.SpeakerDiarizer
import io.github.cozycode890.guzz.core.speaker.TurnJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stage 11 E1: pipeline với server giả: thành công thì không xử lý trên máy; lỗi thì chuyển sang máy (D145). */
class RemotePipelineTest {
    @TempDir
    lateinit var tmp: File

    private val log = MemorySink()
    private val logger = SinkLogger().apply { level = LogLevel.DEBUG; addSink(log) }
    private val out by lazy { DirectoryOutputFolder(File(tmp, "out")) }
    private val turns = listOf(TurnJson(0.0, 20.0, "SPEAKER_00"), TurnJson(20.0, 40.0, "SPEAKER_01"))

    /** Nguồn có `upload`: đếm số lần pipeline tự xử lý âm thanh trên máy. */
    private class Source(val noAudio: Boolean = false) : ChunkSource {
        val local = SyntheticSource(noAudio = noAudio)
        var uploads = 0
        var cleaned = 0
        override fun info() = local.info()
        override fun prepare(
            folder: File, digest: String, chunk: ChunkSettings, clean: CleanSettings, saveClean: Boolean,
            saveExtracted: Boolean, audioTrack: String, listener: SourceListener,
        ) = local.prepare(folder, digest, chunk, clean, saveClean, saveExtracted, audioTrack, listener)

        override fun upload(folder: File, audioTrack: String, listener: SourceListener): UploadSource {
            uploads++
            return UploadSource("bai.wav", 4, cleanup = { cleaned++ }) { ByteArrayInputStream(ByteArray(4)) }
        }
    }

    /** Server giả: cắt đoạn bằng chính nguồn tổng hợp, ghi lượt nói cho sẵn; hoặc ném [fail]. */
    private inner class FakeRemote(var fail: Exception? = null) : RemoteProcessor {
        var calls = 0
        var last: JobRequest? = null
        override suspend fun process(
            upload: UploadSource, request: JobRequest, folder: File, log: (String) -> Unit, onProgress: (String, Double) -> Unit,
        ) {
            calls++
            last = request
            onProgress(RemoteSteps.UPLOAD, 1.0)
            fail?.let { throw it }
            onProgress(RemoteSteps.PROCESS, 0.5)
            SyntheticSource().prepare(
                folder, request.audioKey, request.chunk, request.clean, request.saveClean, request.saveExtracted, "auto",
                object : SourceListener {},
            )
            ChunkCache.deletePcm(folder)
            request.speakerKey?.let {
                DiarizationJson.write(File(folder, "nguoi_noi.$it.json"), DiarizationJson("server", durationSec = 0.0, numSpeakers = 2, turns = turns))
            }
            onProgress(RemoteSteps.DOWNLOAD, 1.0)
        }
    }

    private class CountingDiarizer : SpeakerDiarizer {
        var calls = 0
        override suspend fun diarize(request: DiarizeRequest, onProgress: (Double) -> Unit): DiarizationJson {
            calls++
            val d = DiarizationJson("may", durationSec = 0.0, numSpeakers = 1, turns = listOf(TurnJson(0.0, 40.0, "SPEAKER_00")))
            DiarizationJson.write(request.outFile, d)
            return d
        }
    }

    private var cfg = testConfig().let {
        it.copy(
            deleteTempWhenDone = false,
            serverEnabled = true,
            gemini = it.gemini.copy(diarizationEnabled = true, diarizationMethod = "pyannote", model = "gemini-3.8-flash"),
        )
    }

    private fun run(source: Source, remote: RemoteProcessor?, d: SpeakerDiarizer = CountingDiarizer(), progress: (FileProgress) -> Unit = {}) =
        runBlocking {
            FilePipeline(File(tmp, "tmp"), logger, sleep = {}, diarizer = d, remote = remote)
                .run(source, out, cfg, FakeClient { _, _ -> text("Xin chào.") }, onProgress = progress)
        }

    private fun logText() = log.records.joinToString("\n") { it.message }

    @Test
    fun `server thành công thì không cắt đoạn, không tách giọng trên máy`() {
        val src = Source()
        val remote = FakeRemote()
        val d = CountingDiarizer()
        val progress = ArrayList<FileProgress>()
        val r = run(src, remote, d) { progress += it }
        assertTrue(File(r.outFile!!).isFile)
        assertEquals(1, remote.calls)
        assertEquals(0, src.local.prepareCalls)
        assertEquals(0, d.calls)
        assertEquals(1, src.cleaned, "tệp tải lên tạm phải được xoá")
        assertTrue(remote.last!!.speakerKey != null)
        assertTrue(progress.any { it.step == RemoteSteps.PROCESS })
        assertTrue(progress.all { it.serverFallback == null })
        assertEquals(progress.map { it.fraction }.sorted(), progress.map { it.fraction })
    }

    @Test
    fun `cache đủ thì không gọi server`() {
        val src = Source()
        val remote = FakeRemote()
        run(src, remote)
        run(src, remote)
        assertEquals(1, remote.calls)
        assertEquals(1, src.uploads)
    }

    @Test
    fun `tắt tách giọng thì không xin lượt nói`() {
        cfg = cfg.copy(gemini = cfg.gemini.copy(diarizationEnabled = false))
        val remote = FakeRemote()
        run(Source(), remote)
        assertNull(remote.last!!.speakerKey)
    }

    @Test
    fun `lỗi mạng thì log lý do và làm trên máy`() {
        val src = Source()
        val d = CountingDiarizer()
        val progress = ArrayList<FileProgress>()
        val r = run(src, FakeRemote(RemoteUnavailableException(Reasons.NETWORK, "mất mạng")), d) { progress += it }
        assertTrue(File(r.outFile!!).isFile)
        assertEquals(1, src.local.prepareCalls)
        assertEquals(1, d.calls)
        assertTrue("Server lỗi (network)" in logText(), logText())
        assertTrue(progress.any { it.serverFallback == Reasons.NETWORK })
    }

    @Test
    fun `Dừng thì không chuyển sang máy`() {
        val src = Source()
        assertFailsWith<CancellationException> { run(src, FakeRemote(CancellationException("Dừng"))) }
        assertEquals(0, src.local.prepareCalls)
        assertEquals(1, src.cleaned)
    }

    @Test
    fun `không có luồng âm thanh từ server thì bỏ qua như trên máy`() {
        cfg = cfg.copy(skipSilent = true)
        val src = Source()
        val r = run(src, FakeRemote(RemoteJobFailedException(JobErrors.NO_AUDIO, "video không có âm thanh")))
        assertTrue(r.skipped)
        assertEquals(0, src.local.prepareCalls)
    }

    @Test
    fun `không còn tiếng nói từ server thì báo lỗi như trên máy`() {
        val src = Source()
        assertFailsWith<NoSpeechException> { run(src, FakeRemote(RemoteJobFailedException(JobErrors.NO_SPEECH, null))) }
        assertEquals(0, src.local.prepareCalls)
    }

    @Test
    fun `không bật server thì như cũ`() {
        val src = Source()
        run(src, null)
        assertEquals(0, src.uploads)
        assertEquals(1, src.local.prepareCalls)
        cfg = cfg.copy(serverEnabled = false)
        val remote = FakeRemote()
        run(Source(), remote)
        assertEquals(0, remote.calls)
    }
}

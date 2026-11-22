package io.github.cozycode890.guzz.server

import io.github.cozycode890.guzz.core.audio.ChunkEncoder
import io.github.cozycode890.guzz.core.audio.ChunkPreparer
import io.github.cozycode890.guzz.core.audio.ChunkSettings
import io.github.cozycode890.guzz.core.audio.CleanSettings
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.remote.JobErrors
import io.github.cozycode890.guzz.core.remote.JobRequest
import io.github.cozycode890.guzz.core.remote.JobState
import io.github.cozycode890.guzz.core.remote.JobStatus
import io.github.cozycode890.guzz.core.remote.Protocol
import io.github.cozycode890.guzz.core.speaker.DiarizationJson
import io.github.cozycode890.guzz.core.speaker.DiarizationSettings
import io.github.cozycode890.guzz.core.speaker.LabeledTurn
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test của `:server` trên PC (Stage 11 A2, C1, C3–C5). Cần ffmpeg (`GUZZ_FFMPEG`, mặc định ffmpeg của Guzz desktop) và
 * model / tệp thử của plugin guzz.deps; thiếu ffmpeg thì bỏ qua các test cần nó.
 */
class ServerTest {
    @TempDir
    lateinit var tmp: File

    private val testdata = File(System.getProperty("guzz.testdata"))
    private val models = File(System.getProperty("guzz.models"))
    private val fourSpeakers = File(System.getProperty("guzz.testwav"), "0-four-speakers-zh.wav")

    private fun needFfmpeg() = assumeTrue(runCatching { Ffmpeg.version().startsWith("ffmpeg") }.getOrDefault(false), "không có ffmpeg")

    /** Phần `data` của WAV 16-bit mono 16 kHz ra tệp PCM. */
    private fun wavToPcm(wav: File, out: File): File {
        val b = wav.readBytes()
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (id == "data") return out.apply { writeBytes(b.copyOfRange(pos + 8, pos + 8 + size)) }
            pos += 8 + size + (size and 1)
        }
        error("WAV không có data")
    }

    private fun golden(name: String): List<LabeledTurn> =
        DiarizationJson.decode(File(testdata, "golden/diarization/$name").readText()).turns
            .map { LabeledTurn(it.start, it.end, it.speaker.removePrefix("SPEAKER_").toInt()) }

    private fun renumber(turns: List<LabeledTurn>): List<LabeledTurn> {
        val order = LinkedHashMap<Int, Int>()
        return turns.map { it.copy(label = order.getOrPut(it.label) { order.size }) }
    }

    private fun assertTurns(exp: List<LabeledTurn>, got: List<LabeledTurn>, tol: Double = 0.002) {
        assertEquals(exp.size, got.size, "số lượt: $got")
        for (i in exp.indices) {
            assertEquals(exp[i].label, got[i].label, "nhãn lượt $i")
            assertTrue(abs(exp[i].start - got[i].start) <= tol && abs(exp[i].end - got[i].end) <= tol, "lượt $i: ${exp[i]} ≠ ${got[i]}")
        }
    }

    /**
     * A2: Java API nạp được JNI và model, ra trùng `run_sherpa.py` trên PC như bản Android (D1): 4 cụm cố định ra 4
     * người; ngưỡng 1,1 trên tệp 57 s này ra 1 người (golden cũng vậy).
     */
    @Test
    fun `sherpa-onnx Java API ra như run_sherpa`() {
        val pcm = wavToPcm(fourSpeakers, File(tmp, "a.pcm"))
        SherpaJvmPieceEngine(models, DiarizationSettings()).use { e ->
            val n = pcm.length() / 2
            for ((k, file) in listOf(0 to "four_speakers_thr.json", 4 to "four_speakers_k4.json")) {
                val piece = e.runBlocking(pcm, 0, n, k) { _, _ -> }
                assertEquals(if (k == 4) 4 else 1, piece.labels.size, "k=$k")
                assertTurns(golden(file), renumber(piece.turns))
                assertTrue(piece.emb.values.all { it != null && it.size > 100 })
            }
        }
    }

    private fun request(name: String, speakerKey: String? = null, clean: CleanSettings = CleanSettings(), runOnOriginal: Boolean = false) =
        JobRequest(
            fileName = name, sourceName = "/sdcard/$name", audioKey = "ak", speakerKey = speakerKey, clean = clean,
            runOnOriginal = runOnOriginal,
        )

    private fun runJob(input: File, req: JobRequest, cancelled: AtomicBoolean = AtomicBoolean(false)): Pair<Int, JobStatus> {
        val reqFile = File(tmp, "req.json").apply { writeText(Protocol.json.encodeToString(JobRequest.serializer(), req)) }
        val out = File(tmp, "out")
        val status = File(tmp, "status.json")
        val code = runCli(
            arrayOf("process", "--in", input.path, "--request", reqFile.path, "--out", out.path, "--status", status.path, "--models", models.path),
            cancelled, log = {},
        )
        return code to Protocol.json.decodeFromString(JobStatus.serializer(), status.readText())
    }

    /** C1: tệp thử ra đủ tệp cache, mã trong danh sách đoạn, tệp kết quả kê đúng cỡ và SHA-256; không còn PCM tạm. */
    @Test
    fun `tệp thử ra đủ tệp cache`() {
        needFfmpeg()
        val (code, st) = runJob(File(testdata, "media/tone_aac_stereo_44k.m4a"), request("tone.m4a").copy(saveClean = true, saveExtracted = true))
        assertEquals(ExitCodes.OK, code, st.message)
        assertEquals(JobState.DONE, st.state)
        val out = File(tmp, "out")
        val files = st.result!!.files
        assertEquals(out.list()!!.sorted(), files.map { it.name }.sorted())
        assertTrue(files.any { it.name == "doan_001.flac" } && files.any { it.name == "sach.flac" } && files.any { it.name == "am_thanh.flac" })
        for (f in files) {
            assertEquals(File(out, f.name).length(), f.size)
            assertEquals(JobProcessor.sha256(File(out, f.name)), f.sha256)
        }
        val list = ChunkPreparer.readChunkList(out)!!
        assertEquals("ak", list.digest)
        assertEquals("/sdcard/tone.m4a", list.source)
    }

    /** Ghi đoạn ra PCM thô (để so với FLAC của ffmpeg giải mã lại). */
    private class RawEncoder : ChunkEncoder {
        override fun encode(pcm: Pcm16File, range: io.github.cozycode890.guzz.core.audio.SampleRange, sampleRate: Int, out: File) {
            val s = ShortArray(range.length.toInt())
            pcm.readShorts(range.start, s, 0, s.size)
            val b = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            s.forEach { b.putShort(it) }
            out.writeBytes(b.array())
        }
    }

    private fun decodeFlac(f: File): ByteArray {
        val p = ProcessBuilder(Ffmpeg.path, "-nostdin", "-loglevel", "error", "-i", f.path, "-f", "s16le", "-").start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        return bytes
    }

    /** C3: FLAC của ffmpeg giải mã lại trùng từng mẫu; danh sách đoạn / TimeMap trùng với cùng ChunkPreparer. */
    @Test
    fun `đoạn FLAC giải mã lại trùng từng mẫu`() {
        needFfmpeg()
        val raw = File(tmp, "goc.pcm")
        FfmpegDecoder().decode(File(testdata, "media/tone_mp3_48k.mp3"), 0, raw)
        val settings = ChunkSettings(chunkMinutes = 0.015, searchSeconds = 0.3, minLastChunkSeconds = 0.2)
        val a = File(tmp, "a")
        val b = File(tmp, "b")
        val la = Pcm16File(raw).use { ChunkPreparer(settings, FfmpegFlacEncoder()).prepare(it, 16_000, "x", a, digest = "d") }
        val lb = Pcm16File(raw).use { ChunkPreparer(settings, RawEncoder(), "pcm").prepare(it, 16_000, "x", b, digest = "d") }
        assertTrue(la.chunks.size >= 2, "${la.chunks}")
        assertEquals(lb.timeMap, la.timeMap)
        assertEquals(lb.chunks.map { it.startSeconds to it.endSeconds }, la.chunks.map { it.startSeconds to it.endSeconds })
        for ((ca, cb) in la.chunks.zip(lb.chunks)) {
            assertContentEquals(File(b, cb.file).readBytes(), decodeFlac(File(a, ca.file)), ca.file)
        }
    }

    /** C4: tách giọng qua job (âm thanh gốc = WAV thử, một phần) trùng `run_sherpa.py`. */
    @Test
    fun `job tách giọng ra lượt nói như run_sherpa`() {
        needFfmpeg()
        val (code, st) = runJob(fourSpeakers, request("four.wav", speakerKey = "sk", clean = CleanSettings.OFF, runOnOriginal = true))
        assertEquals(ExitCodes.OK, code, st.message)
        val json = DiarizationJson.decode(File(tmp, "out/nguoi_noi.sk.json").readText())
        val got = json.turns.map { LabeledTurn(it.start, it.end, it.speaker.removePrefix("SPEAKER_").toInt()) }
        assertTurns(golden("four_speakers_thr.json"), renumber(got))
        assertTrue(st.result!!.files.any { it.name == "nguoi_noi.sk.json" })
        assertTrue(File(tmp, "out").list()!!.none { it.endsWith(".pcm") || ".phan_" in it })
    }

    /** G3: tách các phần song song ra cùng lượt nói như chạy lần lượt (mỗi bộ sherpa-onnx cùng 4 luồng). */
    @Test
    fun `tách phần song song ra như chạy lần lượt`() {
        needFfmpeg()
        val small = DiarizationSettings(pieceMinutes = 20.0 / 60, overlapSeconds = 2.0)
        val req = request("four.wav", speakerKey = "sk", clean = CleanSettings.OFF, runOnOriginal = true)
            .copy(diarization = small, bounds = io.github.cozycode890.guzz.core.speaker.SpeakerBounds(1, 6))
        fun run(threads: Int): DiarizationJson {
            val reqFile = File(tmp, "req.json").apply { writeText(Protocol.json.encodeToString(JobRequest.serializer(), req)) }
            val out = File(tmp, "out$threads")
            val logs = ArrayList<String>()
            val code = runCli(
                arrayOf(
                    "process", "--in", fourSpeakers.path, "--request", reqFile.path, "--out", out.path,
                    "--status", File(tmp, "st$threads.json").path, "--models", models.path, "--threads", "$threads",
                ),
                log = { logs += it },
            )
            assertEquals(ExitCodes.OK, code, logs.joinToString("\n"))
            if (threads >= 8) assertTrue(logs.any { "song song: 4 phần, 2 bộ" in it }, logs.joinToString("\n"))
            return DiarizationJson.decode(File(out, "nguoi_noi.sk.json").readText())
        }
        val seq = run(4)
        val par = run(8)
        fun turns(d: DiarizationJson) = d.turns.map { LabeledTurn(it.start, it.end, it.speaker.removePrefix("SPEAKER_").toInt()) }
        assertTurns(turns(seq), turns(par), tol = 0.0)
    }

    /** C5: mã thoát và mã lỗi của từng trường hợp. */
    @Test
    fun `mã thoát của từng lỗi`() {
        needFfmpeg()
        val media = File(testdata, "media")
        runJob(File(media, "silent_video.mp4"), request("v.mp4")).let { (c, s) ->
            assertEquals(ExitCodes.NO_AUDIO, c)
            assertEquals(JobErrors.NO_AUDIO, s.error)
        }
        val empty = File(tmp, "empty.wav").apply { writeBytes(emptyWav()) }
        runJob(empty, request("e.wav")).let { (c, s) ->
            assertEquals(ExitCodes.NO_SPEECH, c, s.message)
            assertEquals(JobErrors.NO_SPEECH, s.error)
        }
        runJob(File(media, "tone_opus.ogg"), request("o.ogg"), AtomicBoolean(true)).let { (c, s) ->
            assertEquals(ExitCodes.CANCELLED, c)
            assertEquals(JobErrors.CANCELLED, s.error)
            assertTrue(File(tmp, "out").list()!!.isEmpty())
        }
        runJob(File(media, "tone_opus.ogg"), request("o.ogg").copy(protocol = 99)).let { (c, s) ->
            assertEquals(ExitCodes.BAD_REQUEST, c)
            assertEquals(JobErrors.BAD_REQUEST, s.error)
        }
        runJob(File(media, "broken.m4a"), request("b.m4a")).let { (c, s) ->
            assertEquals(ExitCodes.INTERNAL, c)
            assertEquals(JobErrors.INTERNAL, s.error)
        }
        assertEquals(ExitCodes.BAD_REQUEST, runCli(arrayOf("process", "--in", "khong_co"), log = {}))
    }

    private fun emptyWav(): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(0)
        return b.array()
    }
}

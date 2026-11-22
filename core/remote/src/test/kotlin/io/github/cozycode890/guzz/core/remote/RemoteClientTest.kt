package io.github.cozycode890.guzz.core.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** E2, E3: client với server giả (MockWebServer): mỗi lỗi ra đúng loại để pipeline chuyển sang máy hay không. */
class RemoteClientTest {
    @TempDir
    lateinit var dir: File

    private val server = MockWebServer()
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @AfterEach
    fun tearDown() = server.close()

    private val files = mapOf(
        "danh_sach_doan.json" to """{"ma":"ak","nguon":"x","tan_so_lay_mau":16000,"thoi_luong_goc":1.0,"ban_do_thoi_gian":[],"doan":[]}""".toByteArray(),
        "doan_001.flac" to ByteArray(3000) { (it % 251).toByte() },
        "nguoi_noi.sk.json" to """{"doan":[]}""".toByteArray(),
    )

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Tar ustar tối giản như `tarfile` của Python. */
    private fun tar(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "%011o\u0000".format(data.size).toByteArray().copyInto(h, 124)
            h[156] = '0'.code.toByte()
            "ustar\u0000".toByteArray().copyInto(h, 257)
            out.write(h)
            out.write(data)
            out.write(ByteArray(((512 - data.size % 512) % 512)))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun json(s: JobStatus) = MockResponse.Builder().body(Protocol.json.encodeToString(JobStatus.serializer(), s)).build()

    private val done = JobStatus(
        id = "j1", state = JobState.DONE, fraction = 1.0,
        result = JobResult(files.map { ResultFile(it.key, it.value.size.toLong(), sha(it.value)) }),
    )

    /** Server giả: `routes` theo "METHOD /đường-dẫn", trả lời lần lượt nếu có nhiều. */
    private fun serve(routes: Map<String, List<MockResponse>>) {
        val queues = routes.mapValues { ArrayDeque(it.value) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.url.encodedPath}"
                seen += key
                val q = queues[key] ?: return MockResponse.Builder().code(404).build()
                return if (q.size > 1) q.removeFirst() else q.first()
            }
        }
        server.start()
    }

    /** Phần 3 byte: tệp 4 byte thành 2 phần (D150). */
    private fun client(token: String = "k.s") =
        RemoteClient(http, server.url("/").toString(), token, pollMs = 1, sleep = {}, partSize = 3)

    private val upload = UploadSource("bai.m4a", 4) { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
    private val req = JobRequest(fileName = "bai.m4a", sourceName = "s", audioKey = "ak", speakerKey = "sk")

    private val okRoutes = mapOf(
        "POST /jobs" to listOf(json(JobStatus(id = "j1", state = JobState.UPLOADING))),
        "PUT /jobs/j1/parts/0" to listOf(json(JobStatus(id = "j1", state = JobState.UPLOADING, received = 3))),
        "PUT /jobs/j1/parts/1" to listOf(json(JobStatus(id = "j1", state = JobState.UPLOADING, received = 4))),
        "POST /jobs/j1/start" to listOf(json(JobStatus(id = "j1", state = JobState.QUEUED))),
        "GET /jobs/j1" to listOf(json(JobStatus(id = "j1", state = JobState.RUNNING, fraction = 0.5)), json(done)),
        "GET /jobs/j1/result" to listOf(MockResponse.Builder().body(Buffer().write(tar(files))).build()),
        "DELETE /jobs/j1" to listOf(MockResponse.Builder().body("{}").build()),
    )

    @Test
    fun `thành công - tải lên, chờ, tải về, kiểm, xoá job`() = runTest {
        serve(okRoutes)
        val steps = ArrayList<String>()
        client().process(upload, req, dir, onProgress = { s, _ -> steps += s })
        for ((name, data) in files) assertContentEquals(data, File(dir, name).readBytes(), name)
        assertEquals(
            listOf(
                "POST /jobs", "PUT /jobs/j1/parts/0", "PUT /jobs/j1/parts/1", "POST /jobs/j1/start", "GET /jobs/j1",
                "GET /jobs/j1", "GET /jobs/j1/result", "DELETE /jobs/j1",
            ),
            seen.toList(),
        )
        assertFalse(File(dir, RemoteClient.JOB_FILE).exists())
        assertEquals(listOf(RemoteSteps.UPLOAD, RemoteSteps.PROCESS, RemoteSteps.DOWNLOAD), steps.distinct())
        val post = server.takeRequest()
        assertEquals("Bearer k.s", post.headers["Authorization"])
        assertTrue("request=" in post.url.toString() && "parts=1" in post.url.toString())
        assertContentEquals(byteArrayOf(1, 2, 3), server.takeRequest().body!!.toByteArray())
        assertContentEquals(byteArrayOf(4), server.takeRequest().body!!.toByteArray())
    }

    private fun unavailable(routes: Map<String, List<MockResponse>>): RemoteUnavailableException {
        serve(routes)
        return assertFailsWith<RemoteUnavailableException> { runBlocking { client().process(upload, req, dir) } }
    }

    @Test
    fun `401 thì lỗi token`() {
        assertEquals(Reasons.AUTH, unavailable(mapOf("POST /jobs" to listOf(MockResponse.Builder().code(401).build()))).reason)
    }

    @Test
    fun `413 và 5xx thì lỗi HTTP`() {
        assertEquals(Reasons.HTTP, unavailable(mapOf("POST /jobs" to listOf(MockResponse.Builder().code(413).build()))).reason)
    }

    @Test
    fun `5xx lúc chờ`() {
        val e = unavailable(okRoutes + ("GET /jobs/j1" to listOf(MockResponse.Builder().code(503).build())))
        assertEquals(Reasons.HTTP, e.reason)
        assertTrue("DELETE /jobs/j1" in seen, "lỗi thì vẫn xoá job trên server")
    }

    @Test
    fun `khác phiên bản giao thức`() {
        assertEquals(Reasons.PROTOCOL, unavailable(mapOf("POST /jobs" to listOf(MockResponse.Builder().code(409).build()))).reason)
    }

    @Test
    fun `trạng thái mang phiên bản khác`() {
        val e = unavailable(okRoutes + ("GET /jobs/j1" to listOf(json(JobStatus(protocol = 99, id = "j1", state = JobState.RUNNING)))))
        assertEquals(Reasons.PROTOCOL, e.reason)
    }

    @Test
    fun `phần lỗi mãi thì thử lại hết lượt rồi chuyển sang máy, xoá job`() {
        serve(okRoutes + ("PUT /jobs/j1/parts/0" to listOf(MockResponse.Builder().code(503).build())))
        val e = assertFailsWith<RemoteUnavailableException> { runBlocking { client().process(upload, req, dir) } }
        assertEquals(Reasons.HTTP, e.reason)
        assertEquals(4, seen.count { it == "PUT /jobs/j1/parts/0" }, "1 lần + 3 lần thử lại")
        assertTrue("DELETE /jobs/j1" in seen)
    }

    @Test
    fun `token sai lúc gửi phần thì không thử lại`() {
        serve(okRoutes + ("PUT /jobs/j1/parts/0" to listOf(MockResponse.Builder().code(401).build())))
        assertEquals(Reasons.AUTH, assertFailsWith<RemoteUnavailableException> { runBlocking { client().process(upload, req, dir) } }.reason)
        assertEquals(1, seen.count { it == "PUT /jobs/j1/parts/0" })
    }

    @Test
    fun `D150 phần lỗi 5xx thì chờ rồi gửi lại phần đó`() = runTest {
        serve(
            okRoutes + (
                "PUT /jobs/j1/parts/1" to listOf(
                    MockResponse.Builder().code(503).build(),
                    json(JobStatus(id = "j1", state = JobState.UPLOADING, received = 4)),
                )
                ),
        )
        val waits = ArrayList<Long>()
        val c = RemoteClient(http, server.url("/").toString(), "k.s", pollMs = 1, sleep = { waits += it }, partSize = 3)
        c.process(upload, req, dir)
        assertEquals(2, seen.count { it == "PUT /jobs/j1/parts/1" })
        assertEquals(1, seen.count { it == "PUT /jobs/j1/parts/0" })
        assertTrue(2_000L in waits, "$waits")
        assertTrue(File(dir, "doan_001.flac").isFile)
    }

    @Test
    fun `D150 nối lại job đang nhận tệp thì gửi tiếp từ phần server đã có`() = runTest {
        serve(okRoutes - "POST /jobs" + ("GET /jobs/j1" to listOf(
            json(JobStatus(id = "j1", state = JobState.UPLOADING, received = 3)),
            json(done),
        )))
        File(dir, RemoteClient.JOB_FILE).writeText("""{"id":"j1","audioKey":"ak","speakerKey":"sk"}""")
        val logs = ArrayList<String>()
        client().process(upload, req, dir, log = { logs += it })
        assertTrue(seen.none { it == "POST /jobs" || it == "PUT /jobs/j1/parts/0" }, "$seen")
        assertEquals(1, seen.count { it == "PUT /jobs/j1/parts/1" })
        assertTrue(logs.any { "Tải lên tiếp từ phần 2" in it }, "$logs")
    }

    @Test
    fun `ngắt giữa lúc tải về`() {
        val cut = tar(files).copyOf(1500)
        val e = unavailable(okRoutes + ("GET /jobs/j1/result" to listOf(MockResponse.Builder().body(Buffer().write(cut)).build())))
        assertTrue(e.reason == Reasons.NETWORK || e.reason == Reasons.RESULT, e.reason)
        assertFalse(File(dir, "danh_sach_doan.json").exists())
    }

    @Test
    fun `tar thiếu tệp hoặc tệp hỏng`() {
        val e = unavailable(okRoutes + ("GET /jobs/j1/result" to listOf(MockResponse.Builder().body(Buffer().write(tar(files - "doan_001.flac"))).build())))
        assertEquals(Reasons.RESULT, e.reason)
        assertFalse(File(dir, "danh_sach_doan.json").exists(), "không để lại danh sách đoạn khi thiếu tệp")
    }

    @Test
    fun `không có luồng âm thanh thì không chuyển sang máy`() {
        serve(okRoutes + ("GET /jobs/j1" to listOf(json(JobStatus(id = "j1", state = JobState.FAILED, error = JobErrors.NO_AUDIO)))))
        val e = assertFailsWith<RemoteJobFailedException> { runBlocking { client().process(upload, req, dir) } }
        assertEquals(JobErrors.NO_AUDIO, e.code)
    }

    @Test
    fun `mất mạng`() {
        val c = RemoteClient(http, "http://127.0.0.1:1/", "k.s", pollMs = 1, sleep = {})
        val e = assertFailsWith<RemoteUnavailableException> { runBlocking { c.process(upload, req, dir) } }
        assertEquals(Reasons.NETWORK, e.reason)
    }

    @Test
    fun `quá thời gian chờ`() {
        serve(okRoutes + ("GET /jobs/j1" to listOf(json(JobStatus(id = "j1", state = JobState.RUNNING)))))
        var t = 0L
        val c = RemoteClient(http, server.url("/").toString(), "k.s", pollMs = 1, maxWaitMs = 10, sleep = { t += 5 }, now = { t })
        val e = assertFailsWith<RemoteUnavailableException> { runBlocking { c.process(upload, req, dir) } }
        assertEquals(Reasons.TIMEOUT, e.reason)
    }

    @Test
    fun `E3 nối lại job đã lưu, không tải lên lại`() = runTest {
        serve(okRoutes - "POST /jobs" - "PUT /jobs/j1/parts/0" - "PUT /jobs/j1/parts/1" - "POST /jobs/j1/start")
        File(dir, RemoteClient.JOB_FILE).writeText("""{"id":"j1","audioKey":"ak","speakerKey":"sk"}""")
        val logs = ArrayList<String>()
        client().process(upload, req, dir, log = { logs += it })
        assertTrue(seen.none { it.startsWith("POST") || it.startsWith("PUT") }, "$seen")
        assertTrue(logs.any { "Nối lại job j1" in it }, "$logs")
        assertTrue(File(dir, "doan_001.flac").isFile)
    }

    @Test
    fun `E3 job cũ không còn thì tải lên lại`() = runTest {
        serve(okRoutes + ("GET /jobs/old" to listOf(MockResponse.Builder().code(404).build())))
        File(dir, RemoteClient.JOB_FILE).writeText("""{"id":"old","audioKey":"ak","speakerKey":"sk"}""")
        client().process(upload, req, dir)
        assertEquals(1, seen.count { it == "POST /jobs" })
    }

    @Test
    fun `Dừng lúc server đang xử lý thì xoá job, không đổi lỗi`() {
        serve(okRoutes + ("GET /jobs/j1" to listOf(json(JobStatus(id = "j1", state = JobState.RUNNING)))))
        var calls = 0
        val c = RemoteClient(http, server.url("/").toString(), "k.s", pollMs = 1, sleep = {
            if (++calls == 2) throw CancellationException("Dừng")
        })
        assertFailsWith<CancellationException> { runBlocking { c.process(upload, req, dir) } }
        assertTrue("DELETE /jobs/j1" in seen, "$seen")
        assertFalse(File(dir, RemoteClient.JOB_FILE).exists())
    }

    @Test
    fun `health kiểm phiên bản`() = runTest {
        serve(mapOf("GET /health" to listOf(MockResponse.Builder().body("""{"protocol":1,"server":"1.1.0"}""").build())))
        assertEquals("1.1.0", client().health().server)
    }

    @Test
    fun `đọc tar có header pax`() {
        val data = "xin chao".toByteArray()
        val pax = "22 path=doan_002.flac\n".toByteArray()
        val out = ByteArrayOutputStream()
        fun header(name: String, size: Int, type: Char) = ByteArray(512).also { h ->
            name.toByteArray().copyInto(h, 0)
            "%011o\u0000".format(size).toByteArray().copyInto(h, 124)
            h[156] = type.code.toByte()
        }
        out.write(header("PaxHeader", pax.size, 'x')); out.write(pax); out.write(ByteArray(512 - pax.size))
        out.write(header("ten-dai", data.size, '0')); out.write(data); out.write(ByteArray(512 - data.size))
        out.write(ByteArray(1024))
        Tar.extract(ByteArrayInputStream(out.toByteArray()), dir, setOf("doan_002.flac"))
        assertContentEquals(data, File(dir, "doan_002.flac").readBytes())
    }
}

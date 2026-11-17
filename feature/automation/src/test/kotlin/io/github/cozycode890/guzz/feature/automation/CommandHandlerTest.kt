package io.github.cozycode890.guzz.feature.automation

import io.github.cozycode890.guzz.core.common.log.LogLevel
import io.github.cozycode890.guzz.core.common.log.MemorySink
import io.github.cozycode890.guzz.core.common.log.SinkLogger
import io.github.cozycode890.guzz.core.pipeline.ItemOverrides
import io.github.cozycode890.guzz.core.pipeline.ItemStatus
import io.github.cozycode890.guzz.core.pipeline.QueueItem
import io.github.cozycode890.guzz.core.pipeline.QueueState
import io.github.cozycode890.guzz.feature.automation.GuzzIntents as G
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 6 B2: công tắc, mã bí mật, đọc lệnh, thêm tệp, bắt đầu. */
class CommandHandlerTest {
    private val log = MemorySink()
    private val logger = SinkLogger().apply { level = LogLevel.DEBUG; addSink(log) }

    private class FakeEnv : CommandEnv {
        var enabled = true
        var secret = ""
        var includePaths = true
        var problem: String? = null
        var startOutcome = StartOutcome.STARTED
        var active = false
        val added = ArrayList<Triple<Resolved, String?, ItemOverrides?>>()
        val existing = HashSet<String>()
        var starts = 0
        var stops = 0
        var state = QueueState()

        override suspend fun enabled() = enabled
        override suspend fun secret() = secret
        override suspend fun includePaths() = includePaths
        override suspend fun resolve(source: String, isPath: Boolean): Resolved = when {
            source.contains("khong-co") -> Resolved.Failed(G.Kind.NOT_FOUND, "không thấy tệp $source")
            isPath -> Resolved.Path(source)
            else -> Resolved.Uri(source)
        }
        override fun add(resolved: Resolved, requestId: String?, overrides: ItemOverrides?): Boolean {
            val key = resolved.toString()
            if (!existing.add(key)) return false
            added += Triple(resolved, requestId, overrides)
            return true
        }
        override suspend fun checkStart() = problem
        override fun start(): StartOutcome {
            starts++
            return startOutcome
        }
        override fun stop() {
            stops++
        }
        override fun state() = state
        override fun active() = active
    }

    private val env = FakeEnv()
    private val handler = CommandHandler(env, logger)

    private fun run(action: String, vararg extras: Pair<String, Any?>) =
        runBlocking { handler.handle(action, mapOf(*extras), emptyList(), "test") }

    private fun kinds(o: CommandHandler.Outcome) = o.replies.map { G.shortName(it.action) + ":" + it.extras[G.EXTRA_ERROR_KIND] }

    @Test
    fun `công tắc tắt thì từ chối, không thêm gì`() {
        env.enabled = false
        val o = run(G.ACTION_TRANSCRIBE, G.EXTRA_PATH to "/a.m4a", G.EXTRA_REQUEST_ID to "r")
        assertEquals(listOf("ERROR:disabled"), kinds(o))
        assertEquals("r", o.replies.single().requestId)
        assertTrue(env.added.isEmpty())
        assertEquals(0, env.starts)
    }

    @Test
    fun `mã bí mật đúng, sai, thiếu, ô trống`() {
        env.secret = "abc123"
        assertEquals(listOf("ERROR:bad_secret"), kinds(run(G.ACTION_START, G.EXTRA_SECRET to "abc124")))
        assertEquals(listOf("ERROR:bad_secret"), kinds(run(G.ACTION_START)))
        assertEquals(0, env.starts)
        assertTrue(run(G.ACTION_START, G.EXTRA_SECRET to " abc123 ").replies.isEmpty())
        assertEquals(1, env.starts)
        env.secret = ""
        assertTrue(run(G.ACTION_START, G.EXTRA_SECRET to "gì cũng được").replies.isEmpty())
        // Log không bao giờ chứa mã bí mật.
        assertFalse(log.lines.any { "abc12" in it })
        assertTrue(CommandHandler.sameSecret("x", "x"))
        assertFalse(CommandHandler.sameSecret("x", "xy"))
    }

    @Test
    fun `TRANSCRIBE thêm tệp kèm request_id và ghi đè rồi bắt đầu, start=false chỉ thêm`() {
        val o = run(
            G.ACTION_TRANSCRIBE, G.EXTRA_PATHS to "/a.m4a\n/b.m4a", G.EXTRA_REQUEST_ID to "r1", G.EXTRA_SPEAKERS to "off",
        )
        assertTrue(o.replies.isEmpty())
        assertEquals(2, o.added)
        assertEquals(1, env.starts)
        assertEquals(Triple(Resolved.Path("/a.m4a"), "r1", ItemOverrides(speakers = "off")), env.added[0])
        val o2 = run(G.ACTION_TRANSCRIBE, G.EXTRA_URI to "content://x/1", G.EXTRA_START to false)
        assertEquals(1, o2.added)
        assertEquals(1, env.starts)
        assertTrue(env.added.last().first is Resolved.Uri)
    }

    @Test
    fun `tệp không đọc được thì FILE_FAILED, tệp trùng thì ERROR duplicate, tệp khác vẫn thêm`() {
        run(G.ACTION_TRANSCRIBE, G.EXTRA_PATH to "/a.m4a", G.EXTRA_START to "0")
        env.problem = CommandHandler.NOTHING_TO_DO
        val o = run(G.ACTION_TRANSCRIBE, G.EXTRA_PATHS to arrayOf("/a.m4a", "/khong-co.m4a", "/c.m4a"), G.EXTRA_REQUEST_ID to "r2")
        assertEquals(listOf("ERROR:duplicate", "FILE_FAILED:not_found"), kinds(o).sorted())
        assertEquals(1, o.added)
        val failed = o.replies.first { it.action == G.EVENT_FILE_FAILED }.extras
        assertEquals("r2", failed[G.EXTRA_REQUEST_ID])
        assertEquals("khong-co.m4a", failed[G.EXTRA_NAME])
        assertEquals("/khong-co.m4a", failed[G.EXTRA_SOURCE])
        // Không còn gì để chạy thì không phải lỗi.
        assertEquals(0, env.starts)
    }

    @Test
    fun `extra sai thì ERROR bad_extra, thiếu key thì not_ready, bị chặn chạy nền thì background_blocked`() {
        assertEquals(listOf("ERROR:bad_extra"), kinds(run(G.ACTION_TRANSCRIBE, G.EXTRA_PATH to "/a", G.EXTRA_PRESET to "x")))
        assertEquals(listOf("ERROR:bad_extra"), kinds(run("io.github.cozycode890.guzz.action.LAM_GI")))
        env.problem = "Chưa có API key"
        assertEquals(listOf("ERROR:not_ready"), kinds(run(G.ACTION_TRANSCRIBE, G.EXTRA_PATH to "/a")))
        env.problem = null
        env.startOutcome = StartOutcome.BLOCKED
        val o = run(G.ACTION_TRANSCRIBE, G.EXTRA_PATH to "/b")
        assertEquals(listOf("ERROR:background_blocked"), kinds(o))
        assertTrue(o.blocked)
        assertEquals(1, o.added)
    }

    @Test
    fun `đang chạy thì không khởi động lại, STOP và STATUS`() {
        env.active = true
        run(G.ACTION_TRANSCRIBE, G.EXTRA_PATH to "/a")
        assertEquals(0, env.starts)
        run(G.ACTION_STOP)
        assertEquals(1, env.stops)
        env.state = QueueState(true, listOf(QueueItem(source = "/a", displayName = "a", status = ItemStatus.RUNNING, progress = 0.5)))
        val s = run(G.ACTION_STATUS, G.EXTRA_REQUEST_ID to "s").replies.single()
        assertEquals(G.EVENT_STATUS, s.action)
        assertEquals("50", s.extras[G.EXTRA_PROGRESS])
        assertEquals("s", s.requestId)
    }
}

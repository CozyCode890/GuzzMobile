package io.github.cozycode890.guzz.feature.automation

import io.github.cozycode890.guzz.core.pipeline.ItemStatus
import io.github.cozycode890.guzz.core.pipeline.QueueItem
import io.github.cozycode890.guzz.core.pipeline.QueueState
import io.github.cozycode890.guzz.feature.automation.GuzzIntents as G
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 6 C1: so trạng thái hàng đợi → sự kiện. */
class EventTrackerTest {
    private val a = QueueItem(id = "a", source = "/sdcard/Rec/a.m4a", displayName = "a.m4a", requestId = "r1", durationSec = 61.0)
    private val b = QueueItem(id = "b", source = "content://x/b", displayName = "b.m4a")
    private val c = QueueItem(id = "c", source = "/sdcard/Rec/c.m4a", displayName = "c.m4a")

    private fun st(running: Boolean, vararg items: QueueItem) = QueueState(running, items.toList())
    private fun QueueItem.to(s: ItemStatus) = copy(status = s)
    private fun names(events: List<AutomationEvent>) = events.map { G.shortName(it.action) }

    @Test
    fun `một vòng ba tệp, xong, lỗi, bỏ qua`() {
        val t = EventTracker()
        val out = ArrayList<AutomationEvent>()
        fun step(s: QueueState) = out.addAll(t.update(s, includePaths = true))
        step(st(false, a, b, c))
        step(st(true, a, b, c))
        step(st(true, a.to(ItemStatus.RUNNING), b, c))
        step(
            st(
                true,
                a.copy(status = ItemStatus.DONE, outFile = "/sdcard/Documents/Guzz/a.txt", charCount = 120, modelsUsed = listOf("m1", "m2")),
                b.to(ItemStatus.RUNNING), c,
            ),
        )
        step(st(true, a.to(ItemStatus.DONE), b.copy(status = ItemStatus.ERROR, error = "hỏng", errorKind = "khac"), c.to(ItemStatus.RUNNING)))
        step(st(false, a.to(ItemStatus.DONE), b.to(ItemStatus.ERROR), c.to(ItemStatus.SKIPPED)))
        assertEquals(
            listOf("FILE_STARTED", "FILE_DONE", "FILE_STARTED", "FILE_FAILED", "FILE_STARTED", "FILE_DONE", "QUEUE_FINISHED"),
            names(out),
        )
        val done = out[1].extras
        assertEquals("r1", done[G.EXTRA_REQUEST_ID])
        assertEquals("/sdcard/Rec/a.m4a", done[G.EXTRA_SOURCE])
        assertEquals("/sdcard/Documents/Guzz/a.txt", done[G.EXTRA_OUTPUT_PATH])
        assertEquals("61.0", done[G.EXTRA_DURATION_SEC])
        assertEquals("120", done[G.EXTRA_CHARS])
        assertEquals("m1,m2", done[G.EXTRA_MODELS])
        assertEquals("false", done[G.EXTRA_SKIPPED])
        assertEquals("khac", out[3].extras[G.EXTRA_ERROR_KIND])
        assertEquals("true", out[5].extras[G.EXTRA_SKIPPED])
        assertEquals(
            mapOf(G.EXTRA_DONE to "1", G.EXTRA_FAILED to "1", G.EXTRA_SKIPPED to "1", G.EXTRA_STOPPED to "false"),
            out.last().extras,
        )
    }

    @Test
    fun `bấm Dừng giữa chừng, một QUEUE_FINISHED có stopped, không lặp lại khi tệp về dung`() {
        val t = EventTracker()
        t.update(st(true, a.to(ItemStatus.RUNNING), b), true)
        val stop = t.update(st(false, a.to(ItemStatus.RUNNING), b), true)
        assertEquals(listOf("QUEUE_FINISHED"), names(stop))
        assertEquals("true", stop.single().extras[G.EXTRA_STOPPED])
        assertTrue(t.update(st(false, a.to(ItemStatus.STOPPED), b), true).isEmpty())
    }

    @Test
    fun `process mới đọc hàng đợi đã xong thì không phát gì`() {
        val t = EventTracker()
        assertTrue(t.update(st(false, a.to(ItemStatus.DONE), b.to(ItemStatus.ERROR)), true).isEmpty())
        assertTrue(t.update(st(false, a.to(ItemStatus.DONE), b.to(ItemStatus.ERROR)), true).isEmpty())
    }

    @Test
    fun `cả vòng lọt giữa hai lần đọc vẫn có FILE_DONE và QUEUE_FINISHED`() {
        val t = EventTracker()
        t.update(st(false, a), true)
        assertEquals(listOf("FILE_DONE", "QUEUE_FINISHED"), names(t.update(st(false, a.to(ItemStatus.DONE)), true)))
    }

    @Test
    fun `tắt kèm đường dẫn thì không có source, output_path, output_uri`() {
        val t = EventTracker()
        t.update(st(true, b.to(ItemStatus.RUNNING)), true)
        val e = t.update(st(true, b.copy(status = ItemStatus.DONE, outFile = "content://tree/doc")), includePaths = false)
        val done = e.single().extras
        assertFalse(done.containsKey(G.EXTRA_SOURCE))
        assertFalse(done.containsKey(G.EXTRA_OUTPUT_PATH))
        assertFalse(done.containsKey(G.EXTRA_OUTPUT_URI))
        assertEquals("b.m4a", done[G.EXTRA_NAME])
        val withPaths = EventFactory.fileDone(b.copy(status = ItemStatus.DONE, outFile = "content://tree/doc"), true).extras
        assertEquals("content://tree/doc", withPaths[G.EXTRA_OUTPUT_URI])
    }

    @Test
    fun `STATUS có phần trăm và đoạn của tệp đang chạy`() {
        val s = st(true, a.copy(status = ItemStatus.RUNNING, progress = 0.456, chunk = 2, chunkTotal = 6), b, c.to(ItemStatus.DONE))
        val e = EventFactory.status("q", s, active = true).extras
        assertEquals("q", e[G.EXTRA_REQUEST_ID])
        assertEquals("true", e[G.EXTRA_RUNNING])
        assertEquals("1", e[G.EXTRA_WAITING])
        assertEquals("1", e[G.EXTRA_DONE])
        assertEquals("a.m4a", e[G.EXTRA_CURRENT])
        assertEquals("46", e[G.EXTRA_PROGRESS])
        assertEquals("2", e[G.EXTRA_CHUNK])
        assertEquals("6", e[G.EXTRA_CHUNK_TOTAL])
    }

    @Test
    fun `tên tệp từ đường dẫn và URI tài liệu`() {
        assertEquals("a.m4a", EventFactory.displayName("/sdcard/Rec/a.m4a"))
        assertEquals(
            "bài 1.m4a",
            EventFactory.displayName("content://com.android.externalstorage.documents/tree/primary%3ARec/document/primary%3ARec%2Fb%C3%A0i%201.m4a"),
        )
    }
}

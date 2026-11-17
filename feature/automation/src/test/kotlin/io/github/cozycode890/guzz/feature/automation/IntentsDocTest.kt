package io.github.cozycode890.guzz.feature.automation

import io.github.cozycode890.guzz.feature.automation.GuzzIntents as G
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Stage 6 G1: `docs/intents.md` liệt kê đúng các action / event / extra / error_kind của [GuzzIntents]. */
class IntentsDocTest {
    private val doc: String = run {
        // Thư mục chạy test là thư mục module; tìm lên gốc repo.
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/intents.md").isFile) dir = dir.parentFile
        File(requireNotNull(dir) { "không thấy docs/intents.md" }, "docs/intents.md").readText()
    }

    @Test
    fun `mọi tên trong GuzzIntents có trong tài liệu`() {
        val names = G.actions + G.events + listOf(G.RECEIVER_CLASS, G.ACTIVITY_CLASS) +
            (G.commandExtras + G.eventExtras + G.Kind.all).map { "`$it`" }
        val missing = names.filter { it !in doc }
        assertTrue("tài liệu thiếu: $missing", missing.isEmpty())
    }

    @Test
    fun `tài liệu không nhắc action hay event không có`() {
        val inDoc = Regex("""io\.github\.cozycode890\.guzz\.(action|event)\.[A-Z_]+""").findAll(doc).map { it.value }.toSet()
        assertEquals(emptySet<String>(), inDoc - (G.actions + G.events).toSet())
    }
}

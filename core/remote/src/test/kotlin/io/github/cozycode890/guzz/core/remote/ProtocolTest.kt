package io.github.cozycode890.guzz.core.remote

import io.github.cozycode890.guzz.core.audio.ChunkSettings
import io.github.cozycode890.guzz.core.audio.CleanSettings
import io.github.cozycode890.guzz.core.speaker.SpeakerBounds
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolTest {
    private val json = Protocol.json

    @Test
    fun `yêu cầu đi rồi về giữ nguyên`() {
        val r = JobRequest(
            fileName = "bai.m4a", sourceName = "content://x/1", audioKey = "a1", speakerKey = "s1",
            chunk = ChunkSettings(chunkMinutes = 5.0), clean = CleanSettings.OFF, saveClean = true,
            bounds = SpeakerBounds(2, 4), runOnOriginal = true,
        )
        val back = json.decodeFromString(JobRequest.serializer(), json.encodeToString(JobRequest.serializer(), r))
        assertEquals(r, back)
        assertTrue(back.diarize)
        assertFalse(r.copy(speakerKey = null).diarize)
    }

    @Test
    fun `trạng thái đi rồi về, enum viết chữ thường`() {
        val s = JobStatus(
            id = "j1", state = JobState.DONE, fraction = 1.0,
            result = JobResult(listOf(ResultFile("doan_001.flac", 10, "ab"))),
        )
        val text = json.encodeToString(JobStatus.serializer(), s)
        assertTrue("\"state\":\"done\"" in text, text)
        assertEquals(s, json.decodeFromString(JobStatus.serializer(), text))
    }

    @Test
    fun `trường lạ bị bỏ qua`() {
        val s = json.decodeFromString(JobStatus.serializer(), """{"state":"running","fraction":0.5,"moi":1}""")
        assertEquals(JobState.RUNNING, s.state)
        assertEquals(Protocol.VERSION, s.protocol)
    }

    /** Mẫu JSON trong docs/server.md phải đọc được bằng chính các class này (B3). */
    @Test
    fun `mẫu JSON trong docs-server đọc được`() {
        val doc = File(System.getProperty("guzz.testdata"), "../docs/server.md").readText(Charsets.UTF_8)
        val blocks = Regex("```json\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(doc).map { it.groupValues[1] }.toList()
        val req = blocks.single { "\"audioKey\"" in it }
        val status = blocks.single { "\"state\"" in it }
        assertEquals(Protocol.VERSION, json.decodeFromString(JobRequest.serializer(), req).protocol)
        assertEquals(JobState.DONE, json.decodeFromString(JobStatus.serializer(), status).state)
    }
}

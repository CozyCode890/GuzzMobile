package io.github.cozycode890.guzz.core.speaker

import io.github.cozycode890.guzz.core.common.audio.TimeMap
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Port của tests/test_speaker.py (11 ca), giữ tên ca của desktop. */
class SpeakerTest {
    private fun L(a: Double, b: Double, s: String) = SpeakerTurn(a, b, s)
    private fun L(a: Int, b: Int, s: String) = SpeakerTurn(a.toDouble(), b.toDouble(), s)

    @Nested
    inner class TestTimeMap {
        // Giữ [10, 20] và [30, 50] của tệp gốc 60 giây → âm thanh đã cắt dài 30 giây.
        private val tmap = TimeMap(listOf(10.0 to 20.0, 30.0 to 50.0), 60.0)

        @Test
        fun test_to_original() {
            assertEquals(30.0, tmap.cleanDuration)
            assertEquals(10.0, tmap.toOriginal(0.0))
            assertEquals(19.5, tmap.toOriginal(9.5))
            assertEquals(30.0, tmap.toOriginal(10.0))
            assertEquals(45.0, tmap.toOriginal(25.0))
            assertEquals(50.0, tmap.toOriginal(99.0))
        }

        @Test
        fun test_to_clean_cut_range_maps_to_next_segment_start() {
            assertEquals(0.0, tmap.toClean(5.0))
            assertEquals(5.0, tmap.toClean(15.0))
            assertEquals(10.0, tmap.toClean(25.0))
            assertEquals(20.0, tmap.toClean(40.0))
            assertEquals(30.0, tmap.toClean(59.0))
        }

        @Test
        fun test_untrimmed() {
            val t = TimeMap.untrimmed(100.0)
            assertEquals(42.0, t.toOriginal(42.0))
            assertEquals(42.0, t.toClean(42.0))
        }
    }

    @Nested
    inner class TestSpeakerTurns {
        @Test
        fun test_sandwich_rule_returns_short_piece_to_lecturer() {
            val items = listOf(L(0, 30, "A"), L(30.5, 31.5, "B"), L(32, 60, "A"))
            val changed = Speakers.smooth(items, items.toList(), minSec = 0.0)
            assertEquals(1, changed)
            assertEquals(listOf("A", "A", "A"), items.map { it.speaker })
            assertEquals(1, Speakers.mergeTurns(items, 1.0).size)
        }

        @Test
        fun test_density_rule_keeps_real_questions() {
            var turns = listOf(L(0, 100, "A"), L(100, 104, "B"), L(104, 110, "A"))
            // Người hỏi nói 4 giây trong ±60 giây: trả về giảng viên.
            var items = turns.map { it.copy() }
            Speakers.smooth(items, turns, sandwichMax = 0.0)
            assertEquals("A", items[1].speaker)
            // Hỏi đáp qua lại 20 giây thì giữ nguyên.
            turns = listOf(L(0, 100, "A"), L(100, 110, "B"), L(110, 115, "A"), L(115, 125, "B"))
            items = turns.map { it.copy() }
            Speakers.smooth(items, turns, sandwichMax = 0.0)
            assertEquals(listOf("A", "B", "A", "B"), items.map { it.speaker })
        }

        @Test
        fun test_name_map() {
            val items = listOf(L(0, 5, "S1"), L(5, 60, "S0"), L(60, 65, "S2"))
            assertEquals(
                mapOf("S0" to "Giảng viên", "S1" to "Người hỏi 1", "S2" to "Người hỏi 2"),
                Speakers.buildNameMap(items, SpeakerOptions()),
            )
            assertEquals(
                mapOf("S1" to "Speaker 1", "S0" to "Speaker 2", "S2" to "Speaker 3"),
                Speakers.buildNameMap(items, SpeakerOptions(labelMain = false, genericLabel = "Speaker")),
            )
        }

        @Test
        fun test_split_by_chunk_and_speaker_map() {
            val items = listOf(L(590, 605, "S0"), L(605, 606, "S1"), L(1300, 1310, "S0"))
            val chunks = Speakers.splitTurnsByChunk(items, 600.0, 1200.0)
            assertEquals(listOf(L(0, 5, "S0"), L(5, 6, "S1")), chunks)
            val names = mapOf("S0" to "Giảng viên", "S1" to "Người hỏi 1")
            val text = Speakers.speakerMapText(chunks, names)
            assertTrue("00:00.0 - 00:05.0  Giảng viên\n00:05.0 - 00:06.0  Người hỏi 1\n" in text)
            assertEquals(listOf("Giảng viên", "Người hỏi 1"), Speakers.speakersInChunk(chunks, names))
            assertTrue("no speech" in Speakers.speakerMapText(emptyList(), emptyMap()))
        }
    }

    @Nested
    inner class TestParsePromptTranscript {
        @Test
        fun test_timestamps_bold_names_and_unnamed_lines() {
            val text = "[00:05] Giảng viên: Hôm nay học SVM.\n\n" +
                "**Người hỏi 1:** Thưa thầy, ví dụ: kernel là gì?\n" +
                "Ví dụ: một dòng không có tên\n" +
                "[01:02:03]\nGiảng viên:\n\nKernel là hàm."
            val tmap = TimeMap(listOf(100.0 to 5000.0))
            val result = Speakers.parsePromptParagraphs(text, listOf("Giảng viên", "Người hỏi 1"), 600.0, tmap::toOriginal)
            assertEquals(
                listOf(
                    Triple("Giảng viên", 705.0, "Hôm nay học SVM."),
                    Triple("Người hỏi 1", null, "Thưa thầy, ví dụ: kernel là gì?"),
                    Triple("Người hỏi 1", null, "Ví dụ: một dòng không có tên"),
                    Triple("Giảng viên", 100.0 + 600 + 3723, "Kernel là hàm."),
                ),
                result.map { Triple(it.speaker, it.timestamp, it.content) },
            )
        }

        @Test
        fun test_format_template() {
            val d = Paragraph("abc", "Giảng viên", 3725.4, review = true)
            assertEquals("[01:02:05] Giảng viên: [?] abc", Speakers.formatParagraph(d, "[{time}] {speaker}: {text}", true, true))
            assertEquals("Giảng viên: abc", Speakers.formatParagraph(d, "[{time}] {speaker}: {text}", false, false))
            assertEquals("Giảng viên: abc", Speakers.formatParagraph(d, "{speaker} ({time}): {text}", false, false))
            assertEquals("x", Speakers.formatParagraph(Paragraph("x"), "{speaker}: {text}", true, true))
            // Mẫu của bản cũ ({moc} {nguoi} {noi_dung}) vẫn dùng được.
            assertEquals("[01:02:05] Giảng viên: abc", Speakers.formatParagraph(d, "[{moc}] {nguoi}: {noi_dung}", true, false))
        }
    }

    @Nested
    inner class TestJoinWords {
        @Test
        fun test_assign_smooth_merge() {
            val words = "Hôm nay , chúng ta học ( SVM ) .".split(" ").mapIndexed { i, w ->
                Speakers.Word(w, i * 0.5, i * 0.5 + 0.4, null)
            }
            val spans = Speakers.wordsToSpans(words, offset = 100.0)
            val turns = listOf(L(100.0, 102.2, "S0"), L(102.2, 102.9, "S1"), L(102.9, 200.0, "S0"))
            Speakers.assignSpeakers(spans, turns)
            assertEquals("S1", spans[5].speaker)
            Speakers.smooth(spans, turns, minSec = 0.0)
            assertTrue(spans.all { it.speaker == "S0" })
            Speakers.renameSpeakers(spans, Speakers.buildNameMap(spans, SpeakerOptions(), primary = "S0"))
            val merged = Speakers.mergeSpans(spans)
            assertEquals(1, merged.size)
            assertEquals("Hôm nay, chúng ta học (SVM).", merged[0].content)
            assertEquals("Giảng viên", merged[0].speaker)
            assertEquals(100.0, merged[0].start)
        }

        @Test
        fun test_merge_breaks_on_long_pause() {
            val spans = listOf(Span(0.0, 1.0, "a", "X"), Span(1.2, 2.0, "b", "X"), Span(6.0, 7.0, "c", "X"), Span(7.0, 8.0, "d", "Y"))
            assertEquals(listOf("a b", "c", "d"), Speakers.mergeSpans(spans, 60.0, 3.0).map { it.content })
        }
    }
}

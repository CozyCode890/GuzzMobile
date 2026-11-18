package io.github.cozycode890.guzz.core.gemini

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stage 8 G3: prompt sửa được ở `files/prompts`, trống thì dùng mặc định. */
class PromptSourceTest {
    @TempDir
    lateinit var dir: File

    private val source by lazy { FilePromptSource(dir) }

    @Test
    fun `chưa sửa thì dùng mặc định`() {
        assertNull(source.custom(PromptKind.TRANSCRIBE))
        assertEquals(DefaultPrompt.text, source.read(PromptKind.TRANSCRIBE))
        assertEquals(DefaultPrompt.diarization, source.read(PromptKind.DIARIZATION))
    }

    @Test
    fun `lưu rồi đọc lại, chỉ đổi đúng loại prompt`() {
        source.save(PromptKind.TRANSCRIBE, "  Chép lời đúng từng chữ.\r\nGiữ tiếng địa phương.  \n")
        assertEquals("Chép lời đúng từng chữ.\nGiữ tiếng địa phương.", source.read(PromptKind.TRANSCRIBE))
        assertEquals(DefaultPrompt.diarization, source.read(PromptKind.DIARIZATION))
        assertTrue(File(dir, "prompt.txt").isFile)
    }

    @Test
    fun `trống hoặc trùng mặc định thì về mặc định, xoá tệp`() {
        source.save(PromptKind.DIARIZATION, "Người nói: {speakers}")
        source.save(PromptKind.DIARIZATION, "   ")
        assertFalse(File(dir, "prompt_diarization.txt").exists())
        source.save(PromptKind.TRANSCRIBE, "khác")
        source.save(PromptKind.TRANSCRIBE, DefaultPrompt.text + "\n")
        assertNull(source.custom(PromptKind.TRANSCRIBE))
    }

    @Test
    fun `tệp sửa tay có BOM vẫn đọc như desktop, tệp rỗng coi như chưa sửa`() {
        File(dir, "prompt.txt").writeText("﻿Dòng 1\r\nDòng 2\r\n", Charsets.UTF_8)
        assertEquals("Dòng 1\nDòng 2", source.read(PromptKind.TRANSCRIBE))
        File(dir, "prompt.txt").writeText("", Charsets.UTF_8)
        assertEquals(DefaultPrompt.text, source.read(PromptKind.TRANSCRIBE))
    }

    @Test
    fun `khôi phục mặc định xoá prompt đã sửa`() {
        source.save(PromptKind.TRANSCRIBE, "khác")
        source.reset(PromptKind.TRANSCRIBE)
        assertEquals(DefaultPrompt.text, source.read(PromptKind.TRANSCRIBE))
    }
}

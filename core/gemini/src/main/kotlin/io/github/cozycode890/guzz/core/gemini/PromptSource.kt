package io.github.cozycode890.guzz.core.gemini

import java.io.File

/** Hai prompt người dùng sửa được (Stage 8 G3), tên tệp như desktop. */
enum class PromptKind(val fileName: String) {
    /** Prompt cho model đa năng khi tắt nhận diện người nói (`[GOOGLE_AI] prompt_file`). */
    TRANSCRIBE("prompt.txt"),

    /** Prompt của cách "Tách giọng trên máy + Prompt", có biến `{speakers}` (`[SPEAKERS] prompt_file`). */
    DIARIZATION("prompt_diarization.txt"),
    ;

    /** Prompt mặc định đóng gói trong app. */
    val default: String get() = if (this == TRANSCRIBE) DefaultPrompt.text else DefaultPrompt.diarization
}

/** Nơi pipeline lấy prompt. Đọc lại mỗi tệp, nên sửa prompt có hiệu lực từ tệp sau. */
fun interface PromptSource {
    fun read(kind: PromptKind): String

    companion object {
        /** Luôn dùng prompt mặc định (test, màn hình thử). */
        val DEFAULT = PromptSource { it.default }
    }
}

/**
 * Prompt người dùng sửa, lưu ở [dir] (`files/prompts/<tên>.txt`, mục "Mặc định không hỏi" của Stage 8): có tệp và
 * không rỗng thì dùng, không thì dùng [PromptKind.default]. Đọc như `_read_prompt_file` của desktop (text mode: bỏ BOM,
 * CRLF thành LF, bỏ khoảng trắng hai đầu). Khác desktop: không có `prompt_file` để trỏ tệp khác, tên tệp cố định.
 */
class FilePromptSource(private val dir: File) : PromptSource {
    private fun file(kind: PromptKind) = File(dir, kind.fileName)

    override fun read(kind: PromptKind): String = custom(kind) ?: kind.default

    /** Prompt đã sửa, `null` khi chưa sửa (hoặc tệp rỗng, đọc lỗi). */
    fun custom(kind: PromptKind): String? {
        val f = file(kind)
        if (!f.isFile) return null
        return runCatching { normalize(f.readText(Charsets.UTF_8)) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Lưu [text]; trống hoặc trùng mặc định thì xoá tệp (về mặc định, prompt mặc định của bản sau vẫn áp dụng). */
    fun save(kind: PromptKind, text: String) {
        val t = normalize(text)
        if (t.isEmpty() || t == kind.default) {
            reset(kind)
            return
        }
        dir.mkdirs()
        val tmp = File(dir, kind.fileName + ".tmp")
        tmp.writeText(t + "\n", Charsets.UTF_8)
        if (!tmp.renameTo(file(kind))) {
            file(kind).delete()
            tmp.renameTo(file(kind))
        }
    }

    fun reset(kind: PromptKind) {
        file(kind).delete()
    }

    private companion object {
        fun normalize(s: String) = s.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').pyStrip()
    }
}

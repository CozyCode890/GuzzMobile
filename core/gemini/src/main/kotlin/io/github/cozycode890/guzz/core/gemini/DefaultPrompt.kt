package io.github.cozycode890.guzz.core.gemini

/**
 * Prompt cho model đa năng khi tắt nhận diện người nói: nội dung `prompt.txt` của desktop, đóng gói làm resource
 * (`[GOOGLE_AI] prompt_file` không có trên mobile; sửa prompt để Stage 8). Port của config.py: `Config.read_prompt`
 * (đọc như text mode của Python: bỏ BOM, CRLF thành LF, bỏ khoảng trắng hai đầu).
 *
 * Golden `GeminiConfigGoldenTest` so với `prompt.txt` của desktop, nên desktop đổi prompt thì test đỏ.
 */
object DefaultPrompt {
    val text: String by lazy {
        val stream = DefaultPrompt::class.java.getResourceAsStream("prompt.txt")
            ?: error("Thiếu resource prompt.txt trong :core:gemini")
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
            .removePrefix("﻿")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .pyStrip()
    }
}

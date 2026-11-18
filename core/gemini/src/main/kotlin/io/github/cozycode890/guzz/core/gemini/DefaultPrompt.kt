package io.github.cozycode890.guzz.core.gemini

/**
 * Prompt cho model đa năng khi tắt nhận diện người nói: nội dung `prompt.txt` của desktop, đóng gói làm resource
 * (`[GOOGLE_AI] prompt_file` không có trên mobile; sửa prompt để Stage 8). Port của config.py: `Config.read_prompt`
 * (đọc như text mode của Python: bỏ BOM, CRLF thành LF, bỏ khoảng trắng hai đầu).
 *
 * Golden `GeminiConfigGoldenTest` so với `prompt.txt` của desktop, nên desktop đổi prompt thì test đỏ.
 */
object DefaultPrompt {
    val text: String by lazy { read("prompt.txt") }

    /**
     * Prompt của cách "Tách giọng trên máy + Prompt" (`pyannote`): `prompt_diarization.txt` của desktop, có biến
     * `{speakers}`. Port của config.py: `Config.read_diarization_prompt`.
     */
    val diarization: String by lazy { read("prompt_diarization.txt") }

    /**
     * Thư mục của resource, viết tuyệt đối. Không dùng tên tương đối (`getResourceAsStream("prompt.txt")`): tên tương
     * đối được tìm theo package của class, mà ở bản release R8 đổi tên và dời class sang package khác (vd `h70` ở package
     * gốc) trong khi resource vẫn nằm nguyên `io/github/.../gemini/` trong APK, nên app chết khi mở "Nâng cao" của trang
     * Google AI. Đường dẫn tuyệt đối không phụ thuộc tên class nên không cần keep rule, R8 vẫn tối ưu được class này.
     */
    private const val RESOURCE_DIR = "/io/github/cozycode890/guzz/core/gemini/"

    private fun read(name: String): String {
        val stream = DefaultPrompt::class.java.getResourceAsStream(RESOURCE_DIR + name)
            ?: error("Thiếu resource $RESOURCE_DIR$name trong :core:gemini")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
            .removePrefix("﻿")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .pyStrip()
    }
}

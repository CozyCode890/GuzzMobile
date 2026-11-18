package io.github.cozycode890.guzz.feature.queue

import android.content.Context
import io.github.cozycode890.guzz.core.gemini.FilePromptSource
import java.io.File

/**
 * Prompt người dùng sửa ở trang Google AI / Người nói (Stage 8 G3): `files/prompts/<tên>.txt` trong bộ nhớ riêng của
 * app.
 */
fun promptSource(context: Context) = FilePromptSource(File(context.filesDir, "prompts"))

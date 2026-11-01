package io.github.cozycode890.guzz.spike

import android.content.Context
import java.io.File

/**
 * Thư mục dữ liệu của app spike: `/sdcard/Android/data/<package>/files/{models,input,results}`. Không cần quyền bộ nhớ,
 * và `adb push` / `adb pull` đọc ghi được trực tiếp.
 */
object Paths {
    fun root(ctx: Context): File = requireNotNull(ctx.getExternalFilesDir(null)) { "Không có bộ nhớ ngoài" }

    fun models(ctx: Context): File = File(root(ctx), "models").apply { mkdirs() }

    fun input(ctx: Context): File = File(root(ctx), "input").apply { mkdirs() }

    fun results(ctx: Context): File = File(root(ctx), "results").apply { mkdirs() }

    /** Tên tương đối thì tìm trong [base]; đường dẫn tuyệt đối thì dùng nguyên. */
    fun resolve(base: File, name: String): File = File(name).let { if (it.isAbsolute) it else File(base, name) }
}

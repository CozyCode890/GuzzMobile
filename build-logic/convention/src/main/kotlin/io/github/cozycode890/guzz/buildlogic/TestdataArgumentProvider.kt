package io.github.cozycode890.guzz.buildlogic

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Truyền `-Dguzz.testdata=<gốc repo>/testdata` cho JVM chạy test, đồng thời khai báo thư mục đó là input của task.
 *
 * Khai báo input để đổi dữ liệu golden thì Gradle chạy lại test thay vì báo UP-TO-DATE. Dùng argument provider (không
 * dùng `systemProperty`) để đường dẫn tuyệt đối không lọt vào khoá cache; `@InputFiles` thay cho `@InputDirectory` để
 * module chưa có dữ liệu golden vẫn build được (công cụ đọc golden tự báo lỗi rõ ràng khi thiếu).
 */
abstract class TestdataArgumentProvider : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testdata: DirectoryProperty

    override fun asArguments(): Iterable<String> =
        listOf("-Dguzz.testdata=${testdata.get().asFile.absolutePath}")
}

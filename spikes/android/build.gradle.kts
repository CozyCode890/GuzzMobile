// Chép từ build.gradle.kts gốc: khai báo phiên bản plugin một lần; build-logic chỉ phụ thuộc compileOnly vào chúng.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

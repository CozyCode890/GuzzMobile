plugins {
    alias(libs.plugins.guzz.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Stage 11: giao thức job giữa app và server xử lý âm thanh (Modal), dùng chung cho cả hai bên, và client OkHttp của
// app. JVM thuần để test trên PC (MockWebServer).
dependencies {
    api(project(":core:audio"))
    api(project(":core:speaker"))
    api(libs.kotlinx.serialization.json)
    // api: client nhận OkHttpClient qua constructor (app dùng chung một client với Gemini).
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ProtocolTest đọc mẫu JSON trong docs/server.md: khai báo để sửa tài liệu thì test chạy lại.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("docs/server.md")).withPropertyName("serverDoc")
}

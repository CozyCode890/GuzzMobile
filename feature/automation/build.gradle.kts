plugins {
    alias(libs.plugins.guzz.android.library)
    alias(libs.plugins.guzz.android.compose)
}

// Stage 6: intent cho MacroDroid / Tasker (nhận lệnh, phát sự kiện), Chia sẻ tới Guzz, trang Tự động hoá. Phần không đụng
// Android (đọc extra, kiểm lệnh, đổi đường dẫn, so trạng thái → sự kiện) là lớp Kotlin thuần, test JUnit trên PC.
android {
    namespace = "io.github.cozycode890.guzz.feature.automation"
}

dependencies {
    api(project(":feature:queue"))
    implementation(libs.androidx.core)
}

// IntentsDocTest đọc docs/intents.md: khai báo để sửa tài liệu thì test chạy lại, không lấy kết quả cũ từ cache.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("docs/intents.md")).withPropertyName("intentsDoc")
}

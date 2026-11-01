plugins {
    alias(libs.plugins.guzz.android.application)
    alias(libs.plugins.guzz.android.compose)
}

android {
    // D31: app spike riêng, cài song song với Guzz thật. Chỉ dùng bản debug (hậu tố .debug của convention plugin).
    namespace = "io.github.cozycode890.guzz.spike"

    defaultConfig {
        applicationId = "io.github.cozycode890.guzz.spike"
        versionCode = 1
        versionName = "0.1.0-spike"
    }

    // Thư viện native của AAR: chỉ cần ABI của máy thử, cho APK nhẹ và cài nhanh.
    buildTypes {
        getByName("debug") {
            ndk { abiFilters.retainAll(listOf("arm64-v8a")) }
        }
    }
}

dependencies {
    // AAR sherpa-onnx 2.0.1 tải bằng spikes/diarization/fetch_models.ps1 (gitignore), không có trên Maven.
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation(libs.androidx.activity.compose)
    // Chỉ spike dùng nên khai thẳng toạ độ, không thêm vào libs.versions.toml (Mặc định kỹ thuật của Stage 1).
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.work:work-runtime:2.12.0")
}

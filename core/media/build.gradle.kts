plugins {
    alias(libs.plugins.guzz.android.library)
}

// Phần bắt buộc đụng tới Android của lõi âm thanh: MediaExtractor, MediaCodec (giải mã, mã hoá FLAC), dung lượng trống.
// Thuật toán (resampler, đọc / ghi PCM, chuẩn hoá, cắt lặng, cắt đoạn) nằm ở :core:audio để test trên PC.
android {
    namespace = "io.github.cozycode890.guzz.core.media"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

}

// Tệp thử tổng hợp (tools/golden/gen_media.py) làm assets của test trên máy. AGP 9 bỏ kiểu sourceSets cũ, nên thêm qua
// Variant API.
androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory(rootProject.file("testdata/media").path)
    }
}

dependencies {
    api(project(":core:audio"))
    api(project(":core:common"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}

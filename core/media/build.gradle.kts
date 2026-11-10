plugins {
    alias(libs.plugins.guzz.android.library)
}

// Phần bắt buộc đụng tới Android của lõi âm thanh: MediaExtractor, MediaCodec (giải mã, mã hoá FLAC), dung lượng trống.
// Thuật toán (resampler, đọc / ghi PCM, chuẩn hoá, cắt lặng, cắt đoạn) nằm ở :core:audio để test trên PC.
android {
    namespace = "io.github.cozycode890.guzz.core.media"
}

dependencies {
    api(project(":core:audio"))
    api(project(":core:common"))
}

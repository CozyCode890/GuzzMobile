plugins {
    alias(libs.plugins.guzz.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Port speaker.py của desktop (lượt nói, làm mượt, đặt tên, bản đồ gửi Gemini, gán người nói cho từ), chia phần và nối
// người nói giữa các phần của tách giọng trên máy. JVM thuần để test và golden chạy trên PC; phần sherpa-onnx ở
// :core:diarization.
dependencies {
    api(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":core:common")))
}

plugins {
    alias(libs.plugins.guzz.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Port transcribe.py (một tệp: cache để làm tiếp, chép lời từng đoạn, ghép, đặt tên) và hàng đợi của gui/threads.py.
// JVM thuần: nhận interface (ChunkSource, OutputFolder, ChunkTranscriber) để test trên PC; bản Android ở :feature:queue.
dependencies {
    api(project(":core:common"))
    api(project(":core:audio"))
    api(project(":core:gemini"))
    api(project(":core:speaker"))
    // Stage 11: xử lý âm thanh trên server (giao thức + RemoteProcessor).
    api(project(":core:remote"))

    testImplementation(testFixtures(project(":core:common")))
    testImplementation(testFixtures(project(":core:gemini")))
}

plugins {
    alias(libs.plugins.guzz.android.library)
}

// Phần Android của hàng đợi: foreground service, thông báo, wake lock, SAF, QueueController. Logic hàng đợi và pipeline
// nằm ở :core:pipeline (JVM thuần, test trên PC).
android {
    namespace = "io.github.cozycode890.guzz.feature.queue"
}

dependencies {
    api(project(":core:pipeline"))
    api(project(":core:media"))
    api(project(":core:diarization"))
}

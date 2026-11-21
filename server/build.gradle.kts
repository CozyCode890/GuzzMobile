import io.github.cozycode890.guzz.buildlogic.GuzzDeps

plugins {
    alias(libs.plugins.guzz.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Stage 11 (D143): xử lý âm thanh trên server bằng chính code của điện thoại. Giải mã và mã hoá FLAC bằng ffmpeg, làm
// sạch / cắt đoạn bằng :core:audio, tách giọng bằng :core:speaker + sherpa-onnx 2.0.1 qua Java API. Đóng gói một tệp
// guzz-server.jar (task serverJar) cho vỏ Modal ở server/modal_app.py.
val depsDir = GuzzDeps.cacheDir(providers.gradleProperty(GuzzDeps.DIR_PROPERTY).orNull, gradle.gradleUserHomeDir)
val sherpaJar = files(File(depsDir, "download/sherpa-onnx-jvm-${GuzzDeps.SHERPA_VERSION}.jar"))
val jniLinux = files(File(depsDir, "download/sherpa-onnx-native-lib-linux-x64-${GuzzDeps.SHERPA_VERSION}.jar"))
val jniWindows = files(File(depsDir, "download/sherpa-onnx-native-lib-win-x64-${GuzzDeps.SHERPA_VERSION}.jar"))
val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")

dependencies {
    implementation(project(":core:remote"))
    implementation(project(":core:audio"))
    implementation(project(":core:speaker"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(sherpaJar)

    // Test chạy trên PC: JNI của máy đang build (Windows hoặc Linux x64).
    testRuntimeOnly(if (isWindows) jniWindows else jniLinux)
    testImplementation(testFixtures(project(":core:common")))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Test cần model và tệp thử của plugin guzz.deps, ffmpeg (GUZZ_FFMPEG hoặc ffmpeg của Guzz desktop).
tasks.withType<Test>().configureEach {
    systemProperty("guzz.models", File(depsDir, "models").absolutePath)
    systemProperty("guzz.testwav", File(depsDir, "testwav").absolutePath)
    environment("GUZZ_FFMPEG", System.getenv("GUZZ_FFMPEG") ?: if (isWindows) "D:/Guzz/runtime/ffmpeg/ffmpeg.exe" else "ffmpeg")
}

/** Một tệp .jar gồm code server, mọi thư viện và JNI linux-x64 (container của Modal). */
val serverJar = tasks.register<Jar>("serverJar") {
    archiveFileName.set("guzz-server.jar")
    destinationDirectory.set(layout.buildDirectory.dir("server"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "io.github.cozycode890.guzz.server.MainKt", "Implementation-Version" to libs.versions.guzz.get())
    }
    from(sourceSets.main.map { it.output })
    val runtime = configurations.runtimeClasspath
    dependsOn(runtime)
    from({ (runtime.get().files + jniLinux.files).map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
    }
}

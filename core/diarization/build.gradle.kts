import io.github.cozycode890.guzz.buildlogic.GuzzDeps

plugins {
    alias(libs.plugins.guzz.android.library)
}

// Tách giọng trên máy bằng sherpa-onnx (JNI): một phần âm thanh, process `:diarize`, bản Android của SpeakerDiarizer.
// Thuật toán thuần (nối người nói, luật min–max, speaker.py) ở :core:speaker để test trên PC.
android {
    namespace = "io.github.cozycode890.guzz.core.diarization"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Stage 9 D3: keep rules đi theo module tới bản release của :app.
        consumerProguardFiles("consumer-rules.pro")
    }
}

// Model đóng gói trong assets (D74, D78): segmentation-3.0 và CAM++ zh_en advanced, lấy từ cache của plugin guzz.deps.
val depsDir = GuzzDeps.cacheDir(providers.gradleProperty(GuzzDeps.DIR_PROPERTY).orNull, gradle.gradleUserHomeDir)
val segmentationModel = "segmentation-3.0.onnx"

/** Chép tệp từ cache vào thư mục sinh ra của assets, dưới [prefix]. */
abstract class CopyAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val files: ConfigurableFileCollection

    @get:Input
    abstract val prefix: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val d = outputDir.get().asFile
        d.deleteRecursively()
        val into = File(d, prefix.get()).apply { mkdirs() }
        for (f in files) {
            check(f.isFile) { "Thiếu $f: plugin guzz.deps chưa tải (xem gradle/guzz-deps.txt)" }
            f.copyTo(File(into, f.name))
        }
    }
}

val copyModels = tasks.register<CopyAssets>("copyDiarizationModels") {
    files.from(File(depsDir, "models/$segmentationModel"), File(depsDir, "models/campplus_zh_en_advanced.onnx"))
    prefix.set("sherpa")
    outputDir.set(layout.buildDirectory.dir("generated/diarization-models"))
}
val copyTestWav = tasks.register<CopyAssets>("copyDiarizationTestWav") {
    files.from(File(depsDir, "testwav/0-four-speakers-zh.wav"))
    prefix.set("testwav")
    outputDir.set(layout.buildDirectory.dir("generated/diarization-testwav"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyModels, CopyAssets::outputDir)
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(copyTestWav, CopyAssets::outputDir)
        // Kết quả run_sherpa.py trên PC cho cùng tệp thử (bước D1): so với bản trên máy.
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory(rootProject.file("testdata/golden/diarization").path)
    }
}

dependencies {
    api(project(":core:speaker"))
    api(project(":core:audio"))
    implementation("${GuzzDeps.SHERPA_GROUP}:${GuzzDeps.SHERPA_ARTIFACT}:${GuzzDeps.SHERPA_VERSION}")

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

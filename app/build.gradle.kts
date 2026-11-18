plugins {
    alias(libs.plugins.guzz.android.application)
    alias(libs.plugins.guzz.android.compose)
}

// D130: số phiên bản SemVer khai một chỗ (`guzz` trong libs.versions.toml); `-Pguzz.version=1.0.1` ghi đè để build bản
// thử cài đè (Stage 9 F2). versionCode = MAJOR×10000 + MINOR×100 + PATCH nên luôn tăng theo số.
val guzzVersion: String = providers.gradleProperty("guzz.version").orNull ?: libs.versions.guzz.get()
val guzzVersionCode: Int = run {
    val parts = guzzVersion.split('.').map { it.toIntOrNull() }
    require(parts.size == 3 && parts.all { it != null && it in 0..99 } && parts[0]!! >= 1) {
        "guzz.version = $guzzVersion: cần dạng MAJOR.MINOR.PATCH, MAJOR ≥ 1, mỗi phần 0–99"
    }
    parts[0]!! * 10000 + parts[1]!! * 100 + parts[2]!!
}

// D129, D137: ký bản release bằng khoá trong biến môi trường (đường dẫn, mật khẩu không nằm trong tệp nào của repo).
// Thiếu biến thì ký bằng khoá debug, chỉ để thử trên máy (F); tools/release.ps1 từ chối bản đó.
val releaseKeystore: String? = providers.environmentVariable("GUZZ_KEYSTORE").orNull?.takeIf { it.isNotBlank() }

android {
    // D9
    namespace = "io.github.cozycode890.guzz"

    defaultConfig {
        applicationId = "io.github.cozycode890.guzz"
        versionCode = guzzVersionCode
        versionName = guzzVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("GUZZ_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("GUZZ_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("GUZZ_KEY_PASSWORD").orNull
                    ?: providers.environmentVariable("GUZZ_KEYSTORE_PASSWORD").orNull
                if (releaseKeystore.endsWith(".p12", ignoreCase = true)) storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("release") {
            // D3: R8 bỏ code / tài nguyên không dùng; keep rules cho JNI của sherpa-onnx ở consumer-rules.pro của
            // :core:diarization.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("GUZZ_KEYSTORE chưa đặt: bản release ký bằng khoá debug, CHỈ ĐỂ THỬ, không phát hành.")
                signingConfigs.getByName("debug")
            }
        }
    }

    // D128: nén .so trong APK (tải về nhẹ hơn ~20 MB, đổi lại hệ thống giải nén ra khi cài); bỏ hai thư viện C API của
    // AAR sherpa-onnx mà JNI không dùng (Stage 1, stage-01-results.md §2).
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Stage 8 (E3): thiếu bản tiếng Anh hay chuỗi viết cứng trong layout là lỗi, không chỉ cảnh báo. Chuỗi viết cứng trong
    // Compose do tools/check_strings.ps1 bắt.
    lint {
        error += listOf("MissingTranslation", "HardcodedText")
        // D136: AGP / Gradle giữ ở bản cao nhất mà Kotlin đang dùng đã được kiểm (bảng tương thích của Kotlin), nên cảnh
        // báo "có AGP / Gradle mới" là cố ý. Bật lại khi nâng Kotlin.
        disable += "AndroidGradlePluginVersion"
    }

    // D115: Android 13+ liệt kê Guzz trong Cài đặt → Ngôn ngữ ứng dụng; danh sách ngôn ngữ sinh từ thư mục values-*.
    androidResources {
        generateLocaleConfig = true
    }

    // sideload: APK tự cài, được xin "Truy cập mọi tệp" để nhận đường dẫn tuyệt đối từ MacroDroid.
    // play: không có quyền đó, chỉ nhận content:// URI / SAF (D4, D6).
    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "ALL_FILES_ACCESS", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "false")
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:media"))
    implementation(project(":core:gemini"))
    implementation(project(":feature:queue"))
    implementation(project(":feature:automation"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}

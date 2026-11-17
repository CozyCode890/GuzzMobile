plugins {
    alias(libs.plugins.guzz.android.application)
    alias(libs.plugins.guzz.android.compose)
}

android {
    // D9
    namespace = "io.github.cozycode890.guzz"

    defaultConfig {
        applicationId = "io.github.cozycode890.guzz"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    // Stage 8 (E3): thiếu bản tiếng Anh hay chuỗi viết cứng trong layout là lỗi, không chỉ cảnh báo. Chuỗi viết cứng trong
    // Compose do tools/check_strings.ps1 bắt.
    lint {
        error += listOf("MissingTranslation", "HardcodedText")
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

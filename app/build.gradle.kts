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

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}

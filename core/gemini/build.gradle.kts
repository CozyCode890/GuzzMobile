plugins {
    alias(libs.plugins.guzz.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.serialization.json)
    // api: GeminiClient nhận OkHttpClient qua constructor (app dùng chung một client, bản debug gắn interceptor).
    api(libs.okhttp)

    testImplementation(testFixtures(project(":core:common")))
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)
}

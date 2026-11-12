plugins {
    alias(libs.plugins.guzz.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    // Test fixtures: server Gemini giả (MockWebServer) và client giả, dùng chung cho test của :core:pipeline (Stage 4).
    `java-test-fixtures`
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.serialization.json)
    // api: GeminiClient nhận OkHttpClient qua constructor (app dùng chung một client, bản debug gắn interceptor).
    api(libs.okhttp)

    testFixturesApi(testFixtures(project(":core:common")))
    testFixturesApi(libs.okhttp.mockwebserver3)
    testFixturesApi(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.kotlin.test.junit5)

    testImplementation(testFixtures(project(":core:common")))
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)
}

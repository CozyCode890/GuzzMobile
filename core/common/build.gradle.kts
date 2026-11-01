plugins {
    alias(libs.plugins.guzz.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    // Test fixtures chứa công cụ đọc dữ liệu golden, dùng chung cho test của mọi module.
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.datastore.preferences.core)

    testFixturesApi(libs.kotlinx.serialization.json)
}

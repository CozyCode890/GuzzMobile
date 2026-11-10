plugins {
    alias(libs.plugins.guzz.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":core:common")))
}

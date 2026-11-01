import com.android.build.api.dsl.LibraryExtension
import io.github.cozycode890.guzz.buildlogic.libs
import io.github.cozycode890.guzz.buildlogic.library
import io.github.cozycode890.guzz.buildlogic.version
import io.github.cozycode890.guzz.buildlogic.versionInt
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Module thư viện Android (`guzz.android.library`): chỉ dùng cho phần bắt buộc đụng tới Android (MediaCodec, JNI,
 * service, intent). Kotlin dựng sẵn trong AGP 9 nên không áp plugin `org.jetbrains.kotlin.android`.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")

            val javaVersion = JavaVersion.toVersion(libs.version("jvmTarget"))
            extensions.configure<LibraryExtension> {
                compileSdk = libs.versionInt("compileSdk")
                buildToolsVersion = libs.version("buildTools")
                defaultConfig {
                    minSdk = libs.versionInt("minSdk")
                }
                compileOptions {
                    sourceCompatibility = javaVersion
                    targetCompatibility = javaVersion
                }
            }

            // Test thiết bị / Robolectric vẫn cần JUnit 4 (D17).
            dependencies {
                "testImplementation"(libs.library("junit4"))
            }
        }
    }
}

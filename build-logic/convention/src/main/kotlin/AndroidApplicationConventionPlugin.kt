import com.android.build.api.dsl.ApplicationExtension
import io.github.cozycode890.guzz.buildlogic.libs
import io.github.cozycode890.guzz.buildlogic.library
import io.github.cozycode890.guzz.buildlogic.version
import io.github.cozycode890.guzz.buildlogic.versionInt
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Module app (`guzz.android.application`). */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            val javaVersion = JavaVersion.toVersion(libs.version("jvmTarget"))
            extensions.configure<ApplicationExtension> {
                compileSdk = libs.versionInt("compileSdk")
                buildToolsVersion = libs.version("buildTools")
                defaultConfig {
                    minSdk = libs.versionInt("minSdk")
                    targetSdk = libs.versionInt("targetSdk")
                }
                compileOptions {
                    sourceCompatibility = javaVersion
                    targetCompatibility = javaVersion
                }
                buildTypes {
                    // Debug và release cài song song, dữ liệu riêng (D10).
                    getByName("debug") {
                        applicationIdSuffix = ".debug"
                        // x86_64 để chạy được trên emulator.
                        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
                    }
                    // Điện thoại thật chỉ cần arm64-v8a; bỏ ABI khác cho APK nhẹ (README §6, rủi ro 7).
                    getByName("release") {
                        ndk { abiFilters += listOf("arm64-v8a") }
                    }
                }
            }

            dependencies {
                "testImplementation"(libs.library("junit4"))
            }
        }
    }
}

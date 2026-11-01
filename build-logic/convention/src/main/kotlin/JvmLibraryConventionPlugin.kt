import io.github.cozycode890.guzz.buildlogic.TestdataArgumentProvider
import io.github.cozycode890.guzz.buildlogic.libs
import io.github.cozycode890.guzz.buildlogic.library
import io.github.cozycode890.guzz.buildlogic.version
import io.github.cozycode890.guzz.buildlogic.versionInt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Module JVM thuần (`guzz.jvm.library`): Kotlin JVM, toolchain JDK 21, bytecode 17, JUnit Platform + kotlin.test.
 *
 * Thuật toán port từ Python nằm ở các module này để test chạy ngay trên máy tính (README §3).
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            val jvmTarget = libs.version("jvmTarget")
            extensions.configure<KotlinJvmProjectExtension> {
                jvmToolchain(libs.versionInt("jdk"))
                compilerOptions {
                    this.jvmTarget.set(JvmTarget.fromTarget(jvmTarget))
                }
            }
            // Kotlin kiểm JVM target của Java và Kotlin phải trùng nhau, nên đặt cả phía Java.
            tasks.withType<JavaCompile>().configureEach {
                sourceCompatibility = jvmTarget
                targetCompatibility = jvmTarget
            }

            dependencies {
                "testImplementation"(platform(libs.library("junit-bom")))
                "testImplementation"(libs.library("junit-jupiter"))
                "testImplementation"(libs.library("kotlin-test-junit5"))
                "testRuntimeOnly"(libs.library("junit-platform-launcher"))
            }

            val testdata = isolated.rootProject.projectDirectory.dir("testdata")
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
                jvmArgumentProviders.add(
                    objects.newInstance<TestdataArgumentProvider>().apply { this.testdata.set(testdata) },
                )
            }
        }
    }
}

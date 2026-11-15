import io.github.cozycode890.guzz.buildlogic.GuzzDeps
import io.github.cozycode890.guzz.buildlogic.GuzzDepsSource
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.kotlin.dsl.of
import java.io.File

/**
 * Plugin settings `guzz.deps` (D79): tải sherpa-onnx 2.0.1 (AAR) và model tách giọng từ GitHub Releases vào thư mục
 * cache ngoài repo, kiểm SHA-256 theo `gradle/guzz-deps.txt`, sai thì dừng build. AAR được đặt thành một repo Maven cục
 * bộ (`com.k2fsa.sherpa-onnx:sherpa-onnx:2.0.1`), vì AGP không cho module thư viện phụ thuộc thẳng vào tệp `.aar`.
 *
 * Việc kiểm chạy lúc cấu hình qua một `ValueSource`, nên configuration cache vẫn kiểm lại mỗi lần build: xoá thư mục
 * cache thì lần build sau tải lại. Tệp đã có và khớp thì không tải; mã băm của tệp lớn chỉ tính lại khi cỡ hoặc giờ sửa
 * đổi (tệp `.ok` cạnh tệp).
 */
class GuzzDepsSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val dir = GuzzDeps.cacheDir(
            settings.providers.gradleProperty(GuzzDeps.DIR_PROPERTY).orNull, settings.gradle.gradleUserHomeDir,
        )
        settings.providers.of(GuzzDepsSource::class) {
            parameters.manifest.set(File(settings.rootDir, GuzzDeps.MANIFEST))
            parameters.cacheDir.set(dir)
        }.get()
        settings.dependencyResolutionManagement.repositories.maven {
            name = "guzzDeps"
            url = File(dir, GuzzDeps.MAVEN_DIR).toURI()
            content { includeGroup(GuzzDeps.SHERPA_GROUP) }
        }
    }
}

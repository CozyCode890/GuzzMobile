package io.github.cozycode890.guzz.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import javax.inject.Inject

/** Hằng số và đường dẫn dùng chung giữa plugin settings `guzz.deps` và build script của `:core:diarization`. */
object GuzzDeps {
    const val MANIFEST = "gradle/guzz-deps.txt"
    const val DIR_PROPERTY = "guzz.depsDir"
    const val MAVEN_DIR = "maven"
    const val SHERPA_GROUP = "com.k2fsa.sherpa-onnx"
    const val SHERPA_ARTIFACT = "sherpa-onnx"
    const val SHERPA_VERSION = "2.0.1"

    /** `-Pguzz.depsDir=...`, mặc định `<Gradle user home>/guzz-deps`. */
    fun cacheDir(property: String?, gradleUserHome: File): File =
        property?.takeIf { it.isNotBlank() }?.let(::File) ?: File(gradleUserHome, "guzz-deps")

    /** Một dòng của `gradle/guzz-deps.txt`. [sha256] `-`: không kiểm riêng (lấy từ tệp tar đã kiểm). */
    data class Entry(val name: String, val sha256: String, val path: String, val source: String)

    fun parseManifest(text: String): List<Entry> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map {
            val p = it.split(Regex("\\s+"))
            require(p.size == 4) { "$MANIFEST: dòng sai định dạng: $it" }
            Entry(p[0], p[1].lowercase(), p[2], p[3])
        }
}

/**
 * Tải và kiểm các tệp của `gradle/guzz-deps.txt` vào thư mục cache. Trả về "tên=sha256" từng dòng: configuration cache
 * so giá trị này mỗi lần build, nên tệp bị xoá hay bị sửa thì cấu hình lại và kiểm lại.
 */
abstract class GuzzDepsSource : ValueSource<String, GuzzDepsSource.Params> {
    interface Params : ValueSourceParameters {
        val manifest: RegularFileProperty
        val cacheDir: DirectoryProperty
    }

    @get:Inject
    abstract val exec: ExecOperations

    private val log = Logging.getLogger(GuzzDepsSource::class.java)

    override fun obtain(): String {
        val manifest = parameters.manifest.get().asFile
        val dir = parameters.cacheDir.get().asFile
        val entries = GuzzDeps.parseManifest(manifest.readText(Charsets.UTF_8))
        val byName = entries.associateBy { it.name }
        val out = StringBuilder()
        for (e in entries) {
            val target = File(dir, e.path)
            if (!isVerified(target, e.sha256)) {
                target.parentFile.mkdirs()
                if (e.source.startsWith("tar:")) {
                    val (_, tarName, member) = e.source.split(":", limit = 3)
                    val tar = File(dir, byName.getValue(tarName).path)
                    extract(tar, member, target)
                } else {
                    download(e.name, e.source, target)
                }
                verify(e, target)
            }
            out.append(e.name).append('=').append(e.sha256).append('\n')
        }
        publishAar(dir, File(dir, byName.getValue("aar").path))
        return out.toString()
    }

    /** Có tệp `.ok` ghi đúng cỡ, giờ sửa và mã băm cần, hoặc tính lại mã băm thấy khớp. */
    private fun isVerified(f: File, sha: String): Boolean {
        if (!f.isFile) return false
        if (sha == "-") return true
        val stamp = File(f.path + ".ok")
        val key = "${f.length()} ${f.lastModified()} $sha"
        if (stamp.isFile && stamp.readText() == key) return true
        if (sha256(f) != sha) return false
        stamp.writeText(key)
        return true
    }

    private fun verify(e: GuzzDeps.Entry, f: File) {
        if (e.sha256 == "-") return
        val got = sha256(f)
        if (got != e.sha256) {
            throw GradleException(
                "guzz.deps: SHA-256 không khớp với ${GuzzDeps.MANIFEST}: ${e.name} (${f.name})\n" +
                    "  có:  $got\n  cần: ${e.sha256}\nXoá $f nếu tệp hỏng, hoặc kiểm lại mã băm trong ${GuzzDeps.MANIFEST}.",
            )
        }
        File(f.path + ".ok").writeText("${f.length()} ${f.lastModified()} ${e.sha256}")
    }

    private fun download(name: String, url: String, target: File) {
        log.lifecycle("guzz.deps: tải $name ← $url")
        val part = File(target.path + ".part")
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30)).build()
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(10)).GET().build()
        val resp = try {
            client.send(req, HttpResponse.BodyHandlers.ofFile(part.toPath()))
        } catch (ex: Exception) {
            part.delete()
            throw GradleException("guzz.deps: tải $name lỗi ($url): ${ex.message}", ex)
        }
        if (resp.statusCode() != 200) {
            part.delete()
            throw GradleException("guzz.deps: tải $name lỗi: HTTP ${resp.statusCode()} ($url)")
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Lấy một tệp ra khỏi `.tar.bz2` bằng `tar` của hệ thống (Windows 10+ có sẵn bsdtar). */
    private fun extract(tar: File, member: String, target: File) {
        log.lifecycle("guzz.deps: giải nén ${target.name} ← ${tar.name}")
        val tmp = Files.createTempDirectory(target.parentFile.toPath(), "x").toFile()
        try {
            exec.exec { commandLine("tar", "-xjf", tar.absolutePath, "-C", tmp.absolutePath, member) }
            Files.move(File(tmp, member).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Đặt AAR thành repo Maven cục bộ `com.k2fsa.sherpa-onnx:sherpa-onnx:2.0.1` (aar + pom). */
    private fun publishAar(dir: File, aar: File) {
        val base = "${GuzzDeps.SHERPA_ARTIFACT}-${GuzzDeps.SHERPA_VERSION}"
        val d = File(dir, "${GuzzDeps.MAVEN_DIR}/${GuzzDeps.SHERPA_GROUP.replace('.', '/')}/${GuzzDeps.SHERPA_ARTIFACT}/${GuzzDeps.SHERPA_VERSION}")
        d.mkdirs()
        val dst = File(d, "$base.aar")
        if (!dst.isFile || dst.length() != aar.length()) Files.copy(aar.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val pom = File(d, "$base.pom")
        val text = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>${GuzzDeps.SHERPA_GROUP}</groupId>
              <artifactId>${GuzzDeps.SHERPA_ARTIFACT}</artifactId>
              <version>${GuzzDeps.SHERPA_VERSION}</version>
              <packaging>aar</packaging>
            </project>
        """.trimIndent() + "\n"
        if (!pom.isFile || pom.readText() != text) pom.writeText(text)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

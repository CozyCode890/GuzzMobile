package io.github.cozycode890.guzz.core.common.testing

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Đọc dữ liệu golden trong `testdata/golden/` (do `tools/golden/gen_*.py` tạo bằng chính Python của desktop).
 *
 * Thư mục lấy từ system property `guzz.testdata`, do convention plugin `guzz.jvm.library` truyền cho task `test`.
 * Test của module nào cần golden thì thêm `testImplementation(testFixtures(project(":core:common")))`.
 */
object Golden {
    const val TESTDATA_PROPERTY = "guzz.testdata"

    /** Bỏ qua khoá lạ (`meta`, trường mới thêm ở script) để thêm thông tin vào JSON không làm vỡ test cũ. */
    val json = Json { ignoreUnknownKeys = true }

    val dir: File by lazy {
        val root = System.getProperty(TESTDATA_PROPERTY)
            ?: error(
                "Thiếu system property '$TESTDATA_PROPERTY'. Test phải chạy qua Gradle trong module dùng plugin " +
                    "guzz.jvm.library (plugin này truyền -D$TESTDATA_PROPERTY=<gốc repo>/testdata).",
            )
        File(root, "golden").also {
            check(it.isDirectory) { "Không thấy thư mục golden: $it. Tạo bằng tools/golden/regenerate.ps1." }
        }
    }

    /** Tệp `testdata/golden/<path>`, báo lỗi rõ ràng nếu thiếu. */
    fun file(path: String): File = File(dir, path).also {
        check(it.isFile) { "Không thấy tệp golden: $it. Tạo bằng tools/golden/regenerate.ps1." }
    }

    inline fun <reified T> readJson(path: String): T = json.decodeFromString(file(path).readText(Charsets.UTF_8))

    /** WAV PCM 16-bit mono ra `FloatArray` = int16 / 32768, khớp `_common.read_wav16` bên Python. */
    fun readWav(path: String): Wav = parseWav(file(path).readBytes(), path)

    class Wav(val sampleRate: Int, val samples: FloatArray)

    fun parseWav(bytes: ByteArray, name: String): Wav {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(): String = String(ByteArray(4).also { buf.get(it) }, Charsets.US_ASCII)

        require(bytes.size >= 12 && tag() == "RIFF") { "$name: không phải RIFF" }
        buf.int // cỡ RIFF
        require(tag() == "WAVE") { "$name: không phải WAVE" }

        var sampleRate = -1
        while (buf.remaining() >= 8) {
            val id = tag()
            val size = buf.int
            require(size >= 0 && size <= buf.remaining()) { "$name: chunk '$id' cỡ $size vượt quá tệp" }
            val body = buf.slice(buf.position(), size).order(ByteOrder.LITTLE_ENDIAN)
            when (id) {
                "fmt " -> {
                    val format = body.short.toInt()
                    val channels = body.short.toInt()
                    sampleRate = body.int
                    body.int // byte/giây
                    body.short // block align
                    val bits = body.short.toInt()
                    require(format == 1 && channels == 1 && bits == 16) {
                        "$name: cần PCM 16-bit mono, gặp format=$format, channels=$channels, bits=$bits"
                    }
                }
                "data" -> {
                    require(sampleRate > 0) { "$name: chunk data đứng trước fmt" }
                    val shorts = body.asShortBuffer()
                    val samples = FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
                    return Wav(sampleRate, samples)
                }
            }
            // Chunk có cỡ lẻ thì có một byte đệm.
            buf.position(buf.position() + size + (size and 1))
        }
        error("$name: không có chunk data")
    }
}

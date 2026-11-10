package io.github.cozycode890.guzz.core.audio

import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.common.audio.TimeMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden trên bản ghi thật (D46, bước B9): [ChunkPreparer] trên B1–B5 của Stage 1 so với desktop chạy bằng
 * `tools/golden/real_golden.py`. Dữ liệu nằm NGOÀI repo ở `$GUZZ_SPIKE_DATA` (mặc định không có trên máy khác): thiếu
 * thì tự bỏ qua.
 *
 * Không khớp tuyệt đối được: desktop đo dBFS bằng np.dot float32, hệ số khuếch đại lệch cỡ 5e-6 tương đối, nên vài cửa sổ
 * sát ngưỡng đổi kết quả. Dung sai (plan B9): tổng thời lượng giữ lại lệch ≤ 0,1 s; ≥ 99,9% mốc TimeMap có mốc tương ứng
 * trong ±10 ms; mọi điểm cắt đoạn (đổi về thời gian gốc) lệch ≤ 50 ms.
 */
class RealRecordingGoldenTest {

    @TempDir
    lateinit var tmp: File

    @Serializable
    private class Settings(
        @SerialName("target_dbfs") val targetDbfs: Double,
        @SerialName("min_silence_len") val minSilenceLen: Int,
        @SerialName("silence_thresh_offset") val silenceThreshOffset: Int,
        @SerialName("keep_silence") val keepSilence: Int,
        @SerialName("chunk_minutes") val chunkMinutes: Double,
        @SerialName("search_seconds") val searchSeconds: Double,
        @SerialName("min_last_chunk_seconds") val minLastChunkSeconds: Double,
    )

    @Serializable
    private class RealGolden(
        val wav: String? = null,
        val sr: Int,
        val samples: Long,
        val settings: Settings,
        @SerialName("time_map") val timeMap: List<List<Double>>,
        @SerialName("orig_duration") val origDuration: Double,
        @SerialName("clean_samples") val cleanSamples: Long,
        val points: List<Long>,
        @SerialName("python_seconds") val pythonSeconds: Double,
    )

    private val dataDir: File? = System.getenv("GUZZ_SPIKE_DATA")?.let(::File)?.takeIf { it.isDirectory }

    @TestFactory
    fun `B1–B5 khớp desktop theo dung sai`(): List<DynamicTest> {
        assumeTrue(dataDir != null, "không có GUZZ_SPIKE_DATA: bỏ qua golden trên bản ghi thật")
        val goldenDir = File(dataDir, "golden-stage-02")
        val codes = listOf("B1", "B2", "B3", "B4", "B5").filter { File(goldenDir, "$it.json").isFile }
        assumeTrue(codes.isNotEmpty(), "chưa chạy tools/golden/real_golden.py")
        return codes.map { code -> dynamicTest(code) { check(code, File(goldenDir, "$code.json")) } }
    }

    private fun check(code: String, jsonFile: File) {
        val g = Json { ignoreUnknownKeys = true }.decodeFromString(RealGolden.serializer(), jsonFile.readText())
        val wav = File(dataDir, g.wav ?: "$code/goc16k.wav")
        val (offset, bytes) = wavData(wav)
        val s = g.settings
        val settings = ChunkSettings(
            s.targetDbfs, true, s.minSilenceLen, s.silenceThreshOffset, s.keepSilence,
            s.chunkMinutes, s.searchSeconds, s.minLastChunkSeconds,
        )
        val folder = File(tmp, code)

        val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        System.gc()
        heapPools.forEach { it.resetPeakUsage() }
        val t0 = System.nanoTime()
        val result = Pcm16File(wav, offset, bytes).use { pcm ->
            assertEquals(g.samples, pcm.size, "số mẫu")
            ChunkPreparer(settings, { _, _, _, out -> out.writeText("") }).prepare(pcm, g.sr, wav.path, folder)
        }
        val seconds = (System.nanoTime() - t0) / 1e9
        val peakHeapMb = heapPools.sumOf { it.peakUsage.used } / 1e6

        val tmK = result.toTimeMap()
        val tmP = TimeMap(g.timeMap.map { it[0] to it[1] }, g.origDuration)
        val keptDiff = abs(tmK.cleanDuration - tmP.cleanDuration)
        val edgesP = g.timeMap.flatten()
        val edgesK = result.timeMap.flatten()
        val unmatched = edgesP.count { e -> edgesK.none { abs(it - e) <= 0.010 } } +
            edgesK.count { e -> edgesP.none { abs(it - e) <= 0.010 } }
        val matchedRatio = 1 - unmatched.toDouble() / (edgesP.size + edgesK.size)

        // Điểm cắt đổi về thời gian gốc, để lệch nhỏ của bản đồ không cộng dồn thành lệch lớn trên trục đã cắt.
        val cutsP = g.points.map { tmP.toOriginal(it.toDouble() / g.sr) }
        val cutsK = (listOf(0.0) + result.chunks.map { it.endSeconds }).map { tmK.toOriginal(it) }
        val maxCutDiff = if (cutsP.size == cutsK.size) cutsP.zip(cutsK).maxOf { (a, b) -> abs(a - b) } else Double.NaN

        println(
            "%s: Kotlin %.1f s (Python %.1f s), heap đỉnh %.0f MB; giữ lại lệch %.3f s; mốc TimeMap khớp %.4f%% (%d mốc lệch / %d + %d); %d đoạn, điểm cắt lệch tối đa %.3f s"
                .format(
                    code, seconds, g.pythonSeconds, peakHeapMb, keptDiff, matchedRatio * 100, unmatched,
                    edgesP.size, edgesK.size, cutsK.size - 1, maxCutDiff,
                ),
        )
        assertEquals(g.origDuration, result.originalDuration, "thời lượng gốc")
        assertTrue(keptDiff <= 0.1, "$code: tổng thời lượng giữ lại lệch $keptDiff s")
        assertTrue(matchedRatio >= 0.999, "$code: chỉ ${matchedRatio * 100}% mốc TimeMap khớp trong ±10 ms")
        assertEquals(cutsP.size, cutsK.size, "$code: số đoạn")
        assertTrue(maxCutDiff <= 0.050, "$code: điểm cắt lệch $maxCutDiff s")
        folder.deleteRecursively()
    }

    /** (byte bắt đầu, số byte) của chunk `data` trong WAV PCM 16-bit mono. */
    private fun wavData(file: File): Pair<Long, Long> = RandomAccessFile(file, "r").use { raf ->
        val head = ByteArray(12)
        raf.readFully(head)
        require(String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") { "$file: không phải WAV" }
        var pos = 12L
        while (pos + 8 <= raf.length()) {
            raf.seek(pos)
            val h = ByteArray(8)
            raf.readFully(h)
            val id = String(h, 0, 4)
            val size = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (id == "data") return pos + 8 to size
            pos += 8 + size + (size and 1)
        }
        error("$file: không có chunk data")
    }
}

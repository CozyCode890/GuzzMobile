package io.github.cozycode890.guzz.core.audio

import io.github.cozycode890.guzz.core.audio.pcm.DEFAULT_BLOCK_SAMPLES
import io.github.cozycode890.guzz.core.audio.pcm.GainSource
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16File
import io.github.cozycode890.guzz.core.audio.pcm.Pcm16Writer
import io.github.cozycode890.guzz.core.audio.pcm.SampleSource
import io.github.cozycode890.guzz.core.common.audio.TimeMap
import io.github.cozycode890.guzz.core.common.pyRound
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Cài đặt của bước làm sạch tối giản + cắt đoạn (mục `[AUDIO]`, `[CHUNKING]`). Mặc định = `config.default.txt`. */
data class ChunkSettings(
    val targetDbfs: Double = -16.0,
    val trimSilence: Boolean = true,
    val minSilenceLen: Int = 1000,
    val silenceThreshOffset: Int = 20,
    val keepSilence: Int = 400,
    val chunkMinutes: Double = 10.0,
    val searchSeconds: Double = 20.0,
    val minLastChunkSeconds: Double = 60.0,
)

/** Ghi một đoạn của `sach.pcm` ra tệp gửi Gemini. Trên máy là FLAC (`:core:media`), test dùng WAV. */
fun interface ChunkEncoder {
    /** Ghi mẫu `[range.start, range.end)` của [pcm] ra [out]; ghi `.part` rồi mới đổi tên như mọi tệp khác. */
    fun encode(pcm: Pcm16File, range: SampleRange, sampleRate: Int, out: File)
}

/** Một đoạn trong `danh_sach_doan.json`; mốc tính trên âm thanh ĐÃ làm sạch, giây, làm tròn 3 chữ số như desktop. */
@Serializable
data class ChunkInfo(
    val file: String,
    @SerialName("bat_dau_giay") val startSeconds: Double,
    @SerialName("ket_thuc_giay") val endSeconds: Double,
)

/**
 * Nội dung `danh_sach_doan.json`, cùng khoá với desktop. [digest] (`ma`) là mã của tệp nguồn + tham số âm thanh (Stage
 * 4, `CacheKeys.audioHash`): khác mã thì không dùng lại các đoạn.
 */
@Serializable
data class ChunkList(
    @SerialName("ma") val digest: String = "",
    @SerialName("nguon") val source: String,
    @SerialName("tan_so_lay_mau") val sampleRate: Int,
    @SerialName("thoi_luong_goc") val originalDuration: Double,
    @SerialName("ban_do_thoi_gian") val timeMap: List<List<Double>>,
    @SerialName("doan") val chunks: List<ChunkInfo>,
) {
    fun toTimeMap() = TimeMap(timeMap.map { it[0] to it[1] }, originalDuration)
}

/**
 * Các bước để báo tiến độ (desktop báo "[n/6]"). Bốn bước đầu là làm sạch đầy đủ của Stage 7 ([AudioCleaner]), chỉ có
 * khi bật trong [CleanSettings].
 */
enum class PrepareStep { BANDPASS, TRANSIENTS, DENOISE, LEVEL, NORMALIZE, TRIM, WRITE_CLEAN, WRITE_CHUNKS }

/**
 * Báo tiến độ và điểm dừng: [onProgress] nhận bước và phần đã xong của bước đó (0..1); [checkCancelled] được gọi sau mỗi
 * khối mẫu, ném lỗi (vd `CancellationException`) để dừng giữa chừng.
 */
interface PrepareListener {
    fun onProgress(step: PrepareStep, fraction: Double) {}
    fun checkCancelled() {}

    companion object {
        val NONE = object : PrepareListener {}
    }
}

/**
 * Từ PCM thô của cả bài (`goc.pcm`) ra các đoạn gửi Gemini + `danh_sach_doan.json`.
 * Port của transcribe.py: `prepare_chunks`, audio_processing.py: `clean_audio` và chunker.py: `write_chunks`. Dùng lại
 * đoạn đã cắt (so khoá `ma`) là việc của `:core:pipeline`.
 *
 * Đọc lại nguồn nhiều lượt thay vì giữ tín hiệu trong RAM (D43), xem "Mặc định kỹ thuật" của Stage 2 và Stage 7:
 * 0. (Stage 7, nếu bật) lọc dải tần, ghìm tiếng động, khử ồn, cân bằng âm lượng ([AudioCleaner], ra `tam_NN.f32`);
 * 1. đo dBFS → hệ số khuếch đại;
 * 2–3. đo dBFS sau khuếch đại và tìm khoảng có tiếng ([SilenceTrimmer]);
 * 4. ghép các khoảng giữ lại → `sach.pcm` (int16 như `_to_pcm16` của desktop) và năng lượng khối 50 ms trên giá trị float;
 * 5. tìm điểm cắt, mã hoá từng đoạn từ `sach.pcm`.
 *
 * `danh_sach_doan.json` ghi SAU CÙNG: có tệp này nghĩa là mọi đoạn đã ghi xong. Dừng giữa chừng thì không có nó.
 */
class ChunkPreparer(
    private val settings: ChunkSettings,
    private val encoder: ChunkEncoder,
    private val chunkExtension: String = "flac",
) {
    /**
     * @param raw PCM thô của cả bài, mono, [sampleRate] Hz.
     * @param sourceName đường dẫn / URI của tệp gốc, ghi vào khoá `nguon`.
     * @param folder thư mục tạm của bài; ghi `sach.pcm`, `doan_NNN.<đuôi>`, `danh_sach_doan.json` vào đây.
     * @param digest mã ghi vào khoá `ma`, trong cùng lần ghi cuối (có tệp là có mã đúng).
     * @param clean các bước làm sạch đầy đủ (Stage 7); mặc định tắt hết như Stage 2.
     * @param log nhận các dòng nhật ký của bước làm sạch.
     */
    fun prepare(
        raw: SampleSource,
        sampleRate: Int,
        sourceName: String,
        folder: File,
        listener: PrepareListener = PrepareListener.NONE,
        digest: String = "",
        clean: CleanSettings = CleanSettings.OFF,
        log: (String) -> Unit = {},
        noiseChunk: Pair<Int, Int> = 600_000 to 30_000,
    ): ChunkList {
        // Desktop: "Sau khi lam sach khong con doan nao co tieng." Chỉ xảy ra khi nguồn rỗng, vì cắt lặng không bao giờ bỏ
        // hết (cả bài là lặng thì giữ nguyên).
        if (raw.size == 0L) throw NoSpeechException()
        folder.mkdirs()
        val listFile = File(folder, CHUNK_LIST_FILE)
        listFile.delete()
        return AudioCleaner(clean, sampleRate, folder, listener, log, noiseChunk).use { cleaner ->
            prepareCleaned(cleaner.clean(raw), sampleRate, sourceName, folder, listener, digest, listFile)
        }
    }

    private fun prepareCleaned(
        raw: SampleSource,
        sampleRate: Int,
        sourceName: String,
        folder: File,
        listener: PrepareListener,
        digest: String,
        listFile: File,
    ): ChunkList {

        // (1) Chuẩn hoá âm lượng.
        listener.onProgress(PrepareStep.NORMALIZE, 0.0)
        val gain = Loudness.gainFor(Loudness.measureDbfs(Watched(raw, PrepareStep.NORMALIZE, listener)), settings.targetDbfs)
        val boosted = if (gain == null) raw else GainSource(raw, gain)
        // (2) Cắt lặng: hai lượt đọc (dBFS sau khuếch đại, tìm khoảng có tiếng).
        val origDuration = raw.size.toDouble() / sampleRate
        val trim = if (settings.trimSilence) {
            SilenceTrimmer.trim(
                Watched(boosted, PrepareStep.TRIM, listener, passes = 2),
                sampleRate,
                settings.silenceThreshOffset,
                settings.minSilenceLen,
                settings.keepSilence,
            )
        } else {
            TrimResult(listOf(SampleRange(0, raw.size)), TimeMap.untrimmed(origDuration), trimmed = false)
        }

        // (3) Ghép các khoảng giữ lại ra sach.pcm, đồng thời đo năng lượng khối cho chunker trên giá trị float.
        val cleanFile = File(folder, CLEAN_PCM_FILE)
        val energy = BlockEnergyAccumulator(Chunker.blockSize(sampleRate))
        val writer = Pcm16Writer(cleanFile)
        try {
            val buf = FloatArray(DEFAULT_BLOCK_SAMPLES)
            val shorts = ShortArray(DEFAULT_BLOCK_SAMPLES)
            val total = trim.keptSamples.toDouble()
            var done = 0L
            for (range in trim.kept) {
                var pos = range.start
                while (pos < range.end) {
                    listener.checkCancelled()
                    val n = minOf(buf.size.toLong(), range.end - pos).toInt()
                    boosted.read(pos, buf, 0, n)
                    energy.add(buf, 0, n)
                    for (i in 0 until n) shorts[i] = Pcm16.fromFloatDesktop(buf[i])
                    writer.write(shorts, 0, n)
                    pos += n
                    done += n
                    listener.onProgress(PrepareStep.WRITE_CLEAN, done / total)
                }
            }
            writer.close()
        } catch (e: Throwable) {
            writer.abort()
            throw e
        }

        // (4) Điểm cắt, rồi ghi từng đoạn.
        val n = trim.keptSamples
        val points = Chunker.findSplitPoints(
            energy.result(), n, sampleRate, settings.chunkMinutes, settings.searchSeconds, settings.minLastChunkSeconds,
        )
        val chunks = ArrayList<ChunkInfo>()
        Pcm16File(cleanFile).use { clean ->
            for (i in 0 until points.size - 1) {
                listener.checkCancelled()
                val a = points[i]
                val b = points[i + 1]
                val name = "doan_%03d.%s".format(i + 1, chunkExtension)
                encoder.encode(clean, SampleRange(a, b), sampleRate, File(folder, name))
                chunks += ChunkInfo(name, pyRound(a.toDouble() / sampleRate, 3), pyRound(b.toDouble() / sampleRate, 3))
                listener.onProgress(PrepareStep.WRITE_CHUNKS, (i + 1).toDouble() / (points.size - 1))
            }
        }

        val result = ChunkList(
            digest = digest,
            source = sourceName,
            sampleRate = sampleRate,
            originalDuration = pyRound(trim.timeMap.origDuration, 3),
            timeMap = trim.timeMap.toJson(),
            chunks = chunks,
        )
        writeChunkList(listFile, result)
        return result
    }

    /** Bọc nguồn để báo tiến độ và kiểm dừng sau mỗi khối đọc; [passes] = số lượt đọc hết nguồn trong bước này. */
    private class Watched(
        val inner: SampleSource,
        private val step: PrepareStep,
        private val listener: PrepareListener,
        private val passes: Int = 1,
    ) : SampleSource {
        override val size: Long get() = inner.size
        private var read = 0L

        override fun read(start: Long, dst: FloatArray, offset: Int, count: Int) {
            listener.checkCancelled()
            inner.read(start, dst, offset, count)
            read += count
            listener.onProgress(step, minOf(1.0, read.toDouble() / (size * passes)))
        }
    }

    companion object {
        const val CHUNK_LIST_FILE = "danh_sach_doan.json"
        const val CLEAN_PCM_FILE = "sach.pcm"

        private val json = Json { prettyPrint = true; encodeDefaults = true }
        private val lenientJson = Json { ignoreUnknownKeys = true }

        fun writeChunkList(file: File, list: ChunkList) {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(ChunkList.serializer(), list), Charsets.UTF_8)
            Pcm16Writer.moveReplacing(tmp, file)
        }

        /** Đọc `danh_sach_doan.json`; `null` khi chưa có hoặc hỏng. Kiểm mã và tệp đoạn: `ChunkCache` của `:core:pipeline`. */
        fun readChunkList(folder: File): ChunkList? = runCatching {
            lenientJson.decodeFromString(
                ChunkList.serializer(), File(folder, CHUNK_LIST_FILE).readText(Charsets.UTF_8),
            )
        }.getOrNull()
    }
}

/** Nguồn rỗng: không có gì để gửi. Desktop: "Sau khi lam sach khong con doan nao co tieng." */
class NoSpeechException : RuntimeException("Sau khi làm sạch không còn đoạn nào có tiếng.")

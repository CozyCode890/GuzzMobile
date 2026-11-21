package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.audio.ChunkSettings
import io.github.cozycode890.guzz.core.audio.CleanSettings
import io.github.cozycode890.guzz.core.gemini.DiarizationMethod
import io.github.cozycode890.guzz.core.gemini.GeminiConfig

/**
 * Mã băm cache trong thư mục tạm, để làm tiếp một tệp mà không xử lý âm thanh lại hay gửi lại đoạn đã xong. Cùng cách
 * băm với desktop ([pyHash]) nhưng không cần trùng mã: mobile không dùng chung thư mục tạm với desktop.
 */
object CacheKeys {
    /**
     * Vân tay của tệp nguồn: đổi tệp (cỡ hoặc giờ sửa) thì mã đổi. Port của transcribe.py: `fingerprint`
     * (`đường dẫn|cỡ|int(mtime)`); với content:// URI thì là `uri|cỡ|last_modified giây`, provider không cho thì 0.
     * Desktop chuẩn hoá chữ hoa thường của đường dẫn (`normcase`, Windows); Android phân biệt hoa thường nên giữ nguyên.
     */
    fun fingerprint(id: String, size: Long, modifiedMillis: Long): String =
        "$id|$size|${Math.floorDiv(modifiedMillis, 1000L)}"

    /**
     * Tên thư mục tạm của một tệp: `<tên không đuôi>_<8 ký tự mã của vân tay>`, để hai tệp trùng tên không dùng chung.
     * Port của transcribe.py: `temp_dir_for` (phần tên).
     */
    fun tempDirName(displayName: String, fingerprint: String): String =
        "${pySplitext(displayName).first}_${pyHash(fingerprint).take(8)}"

    /**
     * Mã của các đoạn âm thanh: vân tay + mọi tham số làm đổi nội dung đoạn. Port của transcribe.py: `audio_hash`, đúng
     * thứ tự tham số của desktop (Stage 7 thêm các tham số làm sạch); `transient_clamp_db` chỉ thêm vào khi > 0 như
     * desktop.
     *
     * @param videoSignature cách lấy tiếng từ video (vd luồng đã chọn), `null` với tệp âm thanh: như desktop, mã của tệp
     * âm thanh không đổi khi thêm khái niệm video.
     */
    fun audioHash(
        fingerprint: String,
        sampleRate: Int,
        chunk: ChunkSettings,
        clean: CleanSettings,
        chunkFormat: String,
        videoSignature: List<Any?>? = null,
    ): String {
        val parts = mutableListOf<Any?>(
            fingerprint, clean.denoise, sampleRate, chunk.targetDbfs, chunk.trimSilence, chunk.minSilenceLen,
            chunk.silenceThreshOffset, chunk.keepSilence, clean.noiseSampleSec, clean.bandpassLow, clean.bandpassHigh,
            clean.propDecrease, clean.levelWindowSec, chunk.chunkMinutes, chunk.searchSeconds, chunk.minLastChunkSeconds,
            chunkFormat,
        )
        if (clean.transientClampDb > 0) parts.add(listOf("chan_dot_ngot_db", clean.transientClampDb))
        val digest = pyHash(*parts.toTypedArray())
        return if (videoSignature == null) digest else pyHash(digest, videoSignature)
    }

    /**
     * Khoá chung cho kết quả mọi đoạn của một tệp. Port của transcribe.py: `transcribe_chunks` (`shared_key`). Không có
     * tên model: đoạn đã xong bằng model dự phòng vẫn dùng lại được khi chạy tiếp.
     *
     * @param method cách nhận diện người nói (`null` = tắt).
     * @param prompt prompt của model chính khi tắt nhận diện (`null` với model `*-transcribe`), như `prompt_for(cfg.model)`.
     * @param speakerPrompt prompt của cách `pyannote` (chưa thay `{speakers}`).
     * @param salt thêm vào mã khi khác rỗng (chỉ mobile): Gemini giả của bản debug dùng mã riêng, để kết quả giả không bao
     * giờ bị dùng lại khi gửi thật.
     */
    fun sharedKey(
        audioKey: String,
        prompt: String?,
        cfg: GeminiConfig,
        salt: String = "",
        method: String? = null,
        speakerPrompt: String? = null,
        mapDelivery: String = "file",
        timestamps: Boolean = true,
    ): String {
        val pyannote = method == DiarizationMethod.PYANNOTE
        val key = pyHash(
            audioKey, hashValue(method),
            if (pyannote) speakerPrompt else if (method == null) prompt else null,
            cfg.languages, cfg.vocabulary, cfg.transcribeMode,
            if (pyannote) hashValue(mapDelivery) else null,
            if (pyannote) timestamps else null,
        )
        return if (salt.isEmpty()) key else pyHash(key, salt)
    }

    /**
     * `doan_003.flac` → `doan_003.<mã>.json`: kết quả chép lời của đoạn; mã gồm cả bản đồ người nói của đoạn (cách
     * `pyannote`), nên lượt nói đổi thì gửi lại. Port của transcribe.py: `transcribe_chunks` (`result_file`).
     */
    fun resultFileName(chunkFile: String, sharedKey: String, mapText: String? = null): String =
        "${pySplitext(chunkFile).first}.${pyHash(sharedKey, mapText)}.json"

    /**
     * Mã của kết quả tách giọng `nguoi_noi.<mã>.json`. Port của transcribe.py: `speaker_hash`. [model] là
     * `DiarizationSettings.signature` (model + ngưỡng + chia phần), thay cho tên model pyannote của desktop.
     */
    fun speakerHash(audioKey: String, runOn: String, model: String, num: Int, min: Int, max: Int): String =
        pyHash(audioKey, hashValue(runOn), model, num, min, max)

    /** Port của config.py: `hash_value` (giá trị cũ của bản tiếng Việt, để mã cache không đổi). */
    fun hashValue(value: String?): String? = HASH_VALUES[value] ?: value

    private val HASH_VALUES = mapOf(
        DiarizationMethod.HYBRID to "ket_hop", "text" to "van_ban", "clean" to "da_lam_sach", "original" to "goc",
    )
}

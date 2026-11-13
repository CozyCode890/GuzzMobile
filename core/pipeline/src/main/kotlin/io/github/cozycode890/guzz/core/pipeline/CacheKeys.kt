package io.github.cozycode890.guzz.core.pipeline

import io.github.cozycode890.guzz.core.audio.ChunkSettings
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
     * Mã của các đoạn âm thanh: vân tay + mọi tham số làm đổi nội dung đoạn. Port của transcribe.py: `audio_hash`, với
     * các tham số mobile có (16 kHz, làm sạch tối giản của Stage 2, cắt đoạn, định dạng); Stage 7 thêm tham số DSP.
     *
     * @param videoSignature cách lấy tiếng từ video (vd luồng đã chọn), `null` với tệp âm thanh: như desktop, mã của tệp
     * âm thanh không đổi khi thêm khái niệm video.
     */
    fun audioHash(
        fingerprint: String,
        sampleRate: Int,
        chunk: ChunkSettings,
        chunkFormat: String,
        videoSignature: List<Any?>? = null,
    ): String {
        val digest = pyHash(
            fingerprint, sampleRate, chunk.targetDbfs, chunk.trimSilence, chunk.minSilenceLen,
            chunk.silenceThreshOffset, chunk.keepSilence, chunk.chunkMinutes, chunk.searchSeconds,
            chunk.minLastChunkSeconds, chunkFormat,
        )
        return if (videoSignature == null) digest else pyHash(digest, videoSignature)
    }

    /**
     * Khoá chung cho kết quả mọi đoạn của một tệp khi tắt nhận diện người nói. Port của transcribe.py:
     * `transcribe_chunks` (`shared_key`, nhánh `method is None`). Không có tên model: đoạn đã xong bằng model dự phòng
     * vẫn dùng lại được khi chạy tiếp.
     *
     * @param prompt prompt của model chính (`null` với model `*-transcribe`), như `prompt_for(cfg.model)`.
     * @param salt thêm vào mã khi khác rỗng (chỉ mobile): Gemini giả của bản debug dùng mã riêng, để kết quả giả không bao
     * giờ bị dùng lại khi gửi thật.
     */
    fun sharedKey(audioKey: String, prompt: String?, cfg: GeminiConfig, salt: String = ""): String {
        val key = pyHash(audioKey, null, prompt, cfg.languages, cfg.vocabulary, cfg.transcribeMode, null, null)
        return if (salt.isEmpty()) key else pyHash(key, salt)
    }

    /** `doan_003.flac` → `doan_003.<mã>.json`: kết quả chép lời của đoạn (không có bản đồ người nói: `map_text = None`). */
    fun resultFileName(chunkFile: String, sharedKey: String): String =
        "${pySplitext(chunkFile).first}.${pyHash(sharedKey, null)}.json"
}

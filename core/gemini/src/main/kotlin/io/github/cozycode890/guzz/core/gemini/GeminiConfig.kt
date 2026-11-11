package io.github.cozycode890.guzz.core.gemini

import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings

/** Hạn mức của một model: token đầu vào / phút, yêu cầu / phút, yêu cầu / ngày; 0 = không giới hạn. */
data class Quota(val tpm: Int = 0, val rpm: Int = 0, val rpd: Int = 0)

/** Cách nhận diện người nói. Port của config.py: `METHOD_*` (chuỗi giữ như desktop vì nằm trong cài đặt). */
object DiarizationMethod {
    /** Tách giọng trên máy → gửi âm thanh + bản đồ người nói cho model đa năng. */
    const val PYANNOTE = "pyannote"

    /** Model `*-transcribe` tự tách người nói. */
    const val GEMINI = "gemini"

    /** Model `*-transcribe` cho mốc từng từ, tách giọng trên máy cho lượt nói, ghép trên máy. */
    const val HYBRID = "hybrid"

    val all = listOf(PYANNOTE, GEMINI, HYBRID)
}

/**
 * Các cài đặt `[GOOGLE_AI]`, `[FALLBACK]`, `[QUOTAS]`, `[SPEAKERS]` mà client Gemini dùng. Port phần tương ứng của
 * config.py: `Config`.
 *
 * Mặc định lấy từ [SettingKeys] (tức `config.default.txt` của desktop). [load] đọc từ [Settings].
 */
data class GeminiConfig(
    val model: String = SettingKeys.MODEL.default,
    val transcribeMode: String = SettingKeys.TRANSCRIBE_MODE.default,
    val languages: List<String> = splitList(SettingKeys.LANGUAGES.default),
    val vocabulary: List<String> = splitList(SettingKeys.VOCABULARY.default),
    val storeOnServer: Boolean = SettingKeys.STORE_ON_SERVER.default,
    val timeoutSeconds: Int = SettingKeys.TIMEOUT_SECONDS.default,
    val chunkRetries: Int = SettingKeys.CHUNK_RETRIES.default,
    val deleteRemoteFile: Boolean = SettingKeys.DELETE_REMOTE_FILE.default,
    val tokenRateLimit: Int = SettingKeys.TOKEN_RATE_LIMIT.default,
    val autoSwitchModel: Boolean = SettingKeys.FALLBACK_ENABLED.default,
    val fallbackModels: List<String> = splitList(SettingKeys.FALLBACK_MODELS.default),
    val retriesBeforeSwitch: Int = SettingKeys.RETRIES_BEFORE_SWITCH.default,
    val overloadLockMinutes: Double = SettingKeys.OVERLOAD_LOCK_MINUTES.default,
    val returnToMainModel: Boolean = SettingKeys.RETURN_TO_MAIN_MODEL.default,
    val switchWaitThresholdSec: Int = SettingKeys.SWITCH_WAIT_THRESHOLD_SEC.default,
    val maxAllLockedWaitMin: Double = SettingKeys.MAX_ALL_LOCKED_WAIT_MIN.default,
    val proactiveDailyLock: Boolean = SettingKeys.PROACTIVE_DAILY_LOCK.default,
    val modelQuotas: Map<String, Quota> = parseQuotaLines(SettingKeys.QUOTAS_LINES.default),
    val diarizationEnabled: Boolean = SettingKeys.SPEAKERS_ENABLED.default,
    val diarizationMethod: String = SettingKeys.SPEAKERS_METHOD.default,
) {
    /** Cách nhận diện người nói đang dùng, `null` nếu tắt. */
    fun currentDiarizationMethod(): String? = if (diarizationEnabled) diarizationMethod else null

    /**
     * Thứ tự model sẽ thử: model chính rồi các model dự phòng (nếu bật tự đổi model) hợp với cách nhận diện đang chọn,
     * bỏ trùng. Mọi model trong chuỗi đều nhận cùng một kiểu yêu cầu. Port của config.py: `Config.model_chain`.
     */
    fun modelChain(): List<String> {
        val method = currentDiarizationMethod()
        val chain = mutableListOf(model)
        if (autoSwitchModel) {
            for (m in fallbackModels) {
                if (m.isNotEmpty() && m !in chain && modelMatchesMethod(m, method)) chain += m
            }
        }
        return chain
    }

    /** Hạn mức khai báo trong `[QUOTAS]`; chưa khai báo thì (token_rate_limit, 0, 0). Port của `Config.quota_for`. */
    fun quotaFor(model: String): Quota = modelQuotas[model] ?: Quota(tokenRateLimit, 0, 0)

    companion object {
        /** Đọc cấu hình hiện tại từ [Settings] (mỗi lần gọi đọc lại, nên đổi cài đặt có tác dụng ở đoạn sau). */
        suspend fun load(settings: Settings): GeminiConfig = GeminiConfig(
            model = settings.get(SettingKeys.MODEL).trim().ifEmpty { SettingKeys.MODEL.default },
            transcribeMode = settings.get(SettingKeys.TRANSCRIBE_MODE),
            languages = splitList(settings.get(SettingKeys.LANGUAGES)),
            vocabulary = splitList(settings.get(SettingKeys.VOCABULARY)),
            storeOnServer = settings.get(SettingKeys.STORE_ON_SERVER),
            timeoutSeconds = settings.get(SettingKeys.TIMEOUT_SECONDS),
            chunkRetries = settings.get(SettingKeys.CHUNK_RETRIES),
            deleteRemoteFile = settings.get(SettingKeys.DELETE_REMOTE_FILE),
            tokenRateLimit = settings.get(SettingKeys.TOKEN_RATE_LIMIT),
            autoSwitchModel = settings.get(SettingKeys.FALLBACK_ENABLED),
            fallbackModels = splitList(settings.get(SettingKeys.FALLBACK_MODELS)),
            retriesBeforeSwitch = settings.get(SettingKeys.RETRIES_BEFORE_SWITCH),
            overloadLockMinutes = settings.get(SettingKeys.OVERLOAD_LOCK_MINUTES),
            returnToMainModel = settings.get(SettingKeys.RETURN_TO_MAIN_MODEL),
            switchWaitThresholdSec = settings.get(SettingKeys.SWITCH_WAIT_THRESHOLD_SEC),
            maxAllLockedWaitMin = settings.get(SettingKeys.MAX_ALL_LOCKED_WAIT_MIN),
            proactiveDailyLock = settings.get(SettingKeys.PROACTIVE_DAILY_LOCK),
            modelQuotas = parseQuotaLines(settings.get(SettingKeys.QUOTAS_LINES)),
            diarizationEnabled = settings.get(SettingKeys.SPEAKERS_ENABLED),
            diarizationMethod = settings.get(SettingKeys.SPEAKERS_METHOD),
        )
    }
}

/** `"a, b,, c "` → `[a, b, c]`. Port của config.py: `_split_list`. */
fun splitList(s: String): List<String> = s.split(",").map { it.pyStrip() }.filter { it.isNotEmpty() }

/**
 * `'10000, 0, 25'` → (10000, 0, 25); thiếu số thì 0, số âm thành 0. Port của config.py: `read_quota_spec`.
 *
 * @return `null` khi có phần không phải số (desktop ném `ValueError`).
 */
fun readQuotaSpec(s: String): Quota? {
    val parts = s.split(",").map { it.pyStrip() }.take(3)
    val nums = parts.map { part ->
        if (part.isEmpty()) {
            0
        } else {
            val v = part.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            maxOf(0L, v.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    } + List(3 - parts.size) { 0 }
    return Quota(nums[0], nums[1], nums[2])
}

/**
 * Đọc [SettingKeys.QUOTAS_LINES]: mỗi dòng `model = tpm, rpm, rpd` như section `[QUOTAS]` của config.txt. Như
 * configparser: bỏ dòng trống và dòng chú thích (`#`, `;`), bỏ chú thích cuối dòng, tên model viết thường, dòng trùng
 * tên thì dòng sau thắng.
 *
 * Khác desktop: dòng hỏng (không có `=`, số sai) bị bỏ qua thay vì làm hỏng cả cấu hình, vì đọc cài đặt trên điện thoại
 * không được làm app chết.
 */
fun parseQuotaLines(text: String): Map<String, Quota> {
    val result = LinkedHashMap<String, Quota>()
    for (raw in text.lines()) {
        val line = INLINE_COMMENT.find(raw)?.let { raw.substring(0, it.range.first) } ?: raw
        val trimmed = line.pyStrip()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
        val eq = trimmed.indexOf('=')
        if (eq <= 0) continue
        val name = trimmed.substring(0, eq).pyStrip().lowercase()
        val quota = readQuotaSpec(trimmed.substring(eq + 1)) ?: continue
        if (name.isNotEmpty()) result[name] = quota
    }
    return result
}

private val INLINE_COMMENT = Regex("\\s[#;]")

/**
 * Cách nhận diện dùng được với model. Port của config.py: `valid_diarization_methods`.
 *
 * `*-transcribe`: gemini (Google tự tách giọng) và hybrid (cần mốc từng từ của Google); pyannote + prompt bị khoá vì
 * model này không nhận prompt / tệp kèm. Model đa năng: chỉ pyannote + prompt.
 */
fun validDiarizationMethods(model: String): List<String> =
    if (isTranscribeModel(model)) listOf(DiarizationMethod.GEMINI, DiarizationMethod.HYBRID)
    else listOf(DiarizationMethod.PYANNOTE)

/** [method] `null` = tắt nhận diện người nói: model nào cũng được. Port của config.py: `model_matches_method`. */
fun modelMatchesMethod(model: String, method: String?): Boolean =
    method == null || method in validDiarizationMethods(model)

/**
 * Thông báo nếu model chính không đi được với cách nhận diện người nói đang bật, `null` nếu hợp. Port của config.py:
 * `constraint_error`. Lỗi này lặp lại ở mọi tệp nên dừng cả hàng đợi.
 */
fun constraintError(cfg: GeminiConfig): String? {
    val method = cfg.currentDiarizationMethod()
    if (modelMatchesMethod(cfg.model, method)) return null
    if (method == DiarizationMethod.PYANNOTE) {
        return "Cách nhận diện 'tách giọng trên máy + prompt' cần model đa năng (nhận được prompt), nhưng model đang " +
            "chọn '${cfg.model}' là *-transcribe. Đổi model (vd gemini-3.5-flash-lite) hoặc chọn cách Kết hợp / " +
            "Gemini tự tách."
    }
    return "Cách nhận diện '$method' tách giọng trên Google nên cần model *-transcribe, nhưng model đang chọn " +
        "'${cfg.model}' không phải. Đổi model sang gemini-3.5-transcribe hoặc chọn cách tách giọng trên máy + prompt."
}

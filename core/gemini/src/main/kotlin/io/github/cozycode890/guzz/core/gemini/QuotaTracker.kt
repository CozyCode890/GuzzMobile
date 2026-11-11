package io.github.cozycode890.guzz.core.gemini

import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.log.Logger
import io.github.cozycode890.guzz.core.common.pyRound
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Lý do khoá model. Port của quota.py: `LOCK_*` (chuỗi ghi vào tệp, giữ như desktop). */
object LockReason {
    /** Google báo 429 hết hạn mức trong ngày. */
    const val DAILY_QUOTA = "han_muc_ngay"

    /** 429 vẫn còn sau khi đã thử lại (hạn mức theo phút). */
    const val MINUTE_QUOTA = "han_muc_phut"

    /** App tự đếm đủ số yêu cầu / ngày, khoá trước khi bị 429. */
    const val REACHED_DAILY = "cham_han_muc_ngay"

    /** HTTP 5xx (high demand, unavailable). */
    const val OVERLOADED = "qua_tai"

    /** HTTP 404: key không dùng được model này. */
    const val NOT_FOUND = "khong_ton_tai"
}

/** Loại sự kiện trong lịch sử. Port của quota.py: `EVENT_*`. */
object UsageEventKind {
    const val LOCK = "khoa"
    const val UNLOCK = "mo_khoa"
    const val LOCK_EXPIRED = "het_khoa"
    const val SWITCH_MODEL = "doi_model"
    const val ERROR = "loi"
    const val LEARN_LIMIT = "hoc_han_muc"
    const val WAIT = "cho"
}

/** Số liệu của một model, cùng khoá JSON với `su_dung.json` của desktop (D52). Port của quota.py: `_new_model_entry`. */
@Serializable
class ModelUsage(
    @SerialName("yeu_cau_hom_nay") var requestsToday: Long = 0,
    @SerialName("token_hom_nay") var tokensToday: Long = 0,
    @SerialName("yeu_cau_tong") var requestsTotal: Long = 0,
    @SerialName("token_tong") var tokensTotal: Long = 0,
    @SerialName("lan_dung_cuoi") var lastUsed: Double? = null,
    @SerialName("khoa_den") var lockedUntil: Double? = null,
    @SerialName("khoa_vinh_vien") var lockedPermanently: Boolean = false,
    @SerialName("ly_do_khoa") var lockReason: String = "",
    @SerialName("chi_tiet_khoa") var lockDetails: String = "",
    @SerialName("luc_khoa") var lockedAt: Double? = null,
    @SerialName("loi_cuoi") var lastError: String = "",
    @SerialName("loai_loi_cuoi") var lastErrorKind: String = "",
    @SerialName("luc_loi_cuoi") var lastErrorAt: Double? = null,
    @SerialName("dem_loi") var errorCounts: MutableMap<String, Int> = linkedMapOf(),
    @SerialName("hoc_duoc") var learned: MutableMap<String, Int> = linkedMapOf(),
    @SerialName("cac_lan_khoa_phut") var minuteLocks: MutableList<Double> = mutableListOf(),
) {
    val isLocked: Boolean get() = (lockedUntil != null && lockedUntil != 0.0) || lockedPermanently

    internal fun clearLock() {
        lockedUntil = null
        lockedPermanently = false
        lockReason = ""
        lockDetails = ""
        lockedAt = null
    }
}

/** Một sự kiện trong lịch sử (khoá, mở khoá, đổi model, lỗi…). Trường nào không có thì không ghi, như desktop. */
@Serializable
class UsageEvent(
    @SerialName("luc") val time: Double,
    val model: String,
    @SerialName("loai") val kind: String,
    @SerialName("noi_dung") val content: String,
    @SerialName("loai_loi") val errorKind: String? = null,
    @SerialName("ly_do") val reason: String? = null,
    @SerialName("den") val until: Double? = null,
    @SerialName("tu_model") val fromModel: String? = null,
)

/** Nội dung tệp hạn mức. */
@Serializable
class UsageData(
    @SerialName("ngay") var date: String? = null,
    @SerialName("model") val models: MutableMap<String, ModelUsage> = linkedMapOf(),
    @SerialName("su_kien") var events: MutableList<UsageEvent> = mutableListOf(),
)

/** Đang chờ gì, tới lúc nào (cho trang Hạn mức). [reason]: `tpm`, `rpm`, `thu_lai`, `het_model`. */
data class Waiting(val model: String, val reason: String, val until: Double, val details: String, val since: Double)

data class LockStatus(val locked: Boolean, val until: Double?, val reason: String, val details: String)

/** Bản sao để giao diện vẽ. */
class UsageSnapshot(val data: UsageData, val waiting: Waiting?)

/**
 * Sổ theo dõi lượt dùng và hạn mức của từng model Google AI. Port của quota.py: `QuotaTracker`.
 *
 * - đếm yêu cầu / token trong ngày của từng model (ngày tính theo giờ Thái Bình Dương);
 * - khoá model đã chạm hạn mức, đang quá tải, hay không tồn tại, kèm thời điểm mở lại;
 * - nhớ hạn mức Google báo trong lỗi 429 (`limit: 25`) để lần sau tự khoá trước;
 * - trạng thái chờ hiện tại; lịch sử 300 sự kiện gần nhất.
 *
 * Khác desktop (D52, README §1): chỉ app này dùng tệp nên không có khoá tệp, vân tay tệp hay gộp tệp cũ. Mọi hàm giữ
 * một khoá trong tiến trình; hàm sửa ghi tệp ngay (ra `.tmp` rồi đổi tên).
 *
 * @param file `null` = chỉ giữ trong bộ nhớ (test, hoặc chạy không cần lưu).
 * @param clock epoch tính bằng giây.
 */
class QuotaTracker(
    private val file: File? = null,
    val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val logger: Logger = GuzzLog,
) {
    private val lock = Any()
    private var data: UsageData = load()
    private var waiting: Waiting? = null
    private var depth = 0
    private var dirty = false

    /** Đổi mỗi khi có gì cần vẽ lại. */
    @Volatile
    var version: Long = 0
        private set

    // ------------------------------------------------------------ lưu trữ

    private fun load(): UsageData {
        val loaded = file?.let(::readUsageFile) ?: UsageData()
        if (loaded.date == null) loaded.date = pacificDate(clock())
        return loaded
    }

    private fun save() {
        val f = file ?: return
        writeUsageFile(f, data, logger)
    }

    /** Một lần sửa; lồng nhau được, chỉ lớp ngoài cùng ghi tệp. Port của `_transaction` (bỏ phần khoá tệp). */
    private inline fun <T> transaction(block: () -> T): T = synchronized(lock) {
        depth++
        try {
            block()
        } finally {
            depth--
            if (depth == 0 && dirty) {
                dirty = false
                save()
            }
        }
    }

    private fun changed() {
        version++
        if (depth > 0) dirty = true else save()
    }

    private fun needsRefresh(): Boolean {
        val now = clock()
        if (data.date != pacificDate(now)) return true
        return data.models.values.any { m -> m.lockedUntil.isSet() && !m.lockedPermanently && m.lockedUntil!! <= now }
    }

    /** Đầu mỗi lần đọc: sang ngày mới hay hết khoá thì cập nhật. Port của `_sync`. */
    private fun sync() {
        synchronized(lock) {
            if (needsRefresh()) transaction { refresh() }
        }
    }

    private fun model(name: String): ModelUsage = data.models.getOrPut(name) { ModelUsage() }

    private fun addEvent(
        model: String?,
        kind: String,
        content: String,
        errorKind: String? = null,
        reason: String? = null,
        until: Double? = null,
        fromModel: String? = null,
    ) {
        data.events.add(
            UsageEvent(pyRound(clock(), 1), model.orEmpty(), kind, content, errorKind, reason, until, fromModel),
        )
        if (data.events.size > MAX_EVENTS) data.events = data.events.takeLast(MAX_EVENTS).toMutableList()
    }

    /** Sang ngày mới thì đặt lại bộ đếm; khoá hết hạn thì mở. Trả về `true` nếu có thay đổi. Port của `_refresh`. */
    private fun refresh(): Boolean {
        val now = clock()
        var changed = false
        val date = pacificDate(now)
        if (data.date != date) {
            data.date = date
            for (m in data.models.values) {
                m.requestsToday = 0
                m.tokensToday = 0
            }
            changed = true
        }
        for ((name, m) in data.models) {
            if (m.lockedUntil.isSet() && !m.lockedPermanently && m.lockedUntil!! <= now) {
                addEvent(name, UsageEventKind.LOCK_EXPIRED, m.lockReason)
                m.clearLock()
                changed = true
            }
        }
        if (changed) changed()
        return changed
    }

    // ------------------------------------------------------------ ghi

    fun recordRequest(model: String, tokenCount: Long = 0) = transaction {
        refresh()
        val m = model(model)
        val tokens = maxOf(0L, tokenCount)
        m.requestsToday++
        m.requestsTotal++
        m.tokensToday += tokens
        m.tokensTotal += tokens
        m.lastUsed = pyRound(clock(), 1)
        changed()
    }

    fun recordError(model: String, kind: String, message: String) = transaction {
        val m = model(model)
        m.errorCounts[kind] = (m.errorCounts[kind] ?: 0) + 1
        m.lastError = message.pyTake(500)
        m.lastErrorKind = kind
        m.lastErrorAt = pyRound(clock(), 1)
        addEvent(model, UsageEventKind.ERROR, message.pyTake(300), errorKind = kind)
        changed()
    }

    /** Nhớ hạn mức Google báo trong lỗi 429 (vd rpd = 25). [field]: `tpm`, `rpm`, `rpd`. */
    fun learnQuota(model: String, field: String, value: Int) {
        if (field !in QUOTA_FIELDS || value <= 0) return
        transaction {
            val m = model(model)
            if (m.learned[field] == value) return@transaction
            m.learned[field] = value
            addEvent(model, UsageEventKind.LEARN_LIMIT, "$field = $value")
            logger.info("   Google báo hạn mức của $model: $field = $value. Đã ghi nhớ.")
            changed()
        }
    }

    fun forgetQuota(model: String? = null) = transaction {
        for ((name, m) in data.models) {
            if (model == null || name == model) m.learned = linkedMapOf()
        }
        changed()
    }

    /** [until] `null` = khoá cho tới khi bấm Mở khoá (vd model không tồn tại). */
    fun lock(model: String, until: Double?, reason: String, details: String = "") = transaction {
        val now = clock()
        val m = model(model)
        if (until != null && m.lockedUntil.isSet() && !m.lockedPermanently && m.lockedUntil!! > until &&
            m.lockReason in DAILY_REASONS
        ) {
            return@transaction // đang khoá theo ngày thì không rút ngắn
        }
        m.lockedUntil = until?.let { pyRound(it, 1) }
        m.lockedPermanently = until == null
        m.lockReason = reason
        m.lockDetails = details.pyTake(500)
        m.lockedAt = pyRound(now, 1)
        if (reason == LockReason.MINUTE_QUOTA) {
            m.minuteLocks = (m.minuteLocks.filter { now - it < 3600 } + pyRound(now, 1)).toMutableList()
        }
        val untilText = if (until != null) formatLocalTime(until, now) else "khi mở tay"
        addEvent(model, UsageEventKind.LOCK, details.pyTake(300), reason = reason, until = m.lockedUntil)
        logger.warning("   Khoá model $model tới $untilText ($reason).")
        changed()
    }

    fun unlock(model: String) = transaction {
        val m = model(model)
        if (!m.lockedUntil.isSet() && !m.lockedPermanently) return@transaction
        m.clearLock()
        m.minuteLocks = mutableListOf()
        addEvent(model, UsageEventKind.UNLOCK, "")
        logger.info("Đã mở khoá model $model.")
        changed()
    }

    fun recordModelSwitch(fromModel: String, toModel: String, reason: String) = transaction {
        addEvent(toModel, UsageEventKind.SWITCH_MODEL, reason, fromModel = fromModel)
        changed()
    }

    fun setWaiting(model: String?, reason: String, until: Double, details: String = "") = synchronized(lock) {
        waiting = Waiting(model.orEmpty(), reason, until, details, clock())
        version++
    }

    fun clearWaiting() = synchronized(lock) {
        if (waiting != null) {
            waiting = null
            version++
        }
    }

    fun resetCounters(model: String? = null) = transaction {
        for ((name, m) in data.models) {
            if (model == null || name == model) {
                m.requestsToday = 0
                m.tokensToday = 0
            }
        }
        changed()
    }

    fun clearHistory() = transaction {
        data.events = mutableListOf()
        changed()
    }

    // ------------------------------------------------------------ đọc

    fun lockStatus(model: String): LockStatus {
        sync()
        synchronized(lock) {
            val m = data.models[model]
            if (m == null || !m.isLocked) return LockStatus(false, null, "", "")
            return LockStatus(true, m.lockedUntil, m.lockReason, m.lockDetails)
        }
    }

    fun isLocked(model: String): Boolean = lockStatus(model).locked

    fun requestsToday(model: String): Long {
        sync()
        synchronized(lock) { return data.models[model]?.requestsToday ?: 0 }
    }

    fun recentMinuteLocks(model: String, withinSeconds: Double = 900.0): Int {
        sync()
        synchronized(lock) {
            val now = clock()
            return data.models[model]?.minuteLocks?.count { now - it < withinSeconds } ?: 0
        }
    }

    fun learnedLimits(model: String): Map<String, Int> {
        sync()
        synchronized(lock) { return data.models[model]?.learned?.toMap() ?: emptyMap() }
    }

    /**
     * Hạn mức dùng để giãn nhịp / khoá trước: số khai báo trong `[QUOTAS]` (> 0), không thì số Google đã báo, không
     * nữa thì model chưa khai báo dùng `token_rate_limit` cho token / phút. Port của `QuotaTracker.limit`.
     */
    fun limit(cfg: GeminiConfig, model: String): Quota {
        val declared = cfg.modelQuotas[model]
        val learned = learnedLimits(model)
        val values = QUOTA_FIELDS.mapIndexed { i, field ->
            var num = declared?.let { listOf(it.tpm, it.rpm, it.rpd)[i] } ?: 0
            if (num <= 0) num = learned[field] ?: 0
            if (num <= 0 && declared == null && field == "tpm") num = cfg.tokenRateLimit
            maxOf(0, num)
        }
        return Quota(values[0], values[1], values[2])
    }

    fun models(): List<String> {
        sync()
        synchronized(lock) { return data.models.keys.toList() }
    }

    fun snapshot(): UsageSnapshot {
        sync()
        synchronized(lock) {
            val copy = FILE_JSON.decodeFromString(UsageData.serializer(), FILE_JSON.encodeToString(UsageData.serializer(), data))
            return UsageSnapshot(copy, waiting)
        }
    }

    /** Đổi khi có gì cần vẽ lại (kể cả khi khoá tự hết hạn). */
    fun revision(): Pair<Long, List<String>> {
        sync()
        synchronized(lock) {
            return version to data.models.filterValues { it.isLocked }.keys.sorted()
        }
    }

    companion object {
        const val MAX_EVENTS = 300
        const val FILENAME = "su_dung.json"
        val QUOTA_FIELDS = listOf("tpm", "rpm", "rpd")
        private val DAILY_REASONS = setOf(LockReason.DAILY_QUOTA, LockReason.REACHED_DAILY)

        /** Như `json.dump(..., indent=1, ensure_ascii=False)` của desktop; trường null thì bỏ. */
        @OptIn(ExperimentalSerializationApi::class)
        internal val FILE_JSON = Json {
            prettyPrint = true
            prettyPrintIndent = " "
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

        /** Đọc tệp hạn mức; tệp không có hay hỏng thì trả về sổ trắng. Port của quota.py: `read_usage_file`. */
        fun readUsageFile(file: File): UsageData = try {
            FILE_JSON.decodeFromString(UsageData.serializer(), file.readText(Charsets.UTF_8))
        } catch (_: IOException) {
            UsageData()
        } catch (_: IllegalArgumentException) {
            UsageData()
        }

        /** Ghi ra `.tmp` rồi đổi tên, để không bao giờ có tệp ghi dở. Port của quota.py: `write_usage_file`. */
        fun writeUsageFile(file: File, data: UsageData, logger: Logger = GuzzLog): Boolean = try {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(FILE_JSON.encodeToString(UsageData.serializer(), data), Charsets.UTF_8)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            logger.debug("Không ghi được $file: $e")
            false
        }
    }
}

/** `khoa_den` của desktop được coi là có khi khác None và khác 0 (truthy). */
private fun Double?.isSet(): Boolean = this != null && this != 0.0

/** `s[:n]` của Python: cắt theo code point, không theo đơn vị UTF-16. */
internal fun String.pyTake(n: Int): String {
    if (codePointCount(0, length) <= n) return this
    return substring(0, offsetByCodePoints(0, n))
}

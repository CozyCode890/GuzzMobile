package io.github.cozycode890.guzz.core.gemini

import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.log.Logger
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

/** Kết quả gửi một đoạn: nguyên Interaction và model đã chép lời đoạn đó. */
data class DispatchResult(val response: JsonObject, val model: String)

/**
 * Model chính và cách nhận diện người nói không đi cùng nhau: tệp nào cũng sẽ lỗi, nên dừng cả hàng đợi. Port của
 * config.py: `ConstraintError`.
 */
class ConstraintException(message: String) : Exception(message) {
    val stopQueue: Boolean get() = true
}

/**
 * Gửi từng đoạn cho model đầu tiên còn dùng được trong [GeminiConfig.modelChain] (model chính rồi model dự phòng hợp
 * với cách nhận diện). Port của transcribe.py: `ChunkDispatcher` (và `tracker_for`: sổ theo dõi luôn là của client).
 *
 * - hết hạn mức (429): theo ngày thì khoá model tới lúc Google tính lại, theo phút thì khoá vài phút; rồi đổi model;
 * - quá tải (5xx) sau `retries_before_switch` lần: khoá `overload_lock_minutes` phút, đổi model;
 * - bị bộ lọc của Google chặn (400 Input blocked): thử riêng đoạn đó với model khác, không khoá;
 * - model không tồn tại (404): khoá tới khi mở tay, đổi model.
 *
 * Mọi model trong chuỗi đều khoá thì chờ model mở sớm nhất nếu không lâu hơn `max_all_locked_wait_min`, không thì báo
 * lỗi dừng hàng đợi.
 *
 * Khác desktop: kiểm ràng buộc model ↔ cách nhận diện ([constraintError]) ngay khi tạo, thay vì ở đầu
 * `transcribe_chunks`, để pipeline (Stage 4) không quên; sai thì ném [ConstraintException] trước khi gửi gì.
 */
class ChunkDispatcher(
    private val cfg: GeminiConfig,
    private val client: ChunkTranscriber,
    private val sleep: suspend (Double) -> Unit = coroutineSleep,
    private val logger: Logger = GuzzLog,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    init {
        constraintError(cfg)?.let { throw ConstraintException(it) }
    }

    val tracker: QuotaTracker = client.tracker
    val chain: List<String> = cfg.modelChain()

    var currentModel: String = chain[0]
        private set

    // ---------------------------------------------------------------- chọn model

    private fun order(): List<String> {
        if (cfg.returnToMainModel || currentModel !in chain) return chain
        val i = chain.indexOf(currentModel)
        return chain.subList(i, chain.size) + chain.subList(0, i)
    }

    private fun lockIfDailyQuotaReached(model: String) {
        if (!cfg.proactiveDailyLock || tracker.isLocked(model)) return
        val rpd = tracker.limit(cfg, model).rpd
        val sentToday = tracker.requestsToday(model)
        if (rpd > 0 && sentToday >= rpd) {
            tracker.lock(
                model, nextDailyReset(tracker.clock()), LockReason.REACHED_DAILY,
                "Đã gửi $sentToday/$rpd yêu cầu trong ngày.",
            )
        }
    }

    fun availableModels(skip: Collection<String> = emptySet()): List<String> {
        val result = mutableListOf<String>()
        for (m in order()) {
            if (m in skip) continue
            lockIfDailyQuotaReached(m)
            if (!tracker.isLocked(m)) result += m
        }
        return result
    }

    /**
     * Bật `switch_wait_threshold_sec`: model đầu phải chờ hạn mức phút quá lâu mà model khác không phải chờ thì dùng
     * model khác. Trả về (model, có phải vì chờ hạn mức).
     */
    private fun pickByWaitTime(candidates: List<String>, seconds: Double): Pair<String, Boolean> {
        val model = candidates[0]
        val threshold = cfg.switchWaitThresholdSec
        if (!cfg.autoSwitchModel || threshold <= 0 || candidates.size < 2) return model to false
        val wait = client.quotaWaitTime(cfg, model, seconds) ?: return model to false
        if (wait <= threshold) return model to false
        for (m in candidates.drop(1)) {
            if ((client.quotaWaitTime(cfg, m, seconds) ?: 0.0) <= 0) {
                logger.info("   $model phải chờ ${"%.0f".format(wait)} giây hạn mức theo phút, gửi đoạn này cho $m.")
                return m to true
            }
        }
        return model to false
    }

    // ---------------------------------------------------------------- lỗi

    private fun lockForError(model: String, e: GoogleAIException) {
        val now = tracker.clock()
        when {
            e.kind == ErrorKind.QUOTA -> when {
                e.isDaily -> tracker.lock(model, nextDailyReset(now), LockReason.DAILY_QUOTA, e.message)
                cfg.autoSwitchModel -> if (tracker.recentMinuteLocks(model) >= 2) {
                    logger.warning("   $model bị 429 lặp lại sau mỗi lần khoá, coi như đã hết hạn mức trong ngày.")
                    tracker.lock(model, nextDailyReset(now), LockReason.DAILY_QUOTA, e.message)
                } else {
                    tracker.lock(model, now + maxOf(60.0, e.retryAfter + 2), LockReason.MINUTE_QUOTA, e.message)
                }
            }
            e.kind == ErrorKind.OVERLOAD && cfg.autoSwitchModel && cfg.overloadLockMinutes > 0 ->
                tracker.lock(model, now + cfg.overloadLockMinutes * 60, LockReason.OVERLOADED, e.message)
            e.kind == ErrorKind.MODEL -> tracker.lock(model, null, LockReason.NOT_FOUND, e.message)
        }
    }

    private fun describeLocks(models: List<String>): String = models.mapNotNull { m ->
        val status = tracker.lockStatus(m)
        if (!status.locked) return@mapNotNull null
        val until = status.until?.let { "tới " + formatLocalTime(it, tracker.clock(), zone) } ?: "tới khi mở tay"
        "$m khoá $until (${status.reason})"
    }.joinToString("; ")

    private suspend fun waitForUnlock(skip: Map<String, GoogleAIException>, lastError: GoogleAIException?) {
        val remaining = chain.filter { it !in skip }
        if (remaining.isEmpty()) {
            if (skip.values.all { it.kind == ErrorKind.BLOCKED }) {
                throw GoogleAIException(
                    "Đoạn này bị bộ lọc của Google chặn ở mọi model đã thử (${skip.keys.joinToString(", ")}): " +
                        "${lastError?.message}",
                    ErrorKind.BLOCKED,
                )
            }
            throw GoogleAIException(
                "Đoạn này lỗi ở mọi model đã thử (${skip.entries.joinToString(", ") { "${it.key}: ${it.value.kind.code}" }}): " +
                    "${lastError?.message}",
                lastError?.kind ?: ErrorKind.OTHER,
            )
        }
        val locks = describeLocks(remaining)
        val unlockTimes = remaining.mapNotNull { tracker.lockStatus(it).until }
        if (unlockTimes.isEmpty()) {
            throw GoogleAIException(
                "Không còn model nào dùng được: $locks. Mở khoá ở trang Hạn mức hoặc thêm model dự phòng.",
                lastError?.kind ?: ErrorKind.MODEL, stopQueue = true,
            )
        }
        val until = unlockTimes.min()
        val wait = until - tracker.clock()
        val maxWait = cfg.maxAllLockedWaitMin * 60
        if (wait > maxWait) {
            throw GoogleAIException(
                "Mọi model dùng được đều đang khoá: $locks. Phải chờ ${formatCountdown(wait)}, lâu hơn " +
                    "${formatMinutes(cfg.maxAllLockedWaitMin)} phút cho phép nên dừng hàng đợi. Thêm model dự phòng, " +
                    "đổi cách nhận diện người nói, hoặc mở khoá ở trang Hạn mức.",
                ErrorKind.QUOTA, stopQueue = true,
            )
        }
        logger.warning("Mọi model dùng được đều đang khoá ($locks). Chờ ${formatCountdown(wait)} rồi thử lại.")
        tracker.setWaiting(null, "het_model", until, locks)
        try {
            sleep(maxOf(1.0, wait + 1))
        } finally {
            tracker.clearWaiting()
        }
    }

    // ---------------------------------------------------------------- gửi

    /**
     * Gửi một đoạn. [optionsFor] (model) → (prompt, tham số thêm cho `transcribeRaw`). [onModel] gọi mỗi lần chọn xong
     * model.
     *
     * @param initialSkip model không được dùng cho đoạn này (vd vừa trả về bản chép lời rỗng).
     */
    suspend fun send(
        audio: File,
        seconds: Double,
        optionsFor: (String) -> Pair<String?, TranscribeOptions>,
        onModel: ((String) -> Unit)? = null,
        initialSkip: Map<String, GoogleAIException> = emptyMap(),
    ): DispatchResult {
        // Model bỏ qua RIÊNG đoạn này (bị bộ lọc chặn, hoặc lỗi mà không khoá model): {model: lỗi}.
        val skip = LinkedHashMap(initialSkip)
        var lastError: GoogleAIException? = null
        while (true) {
            coroutineContext.ensureActive()
            val candidates = availableModels(skip.keys)
            if (candidates.isEmpty()) {
                waitForUnlock(skip, lastError)
                continue
            }
            val (model, dueToWait) = pickByWaitTime(candidates, seconds)
            if (model != currentModel) {
                // Mã lý do (trang Hạn mức dịch ra chữ): lý do khoá, bi_chan, qua_tai, cho_han_muc, uu_tien. Đây là giá
                // trị ghi vào tệp hạn mức: giữ nguyên như desktop.
                val reason = when {
                    dueToWait -> "cho_han_muc"
                    currentModel in skip -> when (skip.getValue(currentModel).kind) {
                        ErrorKind.BLOCKED -> "bi_chan"
                        ErrorKind.QUOTA -> LockReason.MINUTE_QUOTA
                        ErrorKind.MODEL -> LockReason.NOT_FOUND
                        else -> LockReason.OVERLOADED
                    }
                    else -> tracker.lockStatus(currentModel).reason.ifEmpty { "uu_tien" }
                }
                logger.warning("Đổi model: $currentModel → $model ($reason).")
                tracker.recordModelSwitch(currentModel, model, reason)
                currentModel = model
            }
            onModel?.invoke(model)
            var (prompt, options) = optionsFor(model)
            options = options.copy(model = model)
            if (cfg.autoSwitchModel && candidates.size > 1) options = options.copy(maxAttempts = cfg.retriesBeforeSwitch)
            val response = try {
                client.transcribeRaw(audio, cfg, prompt, seconds, options)
            } catch (e: GoogleAIException) {
                lockForError(model, e)
                lastError = e
                if (!e.kind.switchesModel) throw e
                if (tracker.isLocked(model)) continue // đã khoá: đổi model, hoặc chờ / báo lỗi rõ ràng ở vòng sau
                if (!cfg.autoSwitchModel) throw e
                // Không khoá (bộ lọc chặn, hoặc tắt khoá khi quá tải): chỉ bỏ model này cho đoạn đang gửi.
                skip[model] = e
                if (e.kind == ErrorKind.BLOCKED) logger.warning("   Bộ lọc của Google chặn đoạn này ở $model, thử model khác.")
                continue
            }
            lockIfDailyQuotaReached(model)
            return DispatchResult(response, model)
        }
    }
}

/** `f"{x:g}"` cho số phút trong thông báo (20.0 → "20", 2.5 → "2.5"). */
private fun formatMinutes(x: Double): String =
    if (x == Math.rint(x) && kotlin.math.abs(x) < 1e15) x.toLong().toString() else x.toString()

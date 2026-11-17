package io.github.cozycode890.guzz.core.gemini

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phần tính toán của trang Hạn mức (Stage 8 D1): từ [UsageSnapshot] ra dữ liệu để vẽ. Port của gui/pages/quota.py
 * (`_paint_now`, `_paint_models_table`, `_paint_events`) và `model_ui.known_models`. Không có chữ hiển thị: lý do, loại
 * sự kiện, loại lỗi là mã như trong tệp (`han_muc_ngay`, `khoa`…), `:app` ghép câu bằng `strings.xml`.
 */
object QuotaView {
    /** Model gợi ý luôn có trong danh sách, như `SUGGESTED_MODELS` của gui/model_ui.py. */
    val SUGGESTED_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-3.8-flash", "gemini-3.5-transcribe")

    /** Loại lỗi theo thứ tự hiện ra, như `_ERROR_KINDS` của trang Hạn mức desktop. */
    val ERROR_KINDS = listOf("qua_tai", "han_muc", "han_muc_ngay", "bi_chan", "model", "cau_hinh", "tam_thoi", "khac")

    /** Đang chờ gì: [reason] `tpm` / `rpm` / `thu_lai` / `het_model`; [untilText] giờ máy kiểu `14:00`. */
    data class Wait(val model: String, val reason: String, val remaining: String, val untilText: String, val details: String)

    /** Một model trong thẻ Các model. [lockedUntilText] / [remaining] `null` khi khoá tới lúc mở tay. */
    data class ModelRow(
        val model: String,
        val locked: Boolean,
        val lockedUntilText: String?,
        val remaining: String?,
        val lockReason: String,
        val lockDetails: String,
        val requestsToday: Long,
        val requestsPerDay: Int,
        val tokensToday: Long,
        val tokensPerMinute: Int,
        val errors: List<Pair<String, Int>>,
        val lastError: String,
        val lastErrorAt: String?,
        val learned: Map<String, Int>,
    ) {
        val overDaily: Boolean get() = requestsPerDay > 0 && requestsToday >= requestsPerDay
    }

    /** Một dòng Lịch sử, mới nhất trước. */
    data class EventRow(
        val time: String,
        val model: String,
        val kind: String,
        val reason: String?,
        val errorKind: String?,
        val fromModel: String?,
        val content: String,
    )

    data class View(
        val wait: Wait?,
        val chain: List<String>,
        val resetAtText: String,
        val resetIn: String,
        val models: List<ModelRow>,
        val events: List<EventRow>,
    )

    private val EVENT_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")
    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")

    /** Model hiện trên trang: gợi ý, model chính, dự phòng, model có hạn mức khai báo, model đã có trong sổ; bỏ trùng. */
    fun knownModels(cfg: GeminiConfig, snapshot: UsageSnapshot): List<String> =
        (SUGGESTED_MODELS + cfg.model + cfg.fallbackModels + cfg.modelQuotas.keys + snapshot.data.models.keys)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * Dựng dữ liệu trang. [limit]: hạn mức dùng cho mỗi model (`QuotaTracker.limit`), [chain]: thứ tự model sẽ thử
     * (`GeminiConfig.modelChain`). Khoá đã hết hạn coi như không khoá, như `lock_status` của desktop.
     */
    fun build(
        snapshot: UsageSnapshot,
        models: List<String>,
        chain: List<String>,
        limit: (String) -> Quota,
        now: Double,
        zone: ZoneId = ZoneId.systemDefault(),
        maxEvents: Int = 200,
    ): View {
        val wait = snapshot.waiting?.let {
            Wait(it.model, it.reason, formatCountdown(it.until - now), formatLocalTime(it.until, now, zone), it.details)
        }
        val reset = nextDailyReset(now)
        val rows = models.map { m ->
            val u = snapshot.data.models[m]
            val timed = u?.lockedUntil?.takeIf { it != 0.0 && it > now }
            val locked = u != null && (u.lockedPermanently || timed != null)
            val q = limit(m)
            ModelRow(
                model = m,
                locked = locked,
                lockedUntilText = if (locked && !u!!.lockedPermanently) formatLocalTime(timed!!, now, zone) else null,
                remaining = if (locked && !u!!.lockedPermanently) formatCountdown(timed!! - now) else null,
                lockReason = if (locked) u!!.lockReason else "",
                lockDetails = if (locked) u!!.lockDetails else "",
                requestsToday = u?.requestsToday ?: 0,
                requestsPerDay = q.rpd,
                tokensToday = u?.tokensToday ?: 0,
                tokensPerMinute = q.tpm,
                errors = ERROR_KINDS.mapNotNull { k -> u?.errorCounts?.get(k)?.takeIf { it > 0 }?.let { k to it } },
                lastError = u?.lastError.orEmpty().replace("\n", " "),
                lastErrorAt = u?.lastErrorAt?.takeIf { u.lastError.isNotEmpty() }
                    ?.let { Instant.ofEpochMilli((it * 1000).toLong()).atZone(zone).format(HOUR_MINUTE) },
                learned = u?.learned?.toMap() ?: emptyMap(),
            )
        }
        val events = snapshot.data.events.asReversed().take(maxEvents).map { e ->
            EventRow(
                time = Instant.ofEpochMilli((e.time * 1000).toLong()).atZone(zone).format(EVENT_TIME),
                model = e.model,
                kind = e.kind,
                reason = e.reason,
                errorKind = e.errorKind,
                fromModel = e.fromModel,
                content = e.content.replace("\n", " "),
            )
        }
        return View(wait, chain, formatLocalTime(reset, now, zone), formatCountdown(reset - now), rows, events)
    }
}

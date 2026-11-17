package io.github.cozycode890.guzz.ui.quota

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import io.github.cozycode890.guzz.AppContainer
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.gemini.GeminiConfig
import io.github.cozycode890.guzz.core.gemini.QuotaView
import io.github.cozycode890.guzz.core.gemini.UsageEventKind
import io.github.cozycode890.guzz.core.gemini.parseQuotaLines
import io.github.cozycode890.guzz.ui.components.AdvancedSection
import io.github.cozycode890.guzz.ui.components.ConfirmDialog
import io.github.cozycode890.guzz.ui.components.Hint
import io.github.cozycode890.guzz.ui.components.SectionCard
import io.github.cozycode890.guzz.ui.components.SettingsPage
import io.github.cozycode890.guzz.ui.components.TextSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Lý do khoá / đổi model → chữ, như `model_ui.lock_reason_text` (`use_reason_*`). */
private fun reasonText(context: android.content.Context, reason: String?): String = when (reason) {
    "han_muc_ngay" -> context.getString(R.string.quota_reason_daily)
    "han_muc_phut" -> context.getString(R.string.quota_reason_minute)
    "cham_han_muc_ngay" -> context.getString(R.string.quota_reason_reached_daily)
    "qua_tai" -> context.getString(R.string.quota_reason_overloaded)
    "khong_ton_tai" -> context.getString(R.string.quota_reason_not_found)
    "bi_chan" -> context.getString(R.string.quota_reason_blocked)
    "cho_han_muc" -> context.getString(R.string.quota_reason_wait_tpm)
    "uu_tien" -> context.getString(R.string.quota_reason_preferred)
    null, "" -> ""
    else -> reason
}

/** Loại lỗi → chữ (`use_err_*`). */
private fun errorText(context: android.content.Context, kind: String?): String = when (kind) {
    "qua_tai" -> context.getString(R.string.quota_err_overloaded)
    "han_muc" -> context.getString(R.string.quota_err_minute)
    "han_muc_ngay" -> context.getString(R.string.quota_err_daily)
    "bi_chan" -> context.getString(R.string.quota_err_blocked)
    "model" -> context.getString(R.string.quota_err_model)
    "cau_hinh" -> context.getString(R.string.quota_err_config)
    "tam_thoi" -> context.getString(R.string.quota_err_network)
    else -> context.getString(R.string.quota_err_other)
}

/** Việc cần hỏi lại trước khi làm. */
private enum class Pending { FORGET, RESET, CLEAR }

/**
 * Tab Hạn mức (D1–D2): Bây giờ (đếm ngược mỗi giây), Các model (thẻ, Mở khoá / Quên hạn mức đã học / Đặt lại bộ đếm);
 * Nâng cao: hạn mức từng model (`[QUOTAS]`), Lịch sử. Sổ hạn mức chỉ của app này (README rủi ro 5).
 */
@Composable
fun QuotaScreen(container: AppContainer) {
    val context = LocalContext.current
    val tracker = container.quotaTracker
    var view by remember { mutableStateOf<QuotaView.View?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Pair<Pending, String?>?>(null) }

    // Vẽ lại mỗi giây (đồng hồ đếm ngược); đọc sổ ở luồng IO vì lần đầu phải đọc tệp.
    LaunchedEffect(tick) {
        while (true) {
            view = withContext(Dispatchers.IO) {
                val cfg = GeminiConfig.load(container.settings)
                val snapshot = tracker.snapshot()
                QuotaView.build(
                    snapshot, QuotaView.knownModels(cfg, snapshot), cfg.modelChain(), { tracker.limit(cfg, it) },
                    System.currentTimeMillis() / 1000.0,
                )
            }
            delay(1_000)
        }
    }

    fun act(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            GuzzLog.warning("Trang Hạn mức: ${e.message}")
        }
        tick++
    }

    SettingsPage(stringResource(R.string.nav_quota), onBack = null) {
        val v = view ?: return@SettingsPage
        SectionCard(stringResource(R.string.quota_now), hint = stringResource(R.string.quota_not_shared)) {
            val w = v.wait
            Text(
                when (w?.reason) {
                    null -> stringResource(R.string.quota_wait_none)
                    "tpm" -> stringResource(R.string.quota_wait_tpm, w.remaining, w.details, w.model)
                    "rpm" -> stringResource(R.string.quota_wait_rpm, w.remaining, w.details, w.model)
                    "thu_lai" -> stringResource(R.string.quota_wait_retry, w.remaining, w.model, errorText(context, w.details))
                    else -> stringResource(R.string.quota_wait_all_locked, w.remaining, w.untilText, w.details)
                },
                color = if (w?.reason == "het_model") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(stringResource(R.string.quota_chain, v.chain.joinToString("  →  ")))
            Hint(stringResource(R.string.quota_reset, v.resetAtText, v.resetIn))
        }

        SectionCard(stringResource(R.string.quota_models)) {
            v.models.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider()
                Column(Modifier.fillMaxWidth().testTag("quota_model:${m.model}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(m.model, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            !m.locked -> stringResource(R.string.quota_status_ok)
                            m.lockedUntilText == null -> stringResource(R.string.quota_status_locked_manual, reasonText(context, m.lockReason))
                            else -> stringResource(R.string.quota_status_locked, m.lockedUntilText.orEmpty(), m.remaining.orEmpty(), reasonText(context, m.lockReason))
                        },
                        color = if (m.locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.quota_usage_today,
                            if (m.requestsPerDay > 0) "${m.requestsToday} / ${m.requestsPerDay}" else m.requestsToday.toString(),
                            "%,d".format(m.tokensToday),
                        ),
                        color = if (m.overDaily) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (m.errors.isNotEmpty()) {
                        Hint(stringResource(R.string.quota_errors, m.errors.joinToString(" · ") { (k, n) -> "${errorText(context, k)} $n" }))
                    }
                    if (m.lastError.isNotEmpty()) Hint("${m.lastErrorAt.orEmpty()} ${m.lastError}".trim(), Modifier)
                    if (m.learned.isNotEmpty()) {
                        Hint(stringResource(R.string.quota_learned, m.learned.entries.joinToString(", ") { "${it.key} ${it.value}" }))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (m.locked) {
                            TextButton(onClick = { act { tracker.unlock(m.model) } }) { Text(stringResource(R.string.quota_unlock)) }
                        }
                        if (m.learned.isNotEmpty()) {
                            TextButton(onClick = { pending = Pending.FORGET to m.model }) { Text(stringResource(R.string.quota_forget)) }
                        }
                        if (m.requestsToday > 0 || m.tokensToday > 0) {
                            TextButton(onClick = { pending = Pending.RESET to m.model }) { Text(stringResource(R.string.quota_reset_counts)) }
                        }
                    }
                }
            }
        }

        AdvancedSection {
            SectionCard(stringResource(R.string.quota_limits), hint = stringResource(R.string.quota_limits_hint)) {
                val invalid = stringResource(R.string.quota_limits_invalid)
                TextSetting(
                    container.settings, SettingKeys.QUOTAS_LINES, stringResource(R.string.quota_limits_label),
                    singleLine = false, allowBlank = true,
                    validate = { t ->
                        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
                        if (lines.size == parseQuotaLines(t).size) null else invalid
                    },
                )
            }
            SectionCard(stringResource(R.string.quota_history)) {
                if (v.events.isEmpty()) Hint(stringResource(R.string.quota_history_empty))
                v.events.forEach { e ->
                    val name = when (e.kind) {
                        UsageEventKind.LOCK -> stringResource(R.string.quota_ev_lock, reasonText(context, e.reason))
                        UsageEventKind.SWITCH_MODEL -> stringResource(R.string.quota_ev_switch, e.fromModel ?: "?", e.model)
                        UsageEventKind.ERROR -> stringResource(R.string.quota_ev_error, errorText(context, e.errorKind))
                        UsageEventKind.UNLOCK -> stringResource(R.string.quota_ev_unlock)
                        UsageEventKind.LOCK_EXPIRED -> stringResource(R.string.quota_ev_lock_expired)
                        UsageEventKind.LEARN_LIMIT -> stringResource(R.string.quota_ev_learn)
                        UsageEventKind.WAIT -> stringResource(R.string.quota_ev_wait)
                        else -> e.kind
                    }
                    val detail = if (e.kind == UsageEventKind.SWITCH_MODEL) reasonText(context, e.content) else e.content
                    Column(Modifier.padding(vertical = 2.dp)) {
                        Text(
                            "${e.time} · ${e.model} · $name",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.kind == UsageEventKind.LOCK || e.kind == UsageEventKind.ERROR) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (detail.isNotBlank()) Hint(detail)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pending = Pending.CLEAR to null }) { Text(stringResource(R.string.quota_clear_history)) }
                    OutlinedButton(onClick = { pending = Pending.RESET to null }) { Text(stringResource(R.string.quota_reset_all)) }
                }
            }
        }
    }

    pending?.let { (what, model) ->
        val title = when (what) {
            Pending.FORGET -> stringResource(R.string.quota_forget)
            Pending.RESET -> stringResource(if (model == null) R.string.quota_reset_all else R.string.quota_reset_counts)
            Pending.CLEAR -> stringResource(R.string.quota_clear_history)
        }
        ConfirmDialog(
            title = title,
            text = stringResource(R.string.quota_confirm, model ?: stringResource(R.string.quota_all_models)),
            confirm = title,
            onConfirm = {
                act {
                    when (what) {
                        Pending.FORGET -> tracker.forgetQuota(model)
                        Pending.RESET -> tracker.resetCounters(model)
                        Pending.CLEAR -> tracker.clearHistory()
                    }
                }
            },
            onDismiss = { pending = null },
        )
    }
}


package io.github.cozycode890.guzz.feature.automation

import io.github.cozycode890.guzz.core.common.log.Logger
import io.github.cozycode890.guzz.core.pipeline.ItemOverrides
import io.github.cozycode890.guzz.core.pipeline.QueueState
import io.github.cozycode890.guzz.feature.automation.GuzzIntents as G
import java.security.MessageDigest

/** Kết quả đổi một nguồn của lệnh sang thứ thêm được vào hàng đợi. */
sealed interface Resolved {
    /** Đọc thẳng bằng đường dẫn (có "Truy cập mọi tệp", hoặc tệp trong thư mục của app). */
    data class Path(val path: String) : Resolved

    /** Đọc qua content:// URI (URI có quyền, hoặc URI tài liệu trong Thư mục nguồn, D104). */
    data class Uri(val uri: String) : Resolved

    /** Đã chép vào thư mục riêng của Guzz (D103); [name] là tên gốc. */
    data class Copy(val path: String, val name: String) : Resolved

    data class Failed(val kind: String, val message: String) : Resolved
}

/** Bắt đầu hàng đợi từ một lệnh. */
enum class StartOutcome { STARTED, NOTHING_TO_DO, BLOCKED }

/** Thứ [CommandHandler] cần từ app; trên máy là `Automation`, test thay bằng bản giả. */
interface CommandEnv {
    suspend fun enabled(): Boolean
    suspend fun secret(): String
    suspend fun includePaths(): Boolean

    /** Đổi đường dẫn / URI của lệnh sang nguồn đọc được (có thể chép tệp, D103). */
    suspend fun resolve(source: String, isPath: Boolean): Resolved

    /** Thêm vào hàng đợi; `false` = đã có trong hàng đợi (trùng nguồn, chưa xong). */
    fun add(resolved: Resolved, requestId: String?, overrides: ItemOverrides?): Boolean

    /** Kiểm trước khi bắt đầu như nút Bắt đầu; `null` = được, khác = lý do (thiếu key, thư mục lưu…). */
    suspend fun checkStart(): String?

    /** Khởi động service; [StartOutcome.BLOCKED] khi Android chặn lên foreground service từ nền (D102). */
    fun start(): StartOutcome

    fun stop()
    fun state(): QueueState
    fun active(): Boolean
}

/**
 * Xử lý một lệnh từ app khác (Stage 6 B2), chung cho receiver và activity trong suốt (D94). Thứ tự kiểm: công tắc cho
 * phép (D93) → mã bí mật → đọc extra → từng tệp → bắt đầu. Trả về các sự kiện trả lời (ERROR, STATUS, FILE_FAILED cho tệp
 * không đọc được) và [Outcome.blocked] để hiện thông báo "Bấm để bắt đầu".
 */
class CommandHandler(private val env: CommandEnv, private val logger: Logger) {
    data class Outcome(val replies: List<AutomationEvent>, val blocked: Boolean = false, val added: Int = 0)

    /**
     * @param origin nguồn lệnh cho log (`broadcast`, `activity`).
     * @param dataUris URI ở data / ClipData của intent.
     */
    suspend fun handle(action: String?, extras: Map<String, Any?>, dataUris: List<String>, origin: String): Outcome {
        val requestId = ExtrasReader.requestId(extras)
        val name = G.shortName(action)
        val tag = "Lệnh $name ($origin${requestId?.let { ", request_id $it" } ?: ""})"
        fun reject(kind: String, message: String): Outcome {
            logger.warning("$tag bị từ chối: $message")
            return Outcome(listOf(EventFactory.error(requestId, action, message, kind)))
        }
        if (!env.enabled()) {
            return reject(G.Kind.DISABLED, "chưa bật \"Cho phép app khác điều khiển Guzz\" trong trang Tự động hoá")
        }
        val expected = env.secret()
        if (expected.isNotEmpty()) {
            val given = try {
                ExtrasReader.secret(extras).orEmpty()
            } catch (e: BadExtraException) {
                ""
            }
            if (!sameSecret(given, expected)) {
                return reject(G.Kind.BAD_SECRET, if (given.isEmpty()) "thiếu mã bí mật (extra `secret`)" else "sai mã bí mật")
            }
        }
        val command = try {
            ExtrasReader.read(action, extras, dataUris)
        } catch (e: BadExtraException) {
            return reject(G.Kind.BAD_EXTRA, e.message ?: "extra không hợp lệ")
        }
        logger.info("$tag: nhận.")
        return when (command) {
            is Command.Transcribe -> transcribe(command, action, tag)
            is Command.Start -> startQueue(command.requestId, action, tag)
            is Command.Stop -> {
                env.stop()
                logger.info("$tag: dừng hàng đợi.")
                Outcome(emptyList())
            }
            is Command.Status -> Outcome(listOf(EventFactory.status(command.requestId, env.state(), env.active())))
        }
    }

    private suspend fun transcribe(c: Command.Transcribe, action: String?, tag: String): Outcome {
        val includePaths = env.includePaths()
        val replies = ArrayList<AutomationEvent>()
        var added = 0
        val sources = c.paths.map { it to true } + c.uris.map { it to false }
        for ((source, isPath) in sources) {
            when (val r = env.resolve(source, isPath)) {
                is Resolved.Failed -> {
                    logger.warning("$tag: không thêm được $source: ${r.message}")
                    replies += EventFactory.sourceFailed(c.requestId, source, r.message, r.kind, includePaths)
                }
                else -> if (env.add(r, c.requestId, c.overrides)) {
                    added++
                    logger.info(
                        "$tag: thêm $source" + (c.overrides?.let { " (ghi đè ${it.describe()})" } ?: "") + ".",
                    )
                } else {
                    logger.info("$tag: $source đã có trong hàng đợi.")
                    replies += EventFactory.error(
                        c.requestId, action, "tệp đã có trong hàng đợi: ${EventFactory.displayName(source)}", G.Kind.DUPLICATE,
                    )
                }
            }
        }
        if (!c.start) return Outcome(replies, added = added)
        val start = startQueue(c.requestId, action, tag)
        return Outcome(replies + start.replies, blocked = start.blocked, added = added)
    }

    private suspend fun startQueue(requestId: String?, action: String?, tag: String): Outcome {
        if (env.active()) return Outcome(emptyList())
        env.checkStart()?.let { problem ->
            if (problem == NOTHING_TO_DO) return Outcome(emptyList())
            logger.warning("$tag: không bắt đầu được: $problem")
            return Outcome(listOf(EventFactory.error(requestId, action, problem, G.Kind.NOT_READY)))
        }
        return when (env.start()) {
            StartOutcome.STARTED, StartOutcome.NOTHING_TO_DO -> Outcome(emptyList())
            StartOutcome.BLOCKED -> {
                val msg = "Android không cho Guzz chạy nền lúc này (chưa miễn tối ưu pin?): tệp vẫn trong hàng đợi, " +
                    "bấm thông báo hoặc gửi lại lệnh bằng CommandActivity"
                logger.warning("$tag: $msg")
                Outcome(listOf(EventFactory.error(requestId, action, msg, G.Kind.BACKGROUND_BLOCKED)), blocked = true)
            }
        }
    }

    companion object {
        /** Lý do "không có tệp nào để chạy" của [CommandEnv.checkStart]: không phải lỗi với lệnh TRANSCRIBE trùng tệp. */
        const val NOTHING_TO_DO = "nothing_to_do"

        /** So mã bí mật trong thời gian không phụ thuộc vị trí khác nhau đầu tiên. */
        fun sameSecret(given: String, expected: String): Boolean =
            MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }
}

package io.github.cozycode890.guzz.feature.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.log.Logger
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.core.pipeline.ItemOverrides
import io.github.cozycode890.guzz.core.pipeline.ItemStatus
import io.github.cozycode890.guzz.core.pipeline.QueueItem
import io.github.cozycode890.guzz.core.pipeline.StartProblem
import io.github.cozycode890.guzz.feature.automation.GuzzIntents as G
import io.github.cozycode890.guzz.feature.queue.QueueController
import io.github.cozycode890.guzz.feature.queue.querySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Application cung cấp [Automation] cho receiver / activity của module này (dựng tay, D13). */
interface AutomationHost {
    val automation: Automation
}

/**
 * Cửa vào của Stage 6: nhận lệnh từ app khác (receiver + activity trong suốt, D94), Chia sẻ tới Guzz (D98), phát sự kiện
 * (C1–C2), quyền tệp / pin cho trang Tự động hoá. Sống cùng `AppContainer`.
 *
 * @param queue hàng đợi của app; chỉ gọi khi cần (dựng `QueueController` đọc tệp hàng đợi).
 * @param allFilesFlavor bản sideload (được xin "Truy cập mọi tệp" và xin thẳng miễn tối ưu pin).
 * @param debug bản debug: nhận extra `fake_gemini`, ghi mọi sự kiện vào `files/debug-events/events.jsonl` (C3).
 */
class Automation(
    private val context: Context,
    private val settings: Settings,
    private val queue: () -> QueueController,
    private val scope: CoroutineScope,
    val allFilesFlavor: Boolean,
    val debug: Boolean,
    private val logger: Logger = GuzzLog,
) {
    val fileAccess = FileAccess(context, settings, allFilesFlavor)
    val battery = BatteryStatus(context, canRequestDirectly = allFilesFlavor)
    private val tracker = EventTracker()
    private val handler = CommandHandler(Env(), logger)

    @Volatile
    private var attached = false

    /** Thư mục chép tệp được chia sẻ mà không giữ được quyền đọc (D103). */
    private val inbox = File(context.getExternalFilesDir(null) ?: context.filesDir, "inbox")

    // ------------------------------------------------------------------ lệnh

    /** Xử lý một lệnh (receiver hoặc activity); phát sự kiện trả lời, hiện thông báo khi bị chặn chạy nền (D102). */
    suspend fun handle(intent: Intent, origin: String): CommandHandler.Outcome {
        val extras = intent.extrasMap().let { if (debug) it else it - G.EXTRA_FAKE_GEMINI }
        val outcome = handler.handle(intent.action, extras, intent.dataUris(), origin)
        outcome.replies.forEach(::emit)
        if (outcome.blocked) notifyBlocked()
        if (outcome.added > 0 && !battery.notificationsEnabled()) {
            logger.warning("Guzz chưa có quyền thông báo: hàng đợi vẫn chạy nhưng không có thông báo tiến độ / nút Dừng.")
        }
        return outcome
    }

    /** Kết quả Chia sẻ tới Guzz: số tệp thêm được, tệp không đọc được, lý do không bắt đầu được (`null` = đã chạy). */
    data class ShareOutcome(val added: Int, val failed: List<String>, val problem: String?)

    /**
     * Chia sẻ tới Guzz (D98): thêm và bắt đầu ngay, không cần công tắc cho phép. Gọi từ Activity đang hiện nên được khởi
     * động service. Không giữ được quyền đọc bền thì chép tệp trước khi Activity đóng (D103).
     */
    suspend fun share(uris: List<Uri>): ShareOutcome {
        val failed = ArrayList<String>()
        var added = 0
        for (uri in uris) {
            when (val r = resolveUri(uri.toString())) {
                is Resolved.Failed -> {
                    logger.warning("Chia sẻ tới Guzz: không đọc được $uri: ${r.message}")
                    failed += EventFactory.displayName(uri.toString())
                }
                else -> if (add(r, null, null)) added++
            }
        }
        logger.info("Chia sẻ tới Guzz: ${uris.size} tệp, thêm $added.")
        val c = queue()
        if (c.active.value) return ShareOutcome(added, failed, null)
        val problem = checkStartProblem()
        if (problem == null) c.start()
        return ShareOutcome(added, failed, problem?.takeUnless { it == CommandHandler.NOTHING_TO_DO })
    }

    // ------------------------------------------------------------------ sự kiện

    /**
     * Gắn bộ phát sự kiện vào hàng đợi (gọi một lần, khi `QueueController` được dựng trong process chính). Trạng thái lúc
     * gắn là mốc, không phát lại sự kiện cũ.
     */
    fun attach(controller: QueueController) {
        if (attached) return
        attached = true
        tracker.update(controller.state.value, includePaths = true)
        scope.launch {
            controller.state.collect { s ->
                val enabled = settings.get(SettingKeys.AUTOMATION_ENABLED)
                val events = tracker.update(s, settings.get(SettingKeys.AUTOMATION_INCLUDE_PATHS))
                if (enabled) events.forEach(::emit)
            }
        }
        if (debug) registerDebugLog()
    }

    /** Phát sự kiện bằng broadcast ngầm: receiver đăng ký lúc chạy (MacroDroid, Tasker) nhận được. */
    fun emit(event: AutomationEvent) {
        val intent = Intent(event.action).putExtra(G.EXTRA_PACKAGE, context.packageName)
        event.extras.forEach { (k, v) -> intent.putExtra(k, v) }
        context.sendBroadcast(intent)
        logger.info("Phát sự kiện $event")
    }

    /** Nút "Gửi thử sự kiện": FILE_DONE giả, `request_id` = `test`. */
    suspend fun sendTestEvent() {
        val item = QueueItem(
            source = "/storage/emulated/0/Recordings/thu.m4a", displayName = "thu.m4a", status = ItemStatus.DONE,
            outFile = "/storage/emulated/0/Documents/Guzz/thu.txt", requestId = "test", durationSec = 60.0,
            charCount = 0, modelsUsed = listOf("thu"),
        )
        emit(EventFactory.fileDone(item, settings.get(SettingKeys.AUTOMATION_INCLUDE_PATHS)))
    }

    // ------------------------------------------------------------------ nguồn

    /** Đường dẫn: có quyền thì đọc thẳng; không thì tìm trong Thư mục nguồn (D104). */
    private suspend fun resolvePath(path: String): Resolved {
        val file = File(path)
        if (isAppOwned(path) || fileAccess.hasAllFilesAccess()) {
            return if (file.isFile) Resolved.Path(path) else Resolved.Failed(G.Kind.NOT_FOUND, "không thấy tệp $path")
        }
        for (tree in fileAccess.sourceDirs()) {
            if (tree.authority != EXTERNAL_STORAGE_AUTHORITY) continue
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val docId = PathMapping.documentIdFor(path, treeId) ?: continue
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            return if (readable(doc)) {
                Resolved.Uri(doc.toString())
            } else {
                Resolved.Failed(G.Kind.NOT_FOUND, "không thấy tệp $path trong Thư mục nguồn ${FileAccess.describeTree(tree)}")
            }
        }
        val how = if (allFilesFlavor) "cấp quyền \"Truy cập mọi tệp\" hoặc thêm" else "thêm"
        return Resolved.Failed(
            G.Kind.NO_PERMISSION,
            "Guzz chưa được đọc $path: $how thư mục chứa tệp vào Thư mục nguồn ở trang Tự động hoá",
        )
    }

    /** content:// URI: giữ được quyền bền, hoặc là URI tài liệu trong Thư mục nguồn, thì đọc thẳng; không thì chép (D103). */
    private fun resolveUri(source: String): Resolved {
        val uri = Uri.parse(source)
        val persisted = try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            false
        }
        if (persisted) return Resolved.Uri(source)
        if (!readable(uri)) {
            return Resolved.Failed(G.Kind.NO_PERMISSION, "không đọc được $source (app gửi cần kèm cờ cấp quyền đọc)")
        }
        // URI tài liệu dưới một cây đã cấp: quyền của cây còn mãi, không cần chép.
        if (uri.pathSegments.firstOrNull() == "tree") return Resolved.Uri(source)
        return copyToInbox(uri)
    }

    private fun copyToInbox(uri: Uri): Resolved = try {
        val info = querySource(context, uri.toString())
        val free = StatFs((inbox.parentFile ?: inbox).path).availableBytes
        // D44: kiểm chỗ trống trước; còn chừa 200 MB cho thư mục tạm của chính tệp này.
        if (info.size > 0 && free < info.size + SPARE_BYTES) {
            Resolved.Failed(
                G.Kind.NO_PERMISSION,
                "không đủ chỗ trống để chép ${info.displayName} (${info.size / MB} MB, còn ${free / MB} MB)",
            )
        } else {
            val dir = File(inbox, UUID.randomUUID().toString()).apply { mkdirs() }
            val safeName = info.displayName.replace('/', '_').ifEmpty { "tep" }
            val out = File(dir, safeName)
            context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            logger.info("Chép ${info.displayName} vào ${out.path} (không giữ được quyền đọc bền, D103).")
            Resolved.Copy(out.path, info.displayName)
        }
    } catch (e: Exception) {
        Resolved.Failed(G.Kind.NO_PERMISSION, "không chép được $uri: ${e.message}")
    }

    private fun readable(uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /** Tệp trong thư mục riêng của app (vd `files/input` của lệnh adb) đọc được mà không cần quyền gì. */
    private fun isAppOwned(path: String): Boolean = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
        .any { path.startsWith(it.path + "/") }

    private fun add(r: Resolved, requestId: String?, overrides: ItemOverrides?): Boolean {
        val c = queue()
        return when (r) {
            is Resolved.Path -> c.addPath(File(r.path), requestId = requestId, overrides = overrides).isNotEmpty()
            is Resolved.Uri -> c.addUris(listOf(Uri.parse(r.uri)), requestId, overrides).isNotEmpty()
            is Resolved.Copy -> c.addPath(
                File(r.path), requestId = requestId, overrides = overrides, ownedCopy = true, displayName = r.name,
            ).isNotEmpty()
            is Resolved.Failed -> false
        }
    }

    /** Như nút Bắt đầu: thiếu key / thư mục lưu thì nói lý do; `NOTHING_TO_DO` khi không có tệp chạy được. */
    private suspend fun checkStartProblem(): String? {
        when (val p = queue().checkStart()) {
            null -> Unit
            is StartProblem.NothingToDo -> return CommandHandler.NOTHING_TO_DO
            else -> return p.message
        }
        if (!debug && settings.get(SettingKeys.OUTPUT_DIR).isBlank()) {
            return "Chưa chọn thư mục lưu bản chép lời: mở Guzz, bấm Bắt đầu một lần để chọn thư mục."
        }
        return null
    }

    private inner class Env : CommandEnv {
        override suspend fun enabled() = settings.get(SettingKeys.AUTOMATION_ENABLED)
        override suspend fun secret() = settings.get(SettingKeys.AUTOMATION_SECRET)
        override suspend fun includePaths() = settings.get(SettingKeys.AUTOMATION_INCLUDE_PATHS)
        override suspend fun resolve(source: String, isPath: Boolean) = if (isPath) resolvePath(source) else resolveUri(source)
        override fun add(resolved: Resolved, requestId: String?, overrides: ItemOverrides?) =
            this@Automation.add(resolved, requestId, overrides)
        override suspend fun checkStart() = checkStartProblem()
        override fun start(): StartOutcome = try {
            queue().start()
            StartOutcome.STARTED
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) là IllegalStateException (D69, D102).
            logger.warning("Không khởi động được service từ nền: ${e.javaClass.simpleName}: ${e.message}")
            StartOutcome.BLOCKED
        }
        override fun stop() = queue().stop()
        override fun state() = queue().state.value
        override fun active() = queue().active.value
    }

    // ------------------------------------------------------------------ thông báo, log sự kiện (debug)

    /** D102: "Bấm để bắt đầu" — mở màn hình chính, ở đó hàng đợi tự chạy tiếp (`resumeIfNeeded`) hoặc bấm Bắt đầu. */
    private fun notifyBlocked() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL, context.getString(R.string.automation_channel), NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(context.getString(R.string.automation_blocked_title))
                .setContentText(context.getString(R.string.automation_blocked_text))
                .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.automation_blocked_text)))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    /** C3: bản debug ghi mọi sự kiện `P.event.*` nhận được (của chính Guzz) vào `files/debug-events/events.jsonl`. */
    private fun registerDebugLog() {
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "debug-events/events.jsonl")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val json = JSONObject().put("time", System.currentTimeMillis()).put("action", G.shortName(intent.action))
                intent.extrasMap().forEach { (k, v) -> json.put(k, v?.toString()) }
                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(json.toString() + "\n", Charsets.UTF_8)
                }
            }
        }
        val filter = IntentFilter().apply { G.events.forEach(::addAction) }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    companion object {
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val CHANNEL = "automation"
        private const val NOTIFICATION_ID = 3101
        private const val MB = 1024L * 1024L
        private const val SPARE_BYTES = 200 * MB
    }
}

/** Extra của intent dạng `Map` cho [ExtrasReader]. */
@Suppress("DEPRECATION")
fun Intent.extrasMap(): Map<String, Any?> = extras?.let { b -> b.keySet().associateWith { b.get(it) } } ?: emptyMap()

/** URI ở data và ClipData của intent (app gửi kèm cờ cấp quyền đọc). */
fun Intent.dataUris(): List<String> {
    val out = ArrayList<String>()
    data?.let { out += it.toString() }
    clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out += it.toString() } }
    return out.distinct()
}

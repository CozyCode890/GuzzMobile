package io.github.cozycode890.guzz.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import io.github.cozycode890.guzz.GuzzApp
import io.github.cozycode890.guzz.R
import io.github.cozycode890.guzz.core.audio.PrepareStep
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.media.AudioPreparation
import io.github.cozycode890.guzz.core.media.AudioTrackInfo
import io.github.cozycode890.guzz.core.media.MediaInput
import io.github.cozycode890.guzz.core.media.PreparationListener
import io.github.cozycode890.guzz.core.media.label
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

/** Trạng thái lần chạy hiện tại, để màn hình thử hiển thị. */
data class DebugRunState(
    val running: Boolean = false,
    val input: String = "",
    val step: String = "",
    val fraction: Double = 0.0,
    val result: String = "",
)

/**
 * Foreground service chỉ có ở bản debug (D49): chạy [AudioPreparation] cho một tệp, đo thời gian từng bước và RAM, ghi
 * `files/debug-audio/<tên bài>/ket_qua.json`. Giữ partial wake lock để đo được lúc màn hình tắt (D45). Stage 4 thay bằng
 * service hàng đợi thật.
 */
class AudioDebugService : Service() {

    @Volatile
    private var cancelled = false

    @Volatile
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelled = true
            return START_NOT_STICKY
        }
        startInForeground()
        if (worker?.isAlive == true) {
            GuzzLog.warning("Thử lõi âm thanh: đang chạy một tệp, bỏ qua lệnh mới")
            return START_NOT_STICKY
        }
        val input = intent?.let(::inputFrom)
        if (input == null) {
            GuzzLog.error("Thử lõi âm thanh: thiếu extra uri / path")
            stopSelf()
            return START_NOT_STICKY
        }
        val track = intent.getStringExtra(EXTRA_TRACK) ?: "auto"
        cancelled = false
        worker = thread(name = "guzz-debug-audio") {
            try {
                runOnce(input, track)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private fun inputFrom(intent: Intent): MediaInput? {
        intent.getStringExtra(EXTRA_URI)?.let { return MediaInput.Content(Uri.parse(it)) }
        val path = intent.getStringExtra(EXTRA_PATH) ?: return null
        // Tên trơn = tệp trong files/input của app (adb push vào đó, đọc không cần quyền).
        val file = if (path.startsWith("/")) File(path) else File(getExternalFilesDir("input"), path)
        return MediaInput.Path(file)
    }

    private fun displayName(input: MediaInput): String = when (input) {
        is MediaInput.Path -> input.file.name
        is MediaInput.Content -> contentResolver.query(input.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "uri"
    }

    private fun runOnce(input: MediaInput, track: String) {
        val name = displayName(input)
        val folder = File(getExternalFilesDir("debug-audio"), name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}._-]"), "_"))
        folder.mkdirs()
        val power = getSystemService(PowerManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guzz:debug-audio").apply { acquire(6 * 3600_000L) }
        val sampler = PeakMemory().start()
        val stepStart = LinkedHashMap<String, Long>()
        val json = JSONObject()
            .put("tep", input.name)
            .put("thu_muc", folder.path)
            .put("may", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("api", Build.VERSION.SDK_INT)
            .put("man_hinh_bat_luc_dau", power.isInteractive)
        val t0 = SystemClock.elapsedRealtime()
        state.value = DebugRunState(running = true, input = name, step = "giải mã")
        stepStart["giai_ma"] = t0
        GuzzLog.info("Thử lõi âm thanh: bắt đầu $name, luồng $track → ${folder.path}")
        try {
            val settings = runBlocking { AudioPreparation.chunkSettings((application as GuzzApp).container.settings) }
            json.put("cai_dat", JSONObject().put("target_dbfs", settings.targetDbfs).put("trim_silence", settings.trimSilence)
                .put("min_silence_len", settings.minSilenceLen).put("silence_thresh_offset", settings.silenceThreshOffset)
                .put("keep_silence", settings.keepSilence).put("chunk_minutes", settings.chunkMinutes)
                .put("search_seconds", settings.searchSeconds).put("min_last_chunk_seconds", settings.minLastChunkSeconds)
                .put("audio_track", track))
            val listener = object : PreparationListener {
                private var lastUi = 0L

                override fun onTracks(tracks: List<AudioTrackInfo>, chosen: AudioTrackInfo) {
                    json.put("cac_luong", JSONArray(tracks.map { it.describe() })).put("luong_chon", chosen.index)
                }

                override fun onProgress(fraction: Double) = ui("giải mã", fraction)

                override fun onProgress(step: PrepareStep, fraction: Double) {
                    stepStart.putIfAbsent(step.name.lowercase(), SystemClock.elapsedRealtime())
                    ui(step.label, fraction)
                }

                override fun checkCancelled() {
                    if (cancelled) throw CancellationException("người dùng bấm Dừng")
                }

                private fun ui(step: String, fraction: Double) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi < 250 && fraction < 1.0) return
                    lastUi = now
                    state.value = state.value.copy(step = step, fraction = fraction)
                }
            }
            val r = AudioPreparation(this).run(input, track, settings, folder, listener)
            val seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0
            val audioSeconds = r.decode.samples / r.decode.sampleRate.toDouble()
            json.put("ket_qua", "ok")
                .put("bo_giai_ma", r.decode.codecName).put("mime", r.decode.mime)
                .put("tan_so_goc", r.decode.inputSampleRate).put("so_kenh_goc", r.decode.inputChannels)
                .put("thoi_luong_khai_bao_giay", r.track.durationUs / 1e6)
                .put("giay_am_thanh", audioSeconds)
                .put("giay_sau_cat_lang", r.chunks.toTimeMap().cleanDuration)
                .put("so_doan", r.chunks.chunks.size)
                .put("giay_xu_ly", seconds)
                .put("x_thoi_gian_thuc", audioSeconds / seconds)
                .put("ms_giai_ma", r.decodeMs).put("ms_cat_doan", r.prepareMs)
                .put("ms_trong_giai_ma", JSONObject().put("nap_vao", r.decode.feedMs).put("tron_kenh", r.decode.downmixMs)
                    .put("resample", r.decode.resampleMs).put("ghi", r.decode.writeMs))
            val result = "%.0f s âm thanh → %d đoạn trong %.1f s (×%.1f), PSS đỉnh %d MB".format(
                audioSeconds, r.chunks.chunks.size, seconds, audioSeconds / seconds, sampler.peakPssKb / 1024,
            )
            GuzzLog.info("Thử lõi âm thanh: xong $name: $result")
            state.value = DebugRunState(running = false, input = name, step = "xong", fraction = 1.0, result = result)
        } catch (e: Throwable) {
            json.put("ket_qua", "loi").put("loi", "${e.javaClass.simpleName}: ${e.message}")
            GuzzLog.error("Thử lõi âm thanh: lỗi với $name", e)
            state.value = DebugRunState(running = false, input = name, step = "lỗi", result = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            val end = SystemClock.elapsedRealtime()
            val stepsJson = JSONObject()
            val keys = stepStart.keys.toList()
            for ((i, k) in keys.withIndex()) {
                val next = if (i + 1 < keys.size) stepStart.getValue(keys[i + 1]) else end
                stepsJson.put(k, next - stepStart.getValue(k))
            }
            json.put("ms_tung_buoc", stepsJson)
                .put("man_hinh_bat_luc_cuoi", power.isInteractive)
                .put("ram", sampler.stop())
            File(folder, "ket_qua.json").writeText(json.toString(1), Charsets.UTF_8)
            wake.release()
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.debug_audio_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.debug_audio_notification))
            .setOngoing(true)
            .build()
        when {
            Build.VERSION.SDK_INT >= 35 ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            // Android 8–9: chưa có loại FGS, loại khai trong manifest.
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Đỉnh PSS / RSS / heap mỗi giây (như MemSampler của app spike Stage 1). */
    private class PeakMemory {
        @Volatile private var running = false
        private var worker: Thread? = null

        @Volatile var peakPssKb = 0L
            private set
        @Volatile private var peakRssKb = 0L
        @Volatile private var peakHeap = 0L

        fun start(): PeakMemory {
            running = true
            worker = thread(name = "guzz-debug-mem", isDaemon = true) {
                while (running) {
                    sample()
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            return this
        }

        private fun sample() {
            val rt = Runtime.getRuntime()
            peakPssKb = maxOf(peakPssKb, Debug.getPss())
            peakRssKb = maxOf(peakRssKb, status("VmRSS"))
            peakHeap = maxOf(peakHeap, rt.totalMemory() - rt.freeMemory())
        }

        fun stop(): JSONObject {
            running = false
            worker?.interrupt()
            worker?.join(2000)
            sample()
            return JSONObject().put("pss_dinh_mb", peakPssKb / 1024).put("vmrss_dinh_mb", peakRssKb / 1024)
                .put("vmhwm_mb", status("VmHWM") / 1024).put("heap_java_dinh_mb", peakHeap / (1024 * 1024))
        }

        private fun status(key: String): Long = File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: 0L
        }
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_PATH = "path"
        const val EXTRA_TRACK = "track"
        const val ACTION_STOP = "io.github.cozycode890.guzz.debug.action.STOP_AUDIO"
        private const val CHANNEL = "debug-audio"
        private const val NOTIFICATION_ID = 2002

        private val state = MutableStateFlow(DebugRunState())
        val runState: StateFlow<DebugRunState> get() = state

        fun start(context: Context, uri: Uri?, path: String?, track: String) {
            val intent = Intent(context, AudioDebugService::class.java)
                .putExtra(EXTRA_TRACK, track)
            uri?.let { intent.putExtra(EXTRA_URI, it.toString()) }
            path?.let { intent.putExtra(EXTRA_PATH, it) }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AudioDebugService::class.java).setAction(ACTION_STOP))
        }
    }
}

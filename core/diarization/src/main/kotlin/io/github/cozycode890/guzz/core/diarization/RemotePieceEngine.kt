package io.github.cozycode890.guzz.core.diarization

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import io.github.cozycode890.guzz.core.speaker.Piece
import io.github.cozycode890.guzz.core.speaker.PieceEngine
import io.github.cozycode890.guzz.core.speaker.PieceJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Process `:diarize` chết giữa chừng (hết RAM, bị hệ thống giết…): báo lỗi rõ thay vì treo. */
class DiarizeProcessDiedException(message: String) : Exception(message)

/**
 * [PieceEngine] chạy trong process chính, gửi từng phần cho [DiarizeService] (process `:diarize`). Huỷ coroutine thì giết
 * process `:diarize` ngay (sherpa-onnx không dừng được giữa chừng) và trả về; phần đã ghi tệp vẫn còn.
 *
 * Giữ kết nối giữa các phần để không nạp lại model; [close] khi xong cả bài.
 */
class RemotePieceEngine(private val context: Context) : PieceEngine, Closeable {
    private val thread = HandlerThread("diarize-reply").apply { start() }
    private val handler = Handler(thread.looper)
    private var connection: ServiceConnection? = null
    private var service: Messenger? = null

    @Volatile private var remotePid = 0

    @Volatile private var onDeath: (() -> Unit)? = null

    /** Nối tới service, chờ tối đa 30 s. */
    private suspend fun connect(): Messenger {
        service?.takeIf { it.binder.isBinderAlive }?.let { return it }
        val ready = CompletableDeferred<Messenger>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                binder.linkToDeath({ onDeath?.invoke() }, 0)
                ready.complete(Messenger(binder))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                onDeath?.invoke()
            }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = conn
        check(context.bindService(Intent(context, DiarizeService::class.java), conn, Context.BIND_AUTO_CREATE)) {
            "Không nối được service tách giọng"
        }
        return withTimeout(30_000) { ready.await() }.also { service = it }
    }

    override suspend fun run(pcm: File, a: Long, b: Long, numClusters: Int, onProgress: (Int, Int) -> Unit): Piece {
        val messenger = connect()
        val out = File(pcm.parentFile, "diarize_${a}_$numClusters.tmp.json")
        out.delete()
        suspendCancellableCoroutine { cont ->
            // Xong, lỗi, process chết (linkToDeath và onServiceDisconnected đều báo, trên hai luồng khác nhau): chỉ
            // resume một lần.
            val finished = AtomicBoolean(false)
            fun finish(r: Result<Unit>) {
                if (finished.compareAndSet(false, true)) cont.resumeWith(r)
            }
            val reply = Messenger(Handler(thread.looper) { msg ->
                remotePid = msg.data?.getInt(DiarizeService.KEY_PID, 0)?.takeIf { it > 0 } ?: remotePid
                when (msg.what) {
                    DiarizeService.MSG_PROGRESS -> onProgress(msg.arg1, msg.arg2)
                    DiarizeService.MSG_DONE -> finish(Result.success(Unit))
                    DiarizeService.MSG_ERROR ->
                        finish(Result.failure(IllegalStateException(msg.data.getString(DiarizeService.KEY_ERROR))))
                }
                true
            })
            onDeath = {
                finish(
                    Result.failure(
                        DiarizeProcessDiedException("Process tách giọng bị dừng giữa chừng (hết RAM hoặc bị hệ thống giết)"),
                    ),
                )
            }
            cont.invokeOnCancellation {
                finished.set(true)
                kill()
            }
            messenger.send(Message.obtain(null, DiarizeService.MSG_RUN).apply {
                replyTo = reply
                data = Bundle().apply {
                    putString(DiarizeService.KEY_PCM, pcm.absolutePath)
                    putLong(DiarizeService.KEY_A, a)
                    putLong(DiarizeService.KEY_B, b)
                    putInt(DiarizeService.KEY_CLUSTERS, numClusters)
                    putString(DiarizeService.KEY_OUT, out.absolutePath)
                }
            })
        }
        onDeath = null
        return try {
            PieceJson.read(out).toPiece()
        } finally {
            out.delete()
        }
    }

    /** Giết process `:diarize` (cùng uid nên được phép). Không biết pid thì tìm theo tên process. */
    fun kill() {
        val pid = remotePid.takeIf { it > 0 } ?: findPid()
        onDeath = null
        if (pid > 0 && pid != Process.myPid()) {
            Log.i(DiarizeService.TAG, "Dừng tách giọng: giết process :diarize (pid $pid)")
            Process.killProcess(pid)
        }
        remotePid = 0
        service = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private fun findPid(): Int {
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return 0
        val name = context.packageName + PROCESS_SUFFIX
        return am.runningAppProcesses?.firstOrNull { it.processName == name }?.pid ?: 0
    }

    override fun close() {
        onDeath = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
        thread.quitSafely()
    }

    companion object {
        const val PROCESS_SUFFIX = ":diarize"
    }
}

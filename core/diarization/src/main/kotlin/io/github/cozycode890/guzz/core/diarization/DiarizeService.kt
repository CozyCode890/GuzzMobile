package io.github.cozycode890.guzz.core.diarization

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import io.github.cozycode890.guzz.core.speaker.PieceJson
import java.io.File

/**
 * Service bound chạy trong process riêng `:diarize` (khai báo ở manifest của module). Vì sao process riêng: sherpa-onnx
 * không dừng được giữa chừng (callback trả về khác 0 bị bỏ qua, Stage 1 D7), nên bấm Dừng thì process chính giết process
 * này (54 ms ở Stage 1); phân cụm cũng tốn RAM, hết RAM thì chỉ process này bị giết, process chính còn sống và báo lỗi.
 *
 * Giao thức [Messenger]: [MSG_RUN] (Bundle: [KEY_PCM], [KEY_A], [KEY_B], [KEY_CLUSTERS], [KEY_OUT]) → nhiều
 * [MSG_PROGRESS] (arg1 khối đã xong, arg2 tổng khối) → [MSG_DONE] hoặc [MSG_ERROR] ([KEY_ERROR]). Mọi tin trả về có
 * [KEY_PID] để process chính giết được process này. Mỗi lần một phần; kết quả ghi vào [KEY_OUT] (`.tmp` rồi đổi tên).
 */
class DiarizeService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var diarizer: SherpaPieceDiarizer? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("diarize").apply { start() }
        handler = Handler(worker.looper) { msg -> handle(msg); true }
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(handler).binder

    override fun onDestroy() {
        handler.post { diarizer?.close(); diarizer = null }
        worker.quitSafely()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        if (msg.what != MSG_RUN) return
        val reply = msg.replyTo ?: return
        val req = msg.data
        val pcm = File(req.getString(KEY_PCM)!!)
        val a = req.getLong(KEY_A)
        val b = req.getLong(KEY_B)
        val clusters = req.getInt(KEY_CLUSTERS)
        val out = File(req.getString(KEY_OUT)!!)
        val t0 = SystemClock.elapsedRealtime()
        try {
            val d = diarizer ?: SherpaPieceDiarizer(assets).also { diarizer = it }
            var last = 0L
            val piece = d.run(pcm, a, b, clusters) { done, total ->
                val now = SystemClock.elapsedRealtime()
                // Không gửi mỗi khối: một phần 20 phút có ~2 000 khối.
                if (done == total || now - last >= 500) {
                    last = now
                    send(reply, Message.obtain(null, MSG_PROGRESS, done, total))
                }
            }
            PieceJson.write(out, PieceJson.from(piece, clusters))
            Log.i(TAG, "Xong phần [$a, $b) số cụm $clusters: ${piece.labels.size} nhãn, ${SystemClock.elapsedRealtime() - t0} ms")
            send(reply, Message.obtain(null, MSG_DONE))
        } catch (e: Throwable) {
            Log.e(TAG, "Lỗi tách giọng phần [$a, $b)", e)
            send(reply, Message.obtain(null, MSG_ERROR).apply {
                data = Bundle().apply { putString(KEY_ERROR, "${e.javaClass.simpleName}: ${e.message}") }
            })
        }
    }

    private fun send(to: Messenger, m: Message) {
        m.data = (m.peekData() ?: Bundle()).apply { putInt(KEY_PID, Process.myPid()) }
        try {
            to.send(m)
        } catch (_: RemoteException) {
            // Process chính đã đi (hoặc huỷ): không còn ai nghe.
        }
    }

    companion object {
        const val TAG = "Guzz"
        const val MSG_RUN = 1
        const val MSG_PROGRESS = 2
        const val MSG_DONE = 3
        const val MSG_ERROR = 4
        const val KEY_PCM = "pcm"
        const val KEY_A = "a"
        const val KEY_B = "b"
        const val KEY_CLUSTERS = "clusters"
        const val KEY_OUT = "out"
        const val KEY_ERROR = "error"
        const val KEY_PID = "pid"
    }
}

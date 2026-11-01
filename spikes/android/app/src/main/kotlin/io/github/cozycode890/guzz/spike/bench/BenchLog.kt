package io.github.cozycode890.guzz.spike.bench

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import io.github.cozycode890.guzz.spike.Paths
import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime

/** Ghi JSON lines vào `files/results/<tên>.jsonl`: mỗi dòng có giờ, model máy, API, pid, rồi nội dung của dòng đó. */
class BenchLog(val file: File) {
    fun write(fields: JSONObject) {
        val line = header().also { h -> fields.keys().forEach { h.put(it, fields.get(it)) } }.toString()
        Log.i(TAG, "${file.name}: $line")
        synchronized(LOCK) { file.appendText(line + "\n") }
    }

    fun write(vararg pairs: Pair<String, Any?>) = write(json(*pairs))

    companion object {
        const val TAG = "GuzzSpike"
        private val LOCK = Any()

        fun of(ctx: Context, name: String) = BenchLog(File(Paths.results(ctx), "$name.jsonl"))

        fun header(): JSONObject = JSONObject()
            .put("gio", OffsetDateTime.now().toString())
            .put("may", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("api", Build.VERSION.SDK_INT)
            .put("pid", Process.myPid())
    }
}

fun json(vararg pairs: Pair<String, Any?>): JSONObject =
    JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

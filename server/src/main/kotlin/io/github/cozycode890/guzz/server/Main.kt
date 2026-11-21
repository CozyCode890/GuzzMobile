package io.github.cozycode890.guzz.server

import io.github.cozycode890.guzz.core.remote.JobErrors
import io.github.cozycode890.guzz.core.remote.JobRequest
import io.github.cozycode890.guzz.core.remote.JobState
import io.github.cozycode890.guzz.core.remote.JobStatus
import io.github.cozycode890.guzz.core.remote.Protocol
import io.github.cozycode890.guzz.core.remote.ServerInfo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Mã thoát của `guzz-server.jar process` (C5); vỏ Modal đọc `status.json`, mã thoát để kiểm nhanh. */
object ExitCodes {
    const val OK = 0
    const val INTERNAL = 1
    const val BAD_REQUEST = 2
    const val CANCELLED = 3
    const val NO_AUDIO = 4
    const val NO_SPEECH = 5

    fun of(status: JobStatus): Int = when {
        status.state == JobState.DONE -> OK
        status.error == JobErrors.BAD_REQUEST -> BAD_REQUEST
        status.error == JobErrors.CANCELLED -> CANCELLED
        status.error == JobErrors.NO_AUDIO -> NO_AUDIO
        status.error == JobErrors.NO_SPEECH -> NO_SPEECH
        else -> INTERNAL
    }
}

/** Phiên bản server (manifest của jar; chạy từ Gradle thì `dev`). */
val SERVER_VERSION: String = ExitCodes::class.java.`package`?.implementationVersion ?: "dev"

/**
 * ```
 * java -jar guzz-server.jar process --in <tệp> --request req.json --out <thư mục> --status status.json
 *                                   [--models <thư mục model>] [--threads N]
 * java -jar guzz-server.jar version
 * ```
 * Model mặc định: `GUZZ_MODELS`, không có thì `/models`. Số luồng mặc định: số nhân JVM thấy.
 */
fun main(args: Array<String>) {
    val cancelled = AtomicBoolean(false)
    installTermHandler(cancelled)
    exitProcess(runCli(args, cancelled))
}

/** Phần chạy được trong test (không gọi `exitProcess`). */
fun runCli(args: Array<String>, cancelled: AtomicBoolean = AtomicBoolean(false), log: (String) -> Unit = ::println): Int {
    if (args.firstOrNull() == "version") {
        println(Protocol.json.encodeToString(ServerInfo.serializer(), ServerInfo(Protocol.VERSION, SERVER_VERSION)))
        println(Ffmpeg.version())
        return ExitCodes.OK
    }
    if (args.firstOrNull() != "process") {
        System.err.println("Dùng: process --in <tệp> --request <json> --out <thư mục> --status <json> | version")
        return ExitCodes.BAD_REQUEST
    }
    val opts = args.drop(1).chunked(2).filter { it.size == 2 && it[0].startsWith("--") }
        .associate { it[0].removePrefix("--") to it[1] }
    val statusFile = opts["status"]?.let { StatusFile(File(it)) }
    fun fail(message: String): Int {
        statusFile?.write(JobStatus(state = JobState.FAILED, error = JobErrors.BAD_REQUEST, message = message))
        System.err.println(message)
        return ExitCodes.BAD_REQUEST
    }
    val input = opts["in"]?.let(::File) ?: return fail("Thiếu --in")
    val out = opts["out"]?.let(::File) ?: return fail("Thiếu --out")
    if (statusFile == null) return fail("Thiếu --status")
    if (!input.isFile) return fail("Không có tệp đầu vào")
    val request = try {
        Protocol.json.decodeFromString(JobRequest.serializer(), File(opts["request"] ?: return fail("Thiếu --request")).readText())
    } catch (e: Exception) {
        return fail("Yêu cầu hỏng: ${e.message}")
    }
    val models = File(opts["models"] ?: System.getenv("GUZZ_MODELS") ?: "/models")
    val threads = opts["threads"]?.toIntOrNull() ?: Runtime.getRuntime().availableProcessors()
    val status = JobProcessor(models, threads, cancelled, log).process(request, input, out, statusFile::write)
    return ExitCodes.of(status)
}

/**
 * SIGTERM (Modal huỷ hàm, `DELETE /jobs/{id}`): đặt cờ để dừng ở điểm kiểm tiếp theo và thoát mã [ExitCodes.CANCELLED].
 * Bắt bằng `sun.misc.Signal` thay cho shutdown hook, vì trong shutdown hook không đổi được mã thoát. sherpa-onnx không
 * dừng giữa một phần được, nên sau 20 s mà chưa thoát thì `halt`.
 */
private fun installTermHandler(cancelled: AtomicBoolean) {
    try {
        sun.misc.Signal.handle(sun.misc.Signal("TERM")) {
            cancelled.set(true)
            Thread({
                Thread.sleep(20_000)
                Runtime.getRuntime().halt(ExitCodes.CANCELLED)
            }, "term-watchdog").apply { isDaemon = true }.start()
        }
    } catch (e: Throwable) {
        // Hệ điều hành không có SIGTERM: bỏ qua, chỉ mất khả năng dừng êm.
    }
}

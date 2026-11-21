package io.github.cozycode890.guzz.feature.queue

import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import io.github.cozycode890.guzz.core.remote.JobRequest
import io.github.cozycode890.guzz.core.remote.Reasons
import io.github.cozycode890.guzz.core.remote.RemoteClient
import io.github.cozycode890.guzz.core.remote.RemoteProcessor
import io.github.cozycode890.guzz.core.remote.RemoteUnavailableException
import io.github.cozycode890.guzz.core.remote.ServerInfo
import io.github.cozycode890.guzz.core.remote.UploadSource
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Server xử lý âm thanh theo cài đặt (Stage 11): URL đọc từ `[SERVER] url`, token (`<Modal-Key>.<Modal-Secret>`) từ
 * Keystore, đọc lại mỗi tệp nên đổi cài đặt có tác dụng từ tệp sau. Thiếu URL / token thì ném
 * [RemoteUnavailableException] (`config`): pipeline làm trên máy.
 */
class ServerRemote(
    private val settings: Settings,
    private val token: () -> String?,
    http: OkHttpClient,
) : RemoteProcessor {
    // Tải lên / tải về bài dài: không giới hạn cả cuộc gọi, chỉ giới hạn lúc mạng đứng im.
    private val http = http.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private suspend fun client(url: String? = null, tokenOverride: String? = null): RemoteClient {
        val u = (url ?: settings.get(SettingKeys.SERVER_URL)).trim()
        val t = (tokenOverride ?: token()).orEmpty().trim()
        if (u.isEmpty()) throw RemoteUnavailableException(Reasons.CONFIG, "Chưa nhập URL server")
        if (t.isEmpty()) throw RemoteUnavailableException(Reasons.CONFIG, "Chưa nhập token server")
        return RemoteClient(http, u, t)
    }

    override suspend fun process(
        upload: UploadSource,
        request: JobRequest,
        folder: File,
        log: (String) -> Unit,
        onProgress: (String, Double) -> Unit,
    ) = client().process(upload, request, folder, log, onProgress)

    /** Nút Kiểm tra kết nối (F2): [url] / [token] đang gõ trên màn hình, `null` = giá trị đã lưu. */
    suspend fun health(url: String? = null, token: String? = null): ServerInfo = client(url, token).health()
}

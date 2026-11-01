package io.github.cozycode890.guzz.spike.automation

import android.app.Activity
import android.os.Build
import android.os.Bundle

/**
 * Cách b của G1: MacroDroid mở activity trong suốt này (MacroDroid có quyền hiện trên ứng dụng khác nên được mở activity
 * từ nền). Lúc activity hiện, app được khởi động FGS; gọi xong thì đóng ngay. Extra `show_when_locked` thử thêm
 * `setShowWhenLocked(true)` khi bản thường không chạy lúc màn hình tắt.
 */
class TrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val method = intent.getStringExtra("method") ?: "trampoline"
        val requestId = intent.getStringExtra("request_id")
        val minutes = intent.extras?.get("minutes")?.toString()?.toIntOrNull() ?: 3
        if (intent.getBooleanExtra("show_when_locked", false) && Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        AutomationLog.write(this, "nhan", method, requestId)
        RunReceiver.startHeartbeat(this, method, requestId, minutes, intent.extras)
        finish()
    }
}

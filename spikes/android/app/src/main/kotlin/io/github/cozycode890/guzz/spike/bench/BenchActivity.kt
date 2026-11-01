package io.github.cozycode890.guzz.spike.bench

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/**
 * Nhận lệnh đo từ adb rồi khởi động [BenchService] trong lúc activity đang hiện, nên không vướng hạn chế khởi động FGS
 * từ nền:
 *
 *     adb shell am start -n io.github.cozycode890.guzz.spike.debug/io.github.cozycode890.guzz.spike.bench.BenchActivity \
 *         --es bench noop --ei seconds 60
 */
class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        launch()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        launch()
    }

    private fun launch() {
        startForegroundService(Intent(this, BenchService::class.java).putExtras(intent.extras ?: Bundle()))
        finish()
    }
}

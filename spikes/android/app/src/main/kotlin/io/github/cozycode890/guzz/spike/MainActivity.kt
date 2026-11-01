package io.github.cozycode890.guzz.spike

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cozycode890.guzz.spike.automation.AutomationLog
import io.github.cozycode890.guzz.spike.automation.RunReceiver
import java.io.File

/** Màn hình chính: trạng thái máy, các nút cài đặt một lần (G3), 20 dòng gần nhất của automation.jsonl. */
class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Screen(refresh)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        // Bản Play không được dùng intent này (chính sách Play); spike và bản sideload thì được.
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    @androidx.compose.runtime.Composable
    private fun Screen(tick: Int) {
        val state = remember(tick) { AutomationLog.state(this).toString(1) }
        val lines = remember(tick) {
            File(Paths.results(this), "automation.jsonl").takeIf { it.isFile }?.readLines()?.takeLast(20)?.reversed()
                ?: emptyList()
        }
        var message by remember { mutableStateOf("") }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Guzz spike", style = MaterialTheme.typography.headlineMedium)
            Text(state, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Button(onClick = ::requestBatteryExemption) { Text("Xin miễn tối ưu pin") }
            if (Build.VERSION.SDK_INT >= 33) {
                Button(onClick = { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }) {
                    Text("Xin quyền thông báo")
                }
            }
            Button(onClick = {
                RunReceiver.startHeartbeat(this@MainActivity, "app", "app-${System.currentTimeMillis()}", 5)
                message = "Đã khởi động heartbeat 5 phút"
                refresh++
            }) { Text("Heartbeat 5 phút (G5)") }
            Button(onClick = { refresh++ }) { Text("Làm mới") }
            if (message.isNotEmpty()) Text(message)
            Text("automation.jsonl (mới nhất trước)", style = MaterialTheme.typography.titleSmall)
            lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
        }
    }
}

package io.github.cozycode890.guzz.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Bấm Dừng bằng adb khi màn hình đã tắt (chỉ bản debug):
 *
 *     adb shell am broadcast -n io.github.cozycode890.guzz.debug/io.github.cozycode890.guzz.debug.AudioDebugStopReceiver
 *
 * Gửi `--ez stop true` cho [AudioDebugActivity] không được lúc màn hình tắt: activity đang bị dừng nên intent mới chỉ
 * tới khi nó hiện lại. Service không exported nên adb không gửi thẳng tới nó được.
 */
class AudioDebugStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AudioDebugService.stop(context)
}

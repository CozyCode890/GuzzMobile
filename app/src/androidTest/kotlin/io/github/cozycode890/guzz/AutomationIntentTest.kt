package io.github.cozycode890.guzz

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.feature.automation.GuzzIntents
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Stage 6 C5: gửi lệnh TRANSCRIBE tới [GuzzIntents.RECEIVER_CLASS] như MacroDroid (broadcast có component), tệp vào
 * hàng đợi, chạy với Gemini giả, nhận sự kiện FILE_DONE cùng `request_id` bằng một receiver đăng ký lúc chạy. Chỉ bản
 * debug (Gemini giả, thư mục ra `files/output`).
 */
@RunWith(AndroidJUnit4::class)
class AutomationIntentTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = (context.applicationContext as GuzzApp).container
    private val settings = container.settings
    private val input = File(context.getExternalFilesDir(null), "input/c5_${UUID.randomUUID()}.wav")
    private var hadFakeFlag = false
    private val received = ConcurrentHashMap<String, Intent>()
    private var latch = CountDownLatch(1)
    private var waitFor: Pair<String, String>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val id = intent.getStringExtra(GuzzIntents.EXTRA_REQUEST_ID).orEmpty()
            received["${intent.action}|$id"] = intent
            if (waitFor == intent.action to id) latch.countDown()
        }
    }

    @Before
    fun setUp() {
        hadFakeFlag = container.fakeGeminiFlag.exists()
        container.fakeGeminiFlag.parentFile?.mkdirs()
        container.fakeGeminiFlag.writeText("1")
        runBlocking {
            settings.set(SettingKeys.AUTOMATION_ENABLED, true)
            settings.set(SettingKeys.AUTOMATION_SECRET, "")
        }
        writeToneWav(input, seconds = 8)
        val filter = IntentFilter().apply { GuzzIntents.events.forEach(::addAction) }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    @After
    fun tearDown() {
        context.unregisterReceiver(receiver)
        runBlocking {
            settings.reset(SettingKeys.AUTOMATION_ENABLED)
            settings.reset(SettingKeys.AUTOMATION_SECRET)
        }
        val queue = container.queueController
        queue.stop()
        queue.state.value.items.filter { it.source.endsWith(input.name) }.forEach { queue.remove(it.id) }
        if (!hadFakeFlag) container.fakeGeminiFlag.delete()
        input.delete()
    }

    private fun send(action: String, vararg extras: Pair<String, String>) {
        val intent = Intent(action).setComponent(ComponentName(context.packageName, GuzzIntents.RECEIVER_CLASS))
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        context.sendBroadcast(intent)
    }

    private fun await(action: String, requestId: String, seconds: Long): Intent? {
        received["$action|$requestId"]?.let { return it }
        latch = CountDownLatch(1)
        waitFor = action to requestId
        received["$action|$requestId"]?.let { return it }
        latch.await(seconds, TimeUnit.SECONDS)
        return received["$action|$requestId"]
    }

    @Test
    fun transcribeThenFileDoneWithSameRequestId() {
        val id = "c5-${UUID.randomUUID()}"
        send(GuzzIntents.ACTION_TRANSCRIBE, GuzzIntents.EXTRA_PATH to input.path, GuzzIntents.EXTRA_REQUEST_ID to id)
        val done = await(GuzzIntents.EVENT_FILE_DONE, id, 90)
        assertNotNull("không nhận được FILE_DONE", done)
        assertEquals(context.packageName, done!!.getStringExtra(GuzzIntents.EXTRA_PACKAGE))
        assertEquals(input.name, done.getStringExtra(GuzzIntents.EXTRA_NAME))
        val out = done.getStringExtra(GuzzIntents.EXTRA_OUTPUT_PATH)
        assertTrue("không có bản chép lời: $out", out != null && File(out).isFile)
        assertNotNull(received["${GuzzIntents.EVENT_FILE_STARTED}|$id"])
    }

    @Test
    fun wrongSecretIsRejectedAndQueueUnchanged() {
        runBlocking { settings.set(SettingKeys.AUTOMATION_SECRET, "dung-ma") }
        val before = container.queueController.state.value.items.size
        val id = "c5s-${UUID.randomUUID()}"
        send(
            GuzzIntents.ACTION_TRANSCRIBE, GuzzIntents.EXTRA_PATH to input.path, GuzzIntents.EXTRA_REQUEST_ID to id,
            GuzzIntents.EXTRA_SECRET to "sai-ma",
        )
        val error = await(GuzzIntents.EVENT_ERROR, id, 20)
        assertNotNull("không nhận được ERROR", error)
        assertEquals(GuzzIntents.Kind.BAD_SECRET, error!!.getStringExtra(GuzzIntents.EXTRA_ERROR_KIND))
        assertEquals(before, container.queueController.state.value.items.size)
    }
}

package io.github.cozycode890.guzz

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cozycode890.guzz.core.media.AudioTracks
import io.github.cozycode890.guzz.core.media.MediaInput
import io.github.cozycode890.guzz.feature.queue.querySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Stage 6 D3 (Phát sinh Stage 2): đọc tệp qua ContentProvider thật như khi app khác chia sẻ tới Guzz. MediaStore không có
 * cột `last_modified` (từng làm `querySource` ném lỗi); provider của bản debug trả độ dài không rõ (−1).
 */
@RunWith(AndroidJUnit4::class)
class ContentSourceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cleanup = ArrayList<() -> Unit>()

    @After
    fun tearDown() = cleanup.forEach { runCatching(it) }

    private fun assertReadable(uri: Uri, name: String) {
        val info = querySource(context, uri.toString())
        assertEquals(name, info.displayName)
        val tracks = MediaInput.Content(uri).open(context).use { AudioTracks.list(it.extractor) }
        assertTrue("không thấy luồng âm thanh trong $uri", tracks.isNotEmpty())
    }

    @Test
    fun mediaStoreDownloads() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val name = "guzz_d3_${System.nanoTime()}.wav"
        val tmp = File(context.cacheDir, name).also { writeToneWav(it, seconds = 3) }
        cleanup += { tmp.delete() }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/guzz-test")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        cleanup += { context.contentResolver.delete(uri, null, null) }
        context.contentResolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        assertReadable(uri, name)
    }

    @Test
    fun providerWithUnknownLength() {
        val name = "khong_ro_do_dai.wav"
        val f = File(context.filesDir, "provider-test/$name").also { writeToneWav(it, seconds = 3) }
        cleanup += { f.delete() }
        val uri = Uri.parse("content://${context.packageName}.unknownlength/$name")
        assertEquals(-1L, context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { it.declaredLength })
        assertReadable(uri, name)
    }
}

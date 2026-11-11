package io.github.cozycode890.guzz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** D1: [KeystoreApiKeyStore] trên máy thật (Android Keystore chỉ có trên thiết bị). Key giả, alias riêng cho test. */
@RunWith(AndroidJUnit4::class)
class KeystoreApiKeyStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.filesDir, "test-secrets/api_key.bin")
    private val alias = "guzz_api_key_test"
    private val key = "FAKEKEY-0123456789abcdefghijKLMNOPQ"

    @After
    fun cleanUp() {
        file.parentFile?.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun saveThenReadBackEvenWithNewStore() {
        KeystoreApiKeyStore(file, alias).save("  $key \n")
        assertEquals(key, KeystoreApiKeyStore(file, alias).read())
    }

    @Test
    fun clearThenReadIsEmpty() {
        val store = KeystoreApiKeyStore(file, alias)
        store.save(key)
        store.clear()
        assertNull(store.read())
        assertFalse(file.exists())
        store.save(key)
        store.save("   ") // key rỗng = xoá, như save_api_key của desktop
        assertNull(store.read())
    }

    @Test
    fun fileOnDiskHasNoPlainKey() {
        KeystoreApiKeyStore(file, alias).save(key)
        val bytes = file.readBytes()
        assertTrue(bytes.size > key.length)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(key))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(key.substring(8, 20)))
    }

    @Test
    fun lostKeystoreKeyMeansNoKey() {
        KeystoreApiKeyStore(file, alias).save(key)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        assertNull(KeystoreApiKeyStore(file, alias).read())
        assertFalse(file.exists())
    }
}

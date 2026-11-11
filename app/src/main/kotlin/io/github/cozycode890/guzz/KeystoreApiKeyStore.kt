package io.github.cozycode890.guzz

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.cozycode890.guzz.core.common.log.GuzzLog
import io.github.cozycode890.guzz.core.gemini.ApiKeyStore
import java.io.File
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [ApiKeyStore] mã hoá API key bằng AES-GCM với khoá nằm trong Android Keystore (khoá không bao giờ ra khỏi phần cứng /
 * TEE, app chỉ nhờ Keystore mã hoá / giải mã). Bản mã nằm ở [file] trong bộ nhớ trong của app.
 *
 * Tệp không chép sang máy khác (D55); nếu bằng cách nào đó tệp còn mà khoá Keystore đã mất (cài lại, khôi phục) thì
 * không giải mã được: coi như chưa có key, xoá tệp, người dùng nhập lại.
 *
 * Định dạng tệp: 1 byte phiên bản, 1 byte độ dài IV, IV, bản mã (kèm thẻ GCM 128 bit).
 */
class KeystoreApiKeyStore(private val file: File, private val alias: String = DEFAULT_ALIAS) : ApiKeyStore {
    private val lock = Any()

    override fun read(): String? = synchronized(lock) {
        if (!file.isFile) return null
        try {
            val buf = ByteBuffer.wrap(file.readBytes())
            require(buf.get() == FORMAT_VERSION) { "phiên bản tệp lạ" }
            val iv = ByteArray(buf.get().toInt()).also { buf.get(it) }
            val sealed = ByteArray(buf.remaining()).also { buf.get(it) }
            val key = existingKey() ?: throw GeneralSecurityException("không còn khoá $alias trong Keystore")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(sealed), Charsets.UTF_8).ifEmpty { null }
        } catch (e: Exception) {
            // Không ghi nội dung tệp hay key vào log, chỉ loại lỗi.
            GuzzLog.warning("Không đọc được API key đã lưu (${e.javaClass.simpleName}), coi như chưa có key.")
            file.delete()
            null
        }
    }

    override fun save(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return clear()
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
            val iv = cipher.iv
            val sealed = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val bytes = ByteBuffer.allocate(2 + iv.size + sealed.size)
                .put(FORMAT_VERSION).put(iv.size.toByte()).put(iv).put(sealed).array()
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "không ghi được $file" }
            }
        }
    }

    override fun clear() {
        synchronized(lock) {
            file.delete()
            File(file.path + ".tmp").delete()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "guzz_api_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val FORMAT_VERSION: Byte = 1
    }
}

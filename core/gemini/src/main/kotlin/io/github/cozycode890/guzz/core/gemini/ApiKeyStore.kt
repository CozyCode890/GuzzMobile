package io.github.cozycode890.guzz.core.gemini

/**
 * Nơi giữ API key của Google AI Studio. Thay cho `api_key_source` / `save_api_key` của google_ai.py: trên mobile
 * không đọc biến môi trường hay tệp `api_key.txt`, người dùng nhập key tay (D54).
 *
 * Bản thật ở `:app` mã hoá bằng khoá trong Android Keystore. Không bao giờ ghi key ra log; cần hiện thì dùng
 * [maskApiKey].
 */
interface ApiKeyStore {
    /** Key đã lưu, `null` nếu chưa có. */
    fun read(): String?

    /** Lưu key (đã bỏ khoảng trắng hai đầu); key rỗng = xoá, như `save_api_key` của desktop. */
    fun save(key: String)

    fun clear()
}

/** Giữ key trong bộ nhớ, cho test. */
class InMemoryApiKeyStore(private var key: String? = null) : ApiKeyStore {
    override fun read(): String? = key

    override fun save(key: String) {
        this.key = key.trim().ifEmpty { null }
    }

    override fun clear() {
        key = null
    }
}

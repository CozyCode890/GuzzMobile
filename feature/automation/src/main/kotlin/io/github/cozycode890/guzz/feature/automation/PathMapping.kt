package io.github.cozycode890.guzz.feature.automation

/**
 * Đổi đường dẫn tuyệt đối sang mã tài liệu (document id) của `ExternalStorageProvider` trong một "Thư mục nguồn" cấp qua
 * SAF (Stage 6, D104). Mã tài liệu của provider này có dạng `<ổ>:<đường dẫn tương đối>`: bộ nhớ trong là `primary`
 * (`/storage/emulated/0`, `/sdcard`), thẻ nhớ là mã ổ (`/storage/1A2B-3C4D`). Hàm thuần để test trên PC.
 */
object PathMapping {
    private val PRIMARY_ROOTS = listOf("/storage/emulated/0", "/sdcard", "/storage/self/primary", "/mnt/sdcard")

    /** (ổ, đường dẫn tương đối không có `/` đầu) của một đường dẫn; `null` khi không nằm trong bộ nhớ dùng chung. */
    fun volumeAndRelative(path: String): Pair<String, String>? {
        val clean = normalize(path) ?: return null
        for (root in PRIMARY_ROOTS) {
            if (clean == root) return "primary" to ""
            if (clean.startsWith("$root/")) return "primary" to clean.removePrefix("$root/")
        }
        val m = Regex("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(/.*)?$").matchEntire(clean) ?: return null
        return m.groupValues[1] to m.groupValues[2].removePrefix("/")
    }

    /**
     * Mã tài liệu của [path] nếu nó nằm trong thư mục có mã [treeDocumentId] (vd `primary:Recordings`), không thì `null`.
     * Chính thư mục đó không tính (cần tệp bên trong).
     */
    fun documentIdFor(path: String, treeDocumentId: String): String? {
        val (volume, rel) = volumeAndRelative(path) ?: return null
        val treeVolume = treeDocumentId.substringBefore(':')
        val treeRel = treeDocumentId.substringAfter(':', "").trim('/')
        if (!treeVolume.equals(volume, ignoreCase = true) || rel.isEmpty()) return null
        if (treeRel.isNotEmpty() && !rel.startsWith("$treeRel/")) return null
        return "$treeVolume:$rel"
    }

    /** Đường dẫn của một mã tài liệu (để hiện Thư mục nguồn cho người dùng), vd `primary:Recordings` → `/storage/emulated/0/Recordings`. */
    fun pathOf(documentId: String): String {
        val volume = documentId.substringBefore(':')
        val rel = documentId.substringAfter(':', "").trim('/')
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    /** Bỏ `//`, `/./`; có `..` thì từ chối (không đoán được thư mục thật). */
    private fun normalize(path: String): String? {
        if (!path.startsWith("/")) return null
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return "/" + parts.joinToString("/")
    }
}

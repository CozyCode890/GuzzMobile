package io.github.cozycode890.guzz.feature.automation

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import io.github.cozycode890.guzz.core.common.settings.SettingKeys
import io.github.cozycode890.guzz.core.common.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Quyền đọc tệp cho lệnh của app khác (D6, D104). Stage 8 dùng lại cho Thiết lập ban đầu (H2).
 *
 * @param allFilesFlavor bản sideload: được xin "Truy cập mọi tệp" (`BuildConfig.ALL_FILES_ACCESS`); bản play luôn `false`.
 */
class FileAccess(private val context: Context, private val settings: Settings, val allFilesFlavor: Boolean) {
    /** Có quyền đọc mọi tệp bằng đường dẫn: Android 11+ "Truy cập mọi tệp", Android 8–10 `READ_EXTERNAL_STORAGE`. */
    fun hasAllFilesAccess(): Boolean = allFilesFlavor && if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** Android 8–10 xin quyền lúc chạy (trang gọi `RequestPermission`) thay cho trang cài đặt. */
    val needsRuntimePermission: Boolean get() = allFilesFlavor && Build.VERSION.SDK_INT < 30

    /** Trang cài đặt "Truy cập mọi tệp" của Guzz (Android 11+, bản sideload); `null` khi không dùng được. */
    @SuppressLint("InlinedApi")
    fun allFilesAccessIntent(): Intent? = if (allFilesFlavor && Build.VERSION.SDK_INT >= 30) {
        Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    } else {
        null
    }

    fun sourceDirsFlow(): Flow<List<Uri>> = settings.flow(SettingKeys.AUTOMATION_SOURCE_DIRS).map(::parse)

    suspend fun sourceDirs(): List<Uri> = parse(settings.get(SettingKeys.AUTOMATION_SOURCE_DIRS))

    /** Thêm Thư mục nguồn (URI cây từ `OpenDocumentTree`): giữ quyền đọc bền rồi lưu. */
    suspend fun addSourceDir(tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val dirs = sourceDirs()
        if (tree !in dirs) settings.set(SettingKeys.AUTOMATION_SOURCE_DIRS, (dirs + tree).joinToString("\n"))
    }

    /** Bỏ Thư mục nguồn và trả quyền (trừ khi đó cũng là thư mục lưu bản chép lời). */
    suspend fun removeSourceDir(tree: Uri) {
        settings.set(SettingKeys.AUTOMATION_SOURCE_DIRS, (sourceDirs() - tree).joinToString("\n"))
        if (settings.get(SettingKeys.OUTPUT_DIR) != tree.toString()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** Có ít nhất một Thư mục nguồn còn quyền: thay cho "Truy cập mọi tệp" ở bản play (Stage 8 H2). */
    suspend fun hasUsableSourceDir(): Boolean {
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
        return sourceDirs().any { it in granted }
    }

    private fun parse(text: String): List<Uri> = text.lines().map(String::trim).filter(String::isNotEmpty).map(Uri::parse)

    companion object {
        /** Đường dẫn để hiện của một URI cây (vd `/storage/emulated/0/Recordings`); không đoán được thì chính URI. */
        fun describeTree(tree: Uri): String = runCatching {
            PathMapping.pathOf(DocumentsContract.getTreeDocumentId(tree))
        }.getOrDefault(tree.toString())
    }
}

/**
 * Miễn tối ưu pin (D69): thiếu thì Android 12+ không cho receiver ở nền khởi động foreground service. Bản sideload xin
 * thẳng bằng hộp thoại hệ thống; bản play mở danh sách cài đặt pin (chính sách Play hạn chế quyền xin thẳng).
 */
class BatteryStatus(private val context: Context, private val canRequestDirectly: Boolean) {
    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife")
    fun requestIntent(): Intent = if (canRequestDirectly) {
        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    } else {
        Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /** Thông báo của app đang bật (thiếu thì service vẫn chạy nhưng không có thông báo, không có nút Dừng; Phát sinh F2). */
    fun notificationsEnabled(): Boolean = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}

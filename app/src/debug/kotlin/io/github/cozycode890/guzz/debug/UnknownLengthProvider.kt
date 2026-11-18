package io.github.cozycode890.guzz.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Chỉ bản debug, cho test thiết bị (Stage 6 D3, Phát sinh Stage 2): provider trả tệp với độ dài không rõ
 * (`UNKNOWN_LENGTH`, cột SIZE rỗng) như provider của vài trình chọn tệp, để kiểm Guzz vẫn đọc được. URI
 * `content://<package>.unknownlength/<tên tệp trong files/provider-test>`. Không exported.
 */
class UnknownLengthProvider : ContentProvider() {
    private fun file(uri: Uri) = File(context!!.filesDir, "provider-test/${uri.lastPathSegment}")

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(uri.lastPathSegment, null)) }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor =
        AssetFileDescriptor(ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri) = "audio/wav"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

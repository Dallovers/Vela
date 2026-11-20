package com.dallovers.phoneagent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/** An exact URI read grant lets an installed viewer open one selected file. */
class WorkspaceViewerProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        require(uri.pathSegments.size == 2)
        val (workspace, path) = uri.pathSegments
        return WorkspaceStore(requireNotNull(context)).files(workspace).existingFile(path)
    }
    override fun getType(uri: Uri): String = mime(uri.lastPathSegment.orEmpty())
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = (projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        return MatrixCursor(columns.toTypedArray()).apply {
            addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> file.length(); else -> null } })
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        private fun mime(path: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            path.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        fun open(context: Context, workspaceId: String, path: String) {
            val uri = Uri.Builder().scheme("content").authority(context.packageName + ".viewer")
                .appendPath(workspaceId).appendPath(path).build()
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(path)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "打开文件"))
        }
    }
}

package com.openclaw.car.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream

class UiResultProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String?>?, selection: String?, selectionArgs: Array<String?>?, sortOrder: String?): Cursor {
        val result = UiCommandReceiver.lastResult
        val cursor = MatrixCursor(arrayOf("result"))
        cursor.addRow(arrayOf(result))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        return try {
            val result = UiCommandReceiver.lastResult
            val pipe = ParcelFileDescriptor.createPipe()
            val out = FileOutputStream(pipe[1].fileDescriptor)
            out.write(result.toByteArray(Charsets.UTF_8))
            out.flush()
            out.close()
            pipe[1].close()
            pipe[0]
        } catch (e: Exception) {
            null
        }
    }

    override fun getType(uri: Uri) = "text/plain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String?>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String?>?) = 0
}

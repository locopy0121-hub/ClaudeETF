package com.claudeetf.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

/** 交易附件：把選取的圖片縮小後存到 App 內部（attachments/），只記檔名 */
object Attachments {
    private fun dir(context: Context) = File(context.applicationContext.filesDir, "attachments").apply { mkdirs() }

    fun file(context: Context, name: String) = File(dir(context), name)

    /** 複製並壓縮到最長邊 1600px；回傳檔名或 null */
    fun import(context: Context, uri: Uri): String? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val name = UUID.randomUUID().toString().take(12) + ".jpg"
        file(context, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        name
    }.getOrNull()

    fun load(context: Context, name: String, maxSide: Int): Bitmap? = runCatching {
        val f = file(context, name)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    fun delete(context: Context, name: String) { runCatching { file(context, name).delete() } }
}

package com.closerouter.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 把 assets 内置的电脑端 WebUI 导出到 Download/CloseRouter/ */
object ExeExporter {

    private const val EXPORT_NAME = "CloseRouter_WebUI.exe"

    fun export(context: Context): String {
        val bytes = context.assets.open("closerouter.exe").use { it.readBytes() }
        return if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            // 已存在则先删掉再重建，保证覆盖
            val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
            resolver.delete(collection, selection, arrayOf(EXPORT_NAME, "Download/CloseRouter/"))
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, EXPORT_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/CloseRouter/")
            }
            val uri = resolver.insert(collection, values)
                ?: return "导出失败：无法创建文件"
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return "导出失败：无法写入文件"
            "已导出到 Download/CloseRouter/$EXPORT_NAME"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CloseRouter")
            dir.mkdirs()
            File(dir, EXPORT_NAME).writeBytes(bytes)
            "已导出到 ${dir.absolutePath}/$EXPORT_NAME"
        }
    }
}

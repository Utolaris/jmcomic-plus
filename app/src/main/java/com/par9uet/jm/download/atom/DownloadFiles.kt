package com.par9uet.jm.download.atom

import java.io.File

class DownloadFiles(private val context: android.content.Context? = null) {
    fun delete(zipPath: String, coverPath: String) {
        if (context != null) {
            listOf(zipPath, coverPath).filter(String::isNotBlank).forEach {
                com.par9uet.jm.cache.deleteCachePath(context, it)
            }
            return
        }
        runCatching {
            val zipFile = File(zipPath)
            if (zipFile.exists()) {
                if (zipFile.isDirectory) zipFile.deleteRecursively() else zipFile.delete()
            }
        }
        val coverFile = File(coverPath)
        if (coverFile.exists()) coverFile.delete()
    }
}

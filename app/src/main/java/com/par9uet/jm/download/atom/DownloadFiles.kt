package com.par9uet.jm.download.atom

import java.io.File

class DownloadFiles {
    fun delete(zipPath: String, coverPath: String) {
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

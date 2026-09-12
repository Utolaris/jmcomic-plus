package com.par9uet.jm.download.atom

import android.content.Context
import com.par9uet.jm.cache.CachePathAccess
import com.par9uet.jm.cache.deleteCachePath
import com.par9uet.jm.cache.inspectCachePath
import java.io.File

fun interface DownloadFileRemoval {
    /** True only when both paths are absent or were successfully deleted. */
    fun delete(contentPath: String, coverPath: String): Boolean
}

class DownloadFiles(private val context: Context? = null) : DownloadFileRemoval {
    override fun delete(contentPath: String, coverPath: String): Boolean =
        listOf(contentPath, coverPath).filter(String::isNotBlank).all { path ->
            try {
                if (context != null) {
                    when (inspectCachePath(context, path)) {
                        CachePathAccess.MISSING -> true
                        CachePathAccess.INACCESSIBLE -> false
                        CachePathAccess.READABLE -> deleteCachePath(context, path)
                    }
                } else {
                    val contentFile = File(path)
                    !contentFile.exists() || if (contentFile.isDirectory) {
                        contentFile.deleteRecursively()
                    } else {
                        contentFile.delete()
                    }
                }
            } catch (_: SecurityException) {
                false
            }
        }
}

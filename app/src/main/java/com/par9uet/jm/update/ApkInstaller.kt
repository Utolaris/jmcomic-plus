package com.par9uet.jm.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

interface AppUpdateInstaller {
    fun isAvailable(savedPath: String): Boolean
    fun install(savedPath: String)
}

class ApkInstaller(private val context: Context) : AppUpdateInstaller {
    override fun isAvailable(savedPath: String): Boolean = savedPath.isNotEmpty() && File(savedPath).isFile

    override fun install(savedPath: String) {
        check(isAvailable(savedPath)) { "安装包文件不存在，请重新下载" }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", File(savedPath),
            )
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}

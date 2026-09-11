package com.par9uet.jm.cache

import android.content.Context
import com.par9uet.jm.utils.tryCreateDir
import java.io.File

private const val DOWNLOAD_TREE_PREFERENCES = "download_storage"
private const val DOWNLOAD_TREE_URI_KEY = "tree_uri"
private const val CACHE_MIGRATION_REQUEST_ID_KEY = "cache_migration_request_id"

fun getCommonCacheDir(context: Context) = tryCreateDir(File(context.cacheDir, "common"))
fun getCommonPicDecodeCacheDir(context: Context, comicId: Int) = tryCreateDir(File(context.cacheDir, "pic_decode/$comicId"))
fun getDownloadDir(context: Context) = tryCreateDir(File(context.cacheDir, "download"))

fun getDownloadTreeUri(context: Context): android.net.Uri? =
    context.getSharedPreferences(DOWNLOAD_TREE_PREFERENCES, Context.MODE_PRIVATE)
        .getString(DOWNLOAD_TREE_URI_KEY, null)
        ?.takeIf { it.isNotBlank() }
        ?.let(android.net.Uri::parse)

fun setDownloadTreeUri(context: Context, uri: String) {
    context.getSharedPreferences(DOWNLOAD_TREE_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(DOWNLOAD_TREE_URI_KEY, uri)
        .apply()
}

/**
 * 最后一次提交的迁移任务 id。WorkManager 不保证同名任务的顺序，只有记下这个 id 才能在
 * 进程重启之后认出"这次"的结果，而不是从历史记录里随便挑一条当结果。
 */
fun getCacheMigrationRequestId(context: Context): String? =
    context.getSharedPreferences(DOWNLOAD_TREE_PREFERENCES, Context.MODE_PRIVATE)
        .getString(CACHE_MIGRATION_REQUEST_ID_KEY, null)

fun setCacheMigrationRequestId(context: Context, requestId: String) {
    context.getSharedPreferences(DOWNLOAD_TREE_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(CACHE_MIGRATION_REQUEST_ID_KEY, requestId)
        .apply()
}

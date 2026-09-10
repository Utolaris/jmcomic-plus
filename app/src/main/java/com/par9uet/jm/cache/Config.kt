package com.par9uet.jm.cache

import android.content.Context
import com.par9uet.jm.utils.tryCreateDir
import java.io.File

private const val DOWNLOAD_TREE_PREFERENCES = "download_storage"
private const val DOWNLOAD_TREE_URI_KEY = "tree_uri"

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

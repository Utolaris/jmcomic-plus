package com.par9uet.jm.update

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val GITHUB_RELEASE_API = "https://api.github.com/repos/Utolaris/jmcomic-plus/releases/latest"
private const val GITHUB_RELEASE_URL = "https://github.com/Utolaris/jmcomic-plus/releases"

fun interface ReleaseSource {
    suspend fun latest(): GithubRelease
}

class GithubReleaseSource(private val client: OkHttpClient = OkHttpClient()) : ReleaseSource {
    override suspend fun latest(): GithubRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(GITHUB_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "jmcomic-plus-android")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub 返回 ${response.code}" }
            parseGithubRelease(response.body?.string() ?: error("GitHub 返回空响应"))
        }
    }
}

internal fun parseGithubRelease(body: String): GithubRelease {
    val json = JsonParser.parseString(body).asJsonObject
    val tagName = json.stringOrEmpty("tag_name")
    val name = json.stringOrEmpty("name")
    val url = json.stringOrEmpty("html_url")
    val version = normalizeVersion(tagName.ifBlank { name })
    if (version.isBlank()) {
        error("未读取到 Release 版本号")
    }
    val asset = selectApkAsset(json.getAsJsonArray("assets"), version)
        ?: error("当前 Release 未提供兼容的 APK 安装包")
    return GithubRelease(
        version = version,
        name = name,
        url = url.ifBlank { "$GITHUB_RELEASE_URL/tag/$tagName" },
        body = json.stringOrEmpty("body"),
        downloadUrl = asset.downloadUrl,
        fileName = asset.name
    )
}

private data class ReleaseAsset(val name: String, val downloadUrl: String)

private fun selectApkAsset(assets: JsonArray?, version: String): ReleaseAsset? {
    if (assets == null) return null
    val apkAssets = assets.mapNotNull { item ->
        val obj = item.asJsonObject
        val name = obj.stringOrEmpty("name")
        val url = obj.stringOrEmpty("browser_download_url")
        if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
            ReleaseAsset(name, url)
        } else {
            null
        }
    }
    return apkAssets.firstOrNull {
        it.name.contains("jm-mobile_v$version", ignoreCase = true)
    } ?: apkAssets.firstOrNull()
}

private fun JsonObject.stringOrEmpty(key: String): String {
    return get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
}


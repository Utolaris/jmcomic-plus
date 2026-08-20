package com.par9uet.jm.reader

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Stable identity for one page in one chapter response. */
data class ReaderPageKey(
    val comicId: Int,
    val pageIndex: Int,
    val sourceIdentity: String,
    val scrambleId: Int,
    val speed: String,
) {
    fun stableIdentity(): String = buildString {
        append(comicId)
        append('|')
        append(pageIndex)
        append('|')
        append(readerLogicalSourceIdentity(sourceIdentity))
        append('|')
        append(scrambleId)
        append('|')
        append(speed)
    }
}

/** Mirror hosts share one logical source identity, preventing CDN switching from duplicating cache. */
internal fun readerLogicalSourceIdentity(sourceIdentity: String): String {
    val url = sourceIdentity.toHttpUrlOrNull() ?: return sourceIdentity
    if (
        url.scheme != "https" ||
        url.host !in READER_IMAGE_MIRROR_HOSTS
    ) return sourceIdentity
    // Query parameters on JM image URLs are cache-busters; the immutable page path is the
    // content identity, while the original URL still retains the query for the network request.
    return "image-mirror|${url.encodedPath}"
}

private val READER_IMAGE_MIRROR_HOSTS = setOf(
    "cdn-msp.jmapiproxy1.cc",
    "cdn-msp.jmapiproxy2.cc",
    "cdn-msp2.jmapiproxy2.cc",
    "cdn-msp3.jmapiproxy2.cc",
    "cdn-msp.jmapinodeudzn.net",
    "cdn-msp3.jmapinodeudzn.net",
)

/**
 * A reader page deliberately contains metadata and source callbacks only. It does not own a
 * decoded Bitmap; the pipeline owns the bounded cache and the composable owns only its visible
 * ImageBitmap while the item is composed.
 */
data class ReaderPage(
    val key: ReaderPageKey,
    val originSrc: String,
    val comicId: Int,
    val pageIndex: Int,
    val scrambleId: Int,
    val speed: String,
    val localFile: File?,
    val fallbackFetcher: (suspend () -> ByteArray?)? = null,
) {
    val isGif: Boolean
        get() = originSrc.substringBefore('?').endsWith(".gif", ignoreCase = true)
}

enum class ReaderRequestPriority {
    PREFETCH,
    BACKGROUND,
    VISIBLE,
}

/** A page may be decoded concurrently for the reader and for a full-quality download. */
internal data class ReaderInFlightKey(
    val page: ReaderPageKey,
    val profileToken: String,
)

/** Decode profiles are part of the decoded-cache identity, so quality changes never reuse the
 * wrong representation. */
data class ReaderDecodeProfile(
    val name: String,
    val maxWidth: Int,
    val maxPixels: Long,
    val webpQuality: Int,
    val useRgb565: Boolean,
) {
    val cacheToken: String
        get() = "$name-$maxWidth-$maxPixels-${if (useRgb565) "565" else "8888"}"

    companion object {
        val HIGH = ReaderDecodeProfile(
            name = "high",
            maxWidth = 1_440,
            maxPixels = 12_000_000L,
            webpQuality = 92,
            useRgb565 = false,
        )
        val LOW = ReaderDecodeProfile(
            name = "low",
            maxWidth = 900,
            maxPixels = 4_000_000L,
            webpQuality = 86,
            useRgb565 = true,
        )
        val DOWNLOAD = ReaderDecodeProfile(
            name = "download",
            maxWidth = 4_096,
            maxPixels = 40_000_000L,
            webpQuality = 92,
            useRgb565 = false,
        )
    }
}

internal fun readerCacheKey(
    page: ReaderPageKey,
    kind: String,
    profile: ReaderDecodeProfile? = null,
): String {
    val profileToken = profile?.cacheToken ?: "source"
    val namespace = if (kind == "decoded") "reader-v3" else "reader-v2"
    val value = "$namespace|$kind|$profileToken|${page.stableIdentity()}"
    return sha256(value)
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

package com.par9uet.jm.image

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Static JM image-mirror catalog shared by Reader routing and Coil cover fallback. */
internal val JM_IMAGE_HOSTS = listOf(
    "cdn-msp.jmapiproxy1.cc",
    "cdn-msp.jmapiproxy2.cc",
    "cdn-msp2.jmapiproxy2.cc",
    "cdn-msp3.jmapiproxy2.cc",
    "cdn-msp.jmapinodeudzn.net",
    "cdn-msp3.jmapinodeudzn.net",
)

internal fun normalizeJmImageHost(raw: String?): String? {
    val value = raw?.trim()?.trimEnd('/').orEmpty()
    if (value.isBlank()) return null
    val parsed = (if (value.contains("://")) value else "https://$value").toHttpUrlOrNull()
        ?: return null
    if (
        parsed.scheme != "https" ||
        parsed.port != 443 ||
        parsed.username.isNotEmpty() ||
        parsed.password.isNotEmpty()
    ) {
        return null
    }
    val host = parsed.host.lowercase()
    if (
        host.length > 253 ||
        host.contains("..") ||
        host.any { it == '/' || it == '?' || it == '#' } ||
        host.split('.').any { label ->
            label.isEmpty() || label.length > 63 ||
                label.first().let { !it.isLetterOrDigit() } ||
                label.last().let { !it.isLetterOrDigit() } ||
                label.any { character -> !character.isLetterOrDigit() && character != '-' }
        }
    ) {
        return null
    }
    return host
}

internal fun isJmImagePathAllowed(path: String): Boolean =
    path.startsWith("/media/photos/") || path.startsWith("/media/albums/")

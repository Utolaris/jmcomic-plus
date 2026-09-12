package com.par9uet.jm.network

import okhttp3.Cookie
import okhttp3.HttpUrl

/** AVS may follow the SDK's trusted API domain rotation; no other cookie crosses domains. */
internal fun embeddedCookiesForRequest(
    stored: List<Cookie>,
    url: HttpUrl,
    trustedDomains: Collection<String>,
    now: Long = System.currentTimeMillis(),
): List<Cookie> {
    if (!url.isHttps || url.host !in trustedDomains) return emptyList()
    val eligible = stored.filter { cookie ->
        cookie.expiresAt > now && (cookie.matches(url) || (
            cookie.name == "AVS" && cookie.domain in trustedDomains &&
                Cookie.Builder().name(cookie.name).value(cookie.value)
                    .hostOnlyDomain(url.host).path(cookie.path).secure().build().matches(url)
            ))
    }
    val avs = eligible.firstOrNull { it.name == "AVS" && it.matches(url) }
        ?: eligible.firstOrNull { it.name == "AVS" }
    return eligible.filterNot { it.name == "AVS" } + listOfNotNull(avs)
}

/** Merge Set-Cookie changes instead of trusting SDK getCookies(), which is scoped to loginHost. */
internal fun mergeEmbeddedCookies(
    stored: List<Cookie>,
    received: List<Cookie>,
    now: Long = System.currentTimeMillis(),
): List<Cookie> = (stored + received)
    .associateBy { Triple(it.name, it.domain, it.path) }.values
    .filter { it.expiresAt > now }

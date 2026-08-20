package com.par9uet.jm.coil

import com.par9uet.jm.image.JM_IMAGE_HOSTS
import com.par9uet.jm.image.normalizeJmImageHost
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lightweight application-wide routing state for sequential Coil cover fallback. */
class CoverImageHostResolver internal constructor(
    private val knownHosts: List<String> = JM_IMAGE_HOSTS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val failureCooldownMillis: Long = DEFAULT_FAILURE_COOLDOWN_MILLIS,
    private val maxCandidates: Int = MAX_COVER_CANDIDATES,
) {
    private val failedAtMillis = ConcurrentHashMap<String, Long>()
    private val _preferredHost = MutableStateFlow<String?>(null)

    val preferredHost = _preferredHost.asStateFlow()

    fun coverUrls(comicId: Int, remoteHost: String?): List<String> = candidateHosts(remoteHost)
        .map { host -> buildJmCoverUrl(host, comicId) }

    @Synchronized
    fun recordSuccess(candidateUrl: String) {
        val host = normalizeJmImageHost(candidateUrl) ?: return
        failedAtMillis.remove(host)
        _preferredHost.value = host
    }

    @Synchronized
    fun recordFailure(candidateUrl: String) {
        val host = normalizeJmImageHost(candidateUrl) ?: return
        failedAtMillis[host] = clockMillis()
        if (_preferredHost.value == host) _preferredHost.value = null
    }

    internal fun candidateHosts(remoteHost: String?): List<String> {
        val now = clockMillis()
        val base = buildList {
            _preferredHost.value?.let(::add)
            normalizeJmImageHost(remoteHost)?.let(::add)
            knownHosts.mapNotNullTo(this, ::normalizeJmImageHost)
        }.distinct().take(maxCandidates.coerceAtLeast(1))

        val available = ArrayList<String>(base.size)
        val cooling = ArrayList<String>(base.size)
        base.forEach { host ->
            val failedAt = failedAtMillis[host]
            if (
                failedAt != null &&
                now - failedAt in 0 until failureCooldownMillis
            ) {
                cooling += host
            } else {
                if (failedAt != null) failedAtMillis.remove(host, failedAt)
                available += host
            }
        }
        return available + cooling
    }

    companion object {
        private const val DEFAULT_FAILURE_COOLDOWN_MILLIS = 90_000L
        private const val MAX_COVER_CANDIDATES = 8
    }
}

internal fun buildJmCoverUrl(host: String, comicId: Int): String =
    "https://$host/media/albums/${comicId}_3x4.jpg"

internal fun jmCoverCacheKey(comicId: Int): String = "jm-cover-$comicId"

internal fun nextCoverCandidateUrl(
    candidates: List<String>,
    attemptedUrls: Set<String>,
): String? = candidates.firstOrNull { it !in attemptedUrls }

package com.par9uet.jm.coil

import com.par9uet.jm.image.JM_IMAGE_HOSTS
import com.par9uet.jm.image.JM_IMAGE_HOST_COOLDOWN_MILLIS
import com.par9uet.jm.image.JmImageHostHealth
import com.par9uet.jm.image.JmImageHostHealthStore
import com.par9uet.jm.image.normalizeJmImageHost

/** Sequential Coil cover fallback backed by the same ranked health state as Reader. */
class CoverImageHostResolver internal constructor(
    private val hostHealth: JmImageHostHealth,
    private val maxCandidates: Int = MAX_COVER_CANDIDATES,
) {
    internal constructor(
        knownHosts: List<String> = JM_IMAGE_HOSTS,
        clockMillis: () -> Long = System::currentTimeMillis,
        failureCooldownMillis: Long = JM_IMAGE_HOST_COOLDOWN_MILLIS,
        maxCandidates: Int = MAX_COVER_CANDIDATES,
    ) : this(
        hostHealth = JmImageHostHealthStore(
            knownHosts = knownHosts,
            clockMillis = clockMillis,
            cooldownMillis = failureCooldownMillis,
            maxHosts = maxCandidates,
        ),
        maxCandidates = maxCandidates,
    )

    val preferredHost = hostHealth.preferredHost
    val networkGeneration = hostHealth.networkGeneration

    fun coverUrls(comicId: Int, remoteHost: String?): List<String> = candidateHosts(remoteHost)
        .map { host -> buildJmCoverUrl(host, comicId) }

    fun recordSuccess(candidateUrl: String, elapsedMillis: Long) {
        hostHealth.recordSuccess(candidateUrl, elapsedMillis)
    }

    fun recordFailure(candidateUrl: String) {
        hostHealth.recordFailure(candidateUrl)
    }

    internal fun candidateHosts(remoteHost: String?): List<String> {
        hostHealth.registerHost(remoteHost)
        return hostHealth.orderedHosts(normalizeJmImageHost(remoteHost))
            .take(maxCandidates.coerceAtLeast(1))
    }

    private companion object {
        private const val MAX_COVER_CANDIDATES = 8
    }
}

internal fun buildJmCoverUrl(host: String, comicId: Int): String =
    "https://$host/media/albums/${comicId}_3x4.jpg"

internal fun jmCoverCacheKey(comicId: Int): String = "jm-cover-$comicId"

/** Returns one next candidate; the caller starts no parallel mirror request. */
internal fun nextCoverCandidateUrl(
    candidates: List<String>,
    attemptedUrls: Set<String>,
): String? = candidates.firstOrNull { it !in attemptedUrls }

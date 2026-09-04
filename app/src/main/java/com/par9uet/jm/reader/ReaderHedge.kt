package com.par9uet.jm.reader

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal data class ReaderVisibleSourcePolicy(
    val urls: List<String>,
    val hedgeEnabled: Boolean,
)

/** Current-page loads use no more than two ranked sources. Slow networks stay sequential. */
internal fun readerVisibleSourcePolicy(
    orderedUrls: List<String>,
    fastestKnownLatencyMillis: Long?,
): ReaderVisibleSourcePolicy {
    val urls = orderedUrls.distinct().take(MAX_VISIBLE_SOURCE_ATTEMPTS)
    val degraded = fastestKnownLatencyMillis != null &&
        fastestKnownLatencyMillis >= DEGRADED_HEDGE_LATENCY_MILLIS
    return ReaderVisibleSourcePolicy(
        urls = urls,
        hedgeEnabled = urls.size >= 2 && !degraded,
    )
}

internal suspend fun <T> withReaderVisibleLoadDeadline(
    timeoutMillis: Long,
    load: suspend () -> T,
): T = try {
    withTimeout(timeoutMillis.coerceAtLeast(1L)) { load() }
} catch (error: TimeoutCancellationException) {
    throw ReaderImageException("图片加载超时，请重试", error)
}

internal const val MAX_VISIBLE_SOURCE_ATTEMPTS = 2
private const val DEGRADED_HEDGE_LATENCY_MILLIS = 450L

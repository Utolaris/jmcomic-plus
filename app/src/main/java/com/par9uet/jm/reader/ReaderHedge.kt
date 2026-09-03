package com.par9uet.jm.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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

/** Delayed two-candidate race used only by foreground source loads. */
internal suspend fun <T> delayedHedge(
    delayMillis: Long,
    primaryAttempt: suspend () -> Result<T>,
    secondaryAttempt: suspend () -> Result<T>,
    onWinner: (primary: Boolean) -> Unit = {},
    onLoserCanceled: () -> Unit = {},
    onDiscardedLoser: suspend (T) -> Unit = {},
): T = supervisorScope {
    val primary = async(start = CoroutineStart.DEFAULT) { primaryAttempt() }
    val earlyPrimary = withTimeoutOrNull(delayMillis.coerceAtLeast(0L)) { primary.await() }
    if (earlyPrimary != null) {
        earlyPrimary.getOrNull()?.let { winner ->
            onWinner(true)
            return@supervisorScope winner
        }
        val secondaryResult = secondaryAttempt()
        secondaryResult.getOrNull()?.let { winner ->
            onWinner(false)
            return@supervisorScope winner
        }
        throw secondaryResult.exceptionOrNull()
            ?: earlyPrimary.exceptionOrNull()
            ?: IllegalStateException("hedged attempts failed")
    }

    val secondary = async(start = CoroutineStart.DEFAULT) { secondaryAttempt() }
    val (firstResult, other) = select<Pair<Result<T>, Deferred<Result<T>>>> {
        primary.onAwait { result -> result to secondary }
        secondary.onAwait { result -> result to primary }
    }
    firstResult.getOrNull()?.let { winner ->
        onWinner(other === secondary)
        val loserWasActive = !other.isCompleted
        other.cancel()
        if (loserWasActive) onLoserCanceled()
        val discarded = try {
            other.await()
        } catch (_: CancellationException) {
            null
        }
        discarded?.getOrNull()?.let { onDiscardedLoser(it) }
        return@supervisorScope winner
    }
    val secondResult = other.await()
    secondResult.getOrNull()?.let { winner ->
        onWinner(other === primary)
        return@supervisorScope winner
    }
    throw secondResult.exceptionOrNull()
        ?: firstResult.exceptionOrNull()
        ?: IllegalStateException("hedged attempts failed")
}

internal const val MAX_VISIBLE_SOURCE_ATTEMPTS = 2
private const val DEGRADED_HEDGE_LATENCY_MILLIS = 450L

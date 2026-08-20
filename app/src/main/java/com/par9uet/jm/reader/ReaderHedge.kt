package com.par9uet.jm.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

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

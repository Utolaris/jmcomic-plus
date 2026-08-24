package com.par9uet.jm.reader

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal data class ReaderPrefetchPolicy(
    val distance: Int,
    val sourceOnly: Boolean,
    val parallelism: Int,
)

/** Bounded adaptive policy: memory and network conditions can reduce work, never make it unbounded. */
internal fun readerAdaptivePrefetchPolicy(
    configuredDistance: Int,
    memoryClassMb: Int,
    lowRamDevice: Boolean,
    singleNetworkSlot: Boolean,
    jumpDistance: Int,
    directionStreak: Int,
    pageVelocity: Float,
    networkLatencyMillis: Long?,
    turboMode: Boolean,
): ReaderPrefetchPolicy {
    val base = configuredDistance.coerceIn(0, MAX_PREFETCH_DISTANCE)
    if (base == 0) return ReaderPrefetchPolicy(0, singleNetworkSlot, 1)

    val memoryCap = when {
        lowRamDevice || memoryClassMb < 384 -> 2
        memoryClassMb < 512 -> 4
        else -> MAX_PREFETCH_DISTANCE
    }
    val speedBonus = when {
        pageVelocity >= 3.0f -> 3
        pageVelocity >= 1.5f -> 2
        directionStreak >= 3 -> 1
        else -> 0
    }
    val latencyBonus = if (networkLatencyMillis != null && networkLatencyMillis >= 450L) 1 else 0
    val jumpBonus = if (jumpDistance >= 4) 2 else 0
    val requested = (base + speedBonus + latencyBonus + jumpBonus)
        .coerceAtMost(MAX_PREFETCH_DISTANCE)
        .coerceAtMost(memoryCap)
    val parallelism = when {
        singleNetworkSlot -> 1
        turboMode && memoryClassMb >= 512 -> 2
        memoryClassMb >= 512 && directionStreak >= 2 -> 2
        else -> 1
    }
    return ReaderPrefetchPolicy(
        distance = requested,
        sourceOnly = singleNetworkSlot,
        parallelism = parallelism,
    )
}

/** Runs at most [parallelism] workers; planned pages never become one coroutine per page. */
internal suspend fun <T> runReaderPrefetchSchedule(
    items: List<T>,
    parallelism: Int,
    load: suspend (T) -> Unit,
) = coroutineScope {
    if (items.isEmpty()) return@coroutineScope
    val nextIndex = AtomicInteger()
    repeat(parallelism.coerceIn(1, items.size)) {
        launch {
            while (true) {
                val index = nextIndex.getAndIncrement()
                if (index >= items.size) break
                load(items[index])
            }
        }
    }
}

private const val MAX_PREFETCH_DISTANCE = 12

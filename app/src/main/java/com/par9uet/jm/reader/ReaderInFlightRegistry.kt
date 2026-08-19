package com.par9uet.jm.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** View of one shared load, used by network and decode gates to observe promotion. */
internal class ReaderLoadHandle internal constructor(
    private val priorityRef: AtomicReference<ReaderRequestPriority>,
    private val visibleConsumers: AtomicInteger,
    private val visibleRequests: AtomicInteger,
) {
    val priority: ReaderRequestPriority
        get() = priorityRef.get()

    val isVisible: Boolean
        get() = visibleConsumers.get() > 0

    val hasVisibleRequest: Boolean
        get() = visibleRequests.get() > 0

    suspend fun awaitVisible() {
        while (!isVisible) delay(24L)
    }

    suspend fun awaitBackgroundTurn() {
        while (!isVisible && hasVisibleRequest) delay(24L)
    }
}

/**
 * Shared deferred registry for page loads. A prefetch and a visible consumer use the same
 * Deferred; a visible consumer only promotes the existing entry instead of restarting it.
 */
internal class ReaderInFlightRegistry<K, V>(
    private val scope: CoroutineScope,
    private val cancellationGraceMillis: Long = 750L,
) {
    private class Entry<V>(
        val deferred: Deferred<V>,
        val priorityRef: AtomicReference<ReaderRequestPriority>,
        val visibleConsumers: AtomicInteger,
        val prefetchConsumers: AtomicInteger,
    )

    private val entries = ConcurrentHashMap<K, Entry<V>>()
    private val visibleRequests = AtomicInteger()

    suspend fun request(
        key: K,
        priority: ReaderRequestPriority,
        loader: suspend (ReaderLoadHandle) -> V,
    ): V {
        val created = createEntry(key, loader)
        val existing = entries.putIfAbsent(key, created)
        val selected = existing ?: created
        if (existing != null) created.deferred.cancel()

        retain(selected, priority)
        selected.deferred.start()
        return try {
            selected.deferred.await()
        } finally {
            release(key, selected, priority)
        }
    }

    fun priority(key: K): ReaderRequestPriority? = entries[key]?.priorityRef?.get()

    fun isVisible(key: K): Boolean = entries[key]?.visibleConsumers?.get()?.let { it > 0 } == true

    fun inFlightCount(): Int = entries.size

    fun cancelPrefetch(key: K): Boolean {
        val entry = entries[key] ?: return false
        if (entry.visibleConsumers.get() > 0) return false
        entries.remove(key, entry)
        entry.deferred.cancel()
        return true
    }

    fun cancelAllPrefetch() {
        entries.entries.toList().forEach { (key, entry) ->
            if (entry.visibleConsumers.get() <= 0 && entries.remove(key, entry)) {
                entry.deferred.cancel()
            }
        }
    }

    private fun createEntry(
        key: K,
        loader: suspend (ReaderLoadHandle) -> V,
    ): Entry<V> {
        val priorityRef = AtomicReference(ReaderRequestPriority.PREFETCH)
        val visibleConsumers = AtomicInteger()
        val prefetchConsumers = AtomicInteger()
        val handle = ReaderLoadHandle(priorityRef, visibleConsumers, visibleRequests)
        lateinit var entry: Entry<V>
        val deferred = scope.async(start = CoroutineStart.LAZY) { loader(handle) }
        entry = Entry(deferred, priorityRef, visibleConsumers, prefetchConsumers)
        deferred.invokeOnCompletion { entries.remove(key, entry) }
        return entry
    }

    private fun retain(entry: Entry<V>, priority: ReaderRequestPriority) {
        if (priority == ReaderRequestPriority.VISIBLE) {
            entry.visibleConsumers.incrementAndGet()
            visibleRequests.incrementAndGet()
            entry.priorityRef.set(ReaderRequestPriority.VISIBLE)
        } else {
            entry.prefetchConsumers.incrementAndGet()
        }
    }

    private fun release(key: K, entry: Entry<V>, priority: ReaderRequestPriority) {
        if (priority == ReaderRequestPriority.VISIBLE) {
            if (entry.visibleConsumers.decrementAndGet() <= 0) {
                visibleRequests.decrementAndGet()
                if (entry.prefetchConsumers.get() > 0) {
                    entry.priorityRef.set(ReaderRequestPriority.PREFETCH)
                }
            }
        } else {
            entry.prefetchConsumers.decrementAndGet()
        }

        if (
            entry.visibleConsumers.get() <= 0 &&
            entry.prefetchConsumers.get() <= 0 &&
            !entry.deferred.isCompleted
        ) {
            scope.launchCancellationCheck(key, entry, cancellationGraceMillis)
        }
    }

    private fun CoroutineScope.launchCancellationCheck(
        key: K,
        entry: Entry<V>,
        graceMillis: Long,
    ) {
        launch {
            delay(graceMillis.coerceAtLeast(0L))
            if (
                entries[key] === entry &&
                entry.visibleConsumers.get() <= 0 &&
                entry.prefetchConsumers.get() <= 0 &&
                !entry.deferred.isCompleted
            ) {
                entry.deferred.cancel()
            }
        }
    }
}

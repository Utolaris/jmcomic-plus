package com.par9uet.jm.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class ReaderVisibleRequestTracker {
    private val count = AtomicInteger()

    val hasVisibleRequest: Boolean
        get() = count.get() > 0

    fun retain() { count.incrementAndGet() }
    fun release() { count.decrementAndGet() }
    fun count(): Int = count.get().coerceAtLeast(0)
}

/** View of one shared load, used by network and decode gates to observe promotion. */
internal class ReaderLoadHandle internal constructor(
    private val priorityRef: AtomicReference<ReaderRequestPriority>,
    private val visibleConsumers: AtomicInteger,
    private val visibleRequests: ReaderVisibleRequestTracker,
) {
    val priority: ReaderRequestPriority
        get() = priorityRef.get()

    val isVisible: Boolean
        get() = visibleConsumers.get() > 0

    val hasVisibleRequest: Boolean
        get() = visibleRequests.hasVisibleRequest

    suspend fun awaitVisible() {
        while (!isVisible) delay(24L)
    }

    suspend fun awaitBackgroundTurn() {
        while (!isVisible && hasVisibleRequest) delay(24L)
    }
}

internal class ReaderInFlightLease<V> internal constructor(
    val value: V,
    private val releaseAction: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

/**
 * Shared deferred registry for page and source loads. A visible consumer only promotes the
 * existing entry instead of restarting it. Completed entries remain shareable until their last
 * consumer releases, which lets a source file be shared by two decode profiles during decode.
 */
internal class ReaderInFlightRegistry<K, V>(
    private val scope: CoroutineScope,
    private val cancellationGraceMillis: Long = 750L,
    private val onEntryReleased: (V) -> Unit = {},
    private val visibleRequestTracker: ReaderVisibleRequestTracker = ReaderVisibleRequestTracker(),
) {
    private class Entry<V>(
        val deferred: Deferred<V>,
        val priorityRef: AtomicReference<ReaderRequestPriority>,
        val visibleConsumers: AtomicInteger,
        val prefetchConsumers: AtomicInteger,
        val backgroundConsumers: AtomicInteger,
        val valueRef: AtomicReference<V?>,
        val cleanupStarted: AtomicBoolean,
    )

    private val entries = ConcurrentHashMap<K, Entry<V>>()
    suspend fun request(
        key: K,
        priority: ReaderRequestPriority,
        loader: suspend (ReaderLoadHandle) -> V,
    ): V {
        val lease = acquire(key, priority, loader)
        return try {
            lease.value
        } finally {
            lease.release()
        }
    }

    suspend fun acquire(
        key: K,
        priority: ReaderRequestPriority,
        loader: suspend (ReaderLoadHandle) -> V,
    ): ReaderInFlightLease<V> {
        val created = createEntry(key, loader)
        val existing = entries.putIfAbsent(key, created)
        val selected = existing ?: created
        if (existing != null) created.deferred.cancel()

        retain(selected, priority)
        selected.deferred.start()
        return try {
            val value = selected.deferred.await()
            ReaderInFlightLease(value) {
                release(key, selected, priority)
            }
        } catch (error: Throwable) {
            release(key, selected, priority)
            throw error
        }
    }

    fun priority(key: K): ReaderRequestPriority? = entries[key]?.priorityRef?.get()

    fun isVisible(key: K): Boolean = entries[key]?.visibleConsumers?.get()?.let { it > 0 } == true

    fun inFlightCount(): Int = entries.size

    fun invalidate(key: K): Boolean {
        val entry = entries.remove(key) ?: return false
        entry.deferred.cancel()
        return true
    }

    fun cancelPrefetch(key: K): Boolean {
        val entry = entries[key] ?: return false
        if (
            entry.visibleConsumers.get() > 0 ||
            entry.prefetchConsumers.get() <= 0 ||
            entry.backgroundConsumers.get() > 0
        ) return false
        entries.remove(key, entry)
        entry.deferred.cancel()
        return true
    }

    fun cancelPrefetchMatching(predicate: (K) -> Boolean): Int {
        var cancelled = 0
        entries.entries.toList().forEach { (key, entry) ->
            if (
                predicate(key) &&
                entry.visibleConsumers.get() <= 0 &&
                entry.prefetchConsumers.get() > 0 &&
                entry.backgroundConsumers.get() <= 0 &&
                entries.remove(key, entry)
            ) {
                entry.deferred.cancel()
                cancelled++
            }
        }
        return cancelled
    }

    fun cancelAllPrefetch() {
        entries.entries.toList().forEach { (key, entry) ->
            if (
                entry.visibleConsumers.get() <= 0 &&
                entry.prefetchConsumers.get() > 0 &&
                entry.backgroundConsumers.get() <= 0 &&
                entries.remove(key, entry)
            ) {
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
        val backgroundConsumers = AtomicInteger()
        val valueRef = AtomicReference<V?>(null)
        val cleanupStarted = AtomicBoolean(false)
        val handle = ReaderLoadHandle(priorityRef, visibleConsumers, visibleRequestTracker)
        lateinit var entry: Entry<V>
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            loader(handle).also { valueRef.set(it) }
        }
        entry = Entry(
            deferred,
            priorityRef,
            visibleConsumers,
            prefetchConsumers,
            backgroundConsumers,
            valueRef,
            cleanupStarted,
        )
        deferred.invokeOnCompletion { maybeFinalize(key, entry) }
        return entry
    }

    private fun retain(entry: Entry<V>, priority: ReaderRequestPriority) {
        if (priority == ReaderRequestPriority.VISIBLE) {
            entry.visibleConsumers.incrementAndGet()
            visibleRequestsTracker().retain()
            entry.priorityRef.set(ReaderRequestPriority.VISIBLE)
            return
        }

        when (priority) {
            ReaderRequestPriority.PREFETCH -> entry.prefetchConsumers.incrementAndGet()
            ReaderRequestPriority.BACKGROUND -> entry.backgroundConsumers.incrementAndGet()
            ReaderRequestPriority.VISIBLE -> error("visible handled above")
        }
        if (entry.visibleConsumers.get() <= 0) {
            entry.priorityRef.set(priority)
        }
    }

    private fun release(key: K, entry: Entry<V>, priority: ReaderRequestPriority) {
        if (priority == ReaderRequestPriority.VISIBLE) {
            entry.visibleConsumers.decrementAndGet()
            // Count every visible consumer, not only the last consumer of a page.
            visibleRequestsTracker().release()
            if (entry.visibleConsumers.get() <= 0) {
                entry.priorityRef.set(
                    when {
                        entry.backgroundConsumers.get() > 0 -> ReaderRequestPriority.BACKGROUND
                        entry.prefetchConsumers.get() > 0 -> ReaderRequestPriority.PREFETCH
                        else -> ReaderRequestPriority.PREFETCH
                    }
                )
            }
        } else {
            when (priority) {
                ReaderRequestPriority.PREFETCH -> entry.prefetchConsumers.decrementAndGet()
                ReaderRequestPriority.BACKGROUND -> entry.backgroundConsumers.decrementAndGet()
                ReaderRequestPriority.VISIBLE -> error("visible handled above")
            }
        }

        if (
            entry.visibleConsumers.get() <= 0 &&
            entry.prefetchConsumers.get() <= 0 &&
            entry.backgroundConsumers.get() <= 0 &&
            !entry.deferred.isCompleted
        ) {
            scope.launchCancellationCheck(key, entry, cancellationGraceMillis)
        }
        maybeFinalize(key, entry)
    }

    private fun visibleRequestsTracker(): ReaderVisibleRequestTracker = visibleRequestTracker

    private fun maybeFinalize(key: K, entry: Entry<V>) {
        if (
            !entry.deferred.isCompleted ||
            entry.visibleConsumers.get() > 0 ||
            entry.prefetchConsumers.get() > 0 ||
            entry.backgroundConsumers.get() > 0
        ) return
        entries.remove(key, entry)
        if (entry.cleanupStarted.compareAndSet(false, true)) {
            entry.valueRef.get()?.let { value ->
                runCatching { onEntryReleased(value) }
            }
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
                entry.backgroundConsumers.get() <= 0 &&
                !entry.deferred.isCompleted
            ) {
                entry.deferred.cancel()
            }
        }
    }
}

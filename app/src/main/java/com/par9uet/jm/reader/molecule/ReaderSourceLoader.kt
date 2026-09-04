package com.par9uet.jm.reader.molecule

import android.os.SystemClock
import com.par9uet.jm.reader.MAX_VISIBLE_SOURCE_ATTEMPTS
import com.par9uet.jm.reader.READER_MAX_SOURCE_BYTES
import com.par9uet.jm.reader.ReaderBackgroundPreempted
import com.par9uet.jm.reader.ReaderImageException
import com.par9uet.jm.reader.ReaderImageHostManager
import com.par9uet.jm.reader.ReaderInFlightLease
import com.par9uet.jm.reader.ReaderInFlightRegistry
import com.par9uet.jm.reader.ReaderLoadHandle
import com.par9uet.jm.reader.ReaderMetrics
import com.par9uet.jm.reader.ReaderNetworkPermit
import com.par9uet.jm.reader.ReaderNetworkScheduler
import com.par9uet.jm.reader.ReaderPage
import com.par9uet.jm.reader.ReaderPageKey
import com.par9uet.jm.reader.ReaderRemoteAttempt
import com.par9uet.jm.reader.ReaderRemoteFetcher
import com.par9uet.jm.reader.ReaderRemoteObserver
import com.par9uet.jm.reader.ReaderRequestPriority
import com.par9uet.jm.reader.ReaderVisibleRequestTracker
import com.par9uet.jm.reader.atom.ReaderImageDiskCache
import com.par9uet.jm.reader.atom.ReaderSourceFile
import com.par9uet.jm.reader.readerCacheKey
import com.par9uet.jm.reader.readerVisibleSourcePolicy
import com.par9uet.jm.reader.validateReaderSourceFile
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** L3 source molecule: cache lookup, CDN policy, remote fetch and repository fallback. */
internal class ReaderSourceLease internal constructor(
    internal val source: ReaderSourceFile,
    private val lease: ReaderInFlightLease<ReaderSourceFile>,
) {
    val file: File
        get() = source.file

    fun release() = lease.release()
}

internal class ReaderSourceLoader(
    scope: CoroutineScope,
    visibleRequestTracker: ReaderVisibleRequestTracker,
    private val diskCache: ReaderImageDiskCache,
    private val networkScheduler: ReaderNetworkScheduler,
    private val imageHostManager: ReaderImageHostManager,
    private val metrics: ReaderMetrics,
    httpClient: OkHttpClient,
    remoteObserver: ReaderRemoteObserver,
) {
    private val sourceRequests = ReaderInFlightRegistry<String, ReaderSourceFile>(
        scope = scope,
        visibleRequestTracker = visibleRequestTracker,
        onEntryReleased = diskCache::releaseSource,
    )
    private val remoteFetcher = ReaderRemoteFetcher(
        client = httpClient,
        createTemporary = diskCache::createSourceTempFile,
        discardTemporary = diskCache::discardTemporary,
        validateTemporary = { file -> validateReaderSourceFile(file) },
        maxSourceBytes = READER_MAX_SOURCE_BYTES,
        readChunkBytes = NETWORK_READ_CHUNK_BYTES,
        observer = remoteObserver,
    )

    suspend fun prefetchSource(page: ReaderPage) {
        withContext(Dispatchers.IO) {
            metrics.request(ReaderRequestPriority.PREFETCH)
            val startedAt = SystemClock.elapsedRealtime()
            try {
                if (page.localFile?.isFile == true) return@withContext
                sourceRequests.request(sourceInFlightKey(page.key), ReaderRequestPriority.PREFETCH) { handle ->
                    val source = loadSourceFile(
                        page = page,
                        priority = ReaderRequestPriority.PREFETCH,
                        handle = handle,
                        retryFromDecode = false,
                    )
                    if (!source.persistent) {
                        validateReaderSourceFile(source.file)
                        diskCache.promoteSourceIfNeeded(source)
                    }
                    source
                }
            } finally {
                metrics.requestFinished(
                    priority = ReaderRequestPriority.PREFETCH,
                    elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
                )
            }
        }
    }

    suspend fun acquire(
        page: ReaderPage,
        priority: ReaderRequestPriority,
        retryFromDecode: Boolean,
    ): ReaderSourceLease {
        val lease = sourceRequests.acquire(
            sourceInFlightKey(page.key),
            priority,
        ) { handle ->
            loadSourceFile(
                page = page,
                priority = priority,
                handle = handle,
                retryFromDecode = retryFromDecode,
            )
        }
        return ReaderSourceLease(lease.value, lease)
    }

    suspend fun promote(sourceLease: ReaderSourceLease, forcePersistence: Boolean) {
        diskCache.promoteSourceIfNeeded(sourceLease.source, forcePersistence)
    }

    suspend fun invalidate(pageKey: ReaderPageKey, sourceLease: ReaderSourceLease) {
        diskCache.invalidateSource(sourceLease.source)
        sourceRequests.invalidate(sourceInFlightKey(pageKey))
    }

    fun cancelPrefetch(pageKey: ReaderPageKey): Boolean =
        sourceRequests.cancelPrefetch(sourceInFlightKey(pageKey))

    fun cancelAllPrefetch() = sourceRequests.cancelAllPrefetch()

    private suspend fun loadSourceFile(
        page: ReaderPage,
        priority: ReaderRequestPriority,
        handle: ReaderLoadHandle,
        retryFromDecode: Boolean,
    ): ReaderSourceFile {
        diskCache.acquireCachedSource(page.key)?.let { source ->
            metrics.sourceHit()
            if (priority == ReaderRequestPriority.PREFETCH) metrics.prefetchCacheHit()
            return source
        }
        return fetchSourceFile(
            page = page,
            handle = handle,
            persistSourceCache = priority != ReaderRequestPriority.BACKGROUND,
            preferFallback = retryFromDecode,
        )
    }

    private suspend fun fetchSourceFile(
        page: ReaderPage,
        handle: ReaderLoadHandle,
        persistSourceCache: Boolean,
        preferFallback: Boolean,
    ): ReaderSourceFile {
        val generation = diskCache.currentGeneration()
        var networkError: Throwable? = null

        suspend fun fetchFallback(): ReaderSourceFile? {
            val fallbackFetcher = page.fallbackFetcher ?: return null
            var bytes: ByteArray?
            while (true) {
                try {
                    bytes = withNetworkRequestPriority(handle) { fallbackFetcher() }
                    break
                } catch (_: ReaderBackgroundPreempted) {
                    handle.awaitBackgroundTurn()
                }
            }
            if (bytes == null || bytes.isEmpty() || bytes.size.toLong() > READER_MAX_SOURCE_BYTES) {
                return null
            }
            val temporary = diskCache.createSourceTempFile()
            return try {
                FileOutputStream(temporary).use { it.write(bytes) }
                validateReaderSourceFile(temporary)
                diskCache.transientSource(
                    pageKey = page.key,
                    file = temporary,
                    generation = generation,
                    persistAfterValidation = persistSourceCache,
                )
            } catch (error: Throwable) {
                diskCache.discardTemporary(temporary)
                throw error
            }
        }

        suspend fun fetchRemote(): ReaderSourceFile {
            val urls = imageHostManager.orderedImageUrls(page.originSrc)
            if (urls.isEmpty()) throw ReaderImageException("图片地址为空")
            imageHostManager.warmImageConnections(page.originSrc)
            var lastError: Throwable? = null
            val attemptedUrls = LinkedHashSet<String>()
            val visiblePolicy = readerVisibleSourcePolicy(
                orderedUrls = urls,
                fastestKnownLatencyMillis = imageHostManager.preferredLatencyMillis(),
            )
            var remainingUrls = if (handle.isVisible) visiblePolicy.urls else urls
            if (handle.isVisible && visiblePolicy.hedgeEnabled) {
                attemptedUrls += visiblePolicy.urls
                metrics.hedgeStarted()
                try {
                    val winner = fetchRemoteHedged(
                        primaryUrl = visiblePolicy.urls[0],
                        secondaryUrl = visiblePolicy.urls[1],
                        handle = handle,
                    )
                    return diskCache.transientSource(
                        pageKey = page.key,
                        file = winner.file,
                        generation = generation,
                        persistAfterValidation = persistSourceCache,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ReaderBackgroundPreempted) {
                    throw error
                } catch (error: Throwable) {
                    lastError = error
                    remainingUrls = urls.filterNot(attemptedUrls::contains)
                }
            }
            for (url in remainingUrls) {
                if (handle.isVisible && attemptedUrls.size >= MAX_VISIBLE_SOURCE_ATTEMPTS) break
                if (!attemptedUrls.add(url)) continue
                val attempt = fetchRemoteCandidate(url, handle)
                attempt.getOrNull()?.let { winner ->
                    return diskCache.transientSource(
                        pageKey = page.key,
                        file = winner.file,
                        generation = generation,
                        persistAfterValidation = persistSourceCache,
                    )
                }
                lastError = attempt.exceptionOrNull() ?: lastError
            }
            throw lastError ?: ReaderImageException("网络错误")
        }

        if (preferFallback && !handle.isVisible) {
            try {
                fetchFallback()?.let { return it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                networkError = error
            }
        }

        while (true) {
            try {
                return fetchRemote()
            } catch (error: CancellationException) {
                throw error
            } catch (_: ReaderBackgroundPreempted) {
                handle.awaitBackgroundTurn()
            } catch (error: Throwable) {
                networkError = error
                break
            }
        }

        if (!preferFallback && !handle.isVisible) {
            try {
                fetchFallback()?.let { return it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                networkError = error
            }
        }
        throw ReaderImageException("网络错误", networkError)
    }

    private suspend fun fetchRemoteCandidate(
        url: String,
        handle: ReaderLoadHandle,
    ): Result<ReaderRemoteAttempt> = remoteFetcher.fetch(
        url = url,
        acquirePermit = { acquireNetworkPermit(handle) },
        shouldPreempt = { !handle.isVisible && handle.hasVisibleRequest },
    )

    private suspend fun fetchRemoteHedged(
        primaryUrl: String,
        secondaryUrl: String,
        handle: ReaderLoadHandle,
    ): ReaderRemoteAttempt = remoteFetcher.fetchHedged(
        primaryUrl = primaryUrl,
        secondaryUrl = secondaryUrl,
        delayMillis = VISIBLE_HEDGE_DELAY_MILLIS,
        acquirePermit = { acquireNetworkPermit(handle) },
        shouldPreempt = { !handle.isVisible && handle.hasVisibleRequest },
    )

    private suspend fun acquireNetworkPermit(handle: ReaderLoadHandle): ReaderNetworkPermit =
        networkScheduler.acquire(
            isVisible = { handle.isVisible },
            hasVisibleRequest = { handle.hasVisibleRequest },
        )

    private suspend fun <T> withNetworkRequestPriority(
        handle: ReaderLoadHandle,
        block: suspend () -> T,
    ): T {
        val permit = acquireNetworkPermit(handle)
        return try {
            if (!handle.isVisible && handle.hasVisibleRequest) {
                throw ReaderBackgroundPreempted()
            }
            block()
        } finally {
            permit.close()
        }
    }

    private fun sourceInFlightKey(pageKey: ReaderPageKey): String =
        readerCacheKey(pageKey, "source")

    private companion object {
        const val NETWORK_READ_CHUNK_BYTES = 32 * 1024
        const val VISIBLE_HEDGE_DELAY_MILLIS = 100L
    }
}

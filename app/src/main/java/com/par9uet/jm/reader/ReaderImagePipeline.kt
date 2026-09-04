package com.par9uet.jm.reader

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import com.par9uet.jm.BuildConfig
import com.par9uet.jm.image.JmImageHostHealthManager
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.reader.atom.ReaderBitmapCache
import com.par9uet.jm.reader.atom.ReaderImageDiskCache
import com.par9uet.jm.reader.atom.readerBitmapCacheBudgetBytes
import com.par9uet.jm.reader.coordinator.ReaderRemoteTelemetry
import com.par9uet.jm.reader.molecule.ReaderSourceLoader
import com.par9uet.jm.store.ReaderPreferences
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** L2 coordinator for reader request priority, decode order and resource ownership. */
class ReaderImagePipeline internal constructor(
    context: Context,
    private val readerPreferences: ReaderPreferences,
    imageHostHealthManager: JmImageHostHealthManager,
    dohManager: DohManager,
) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val memoryClassMb = activityManager?.memoryClass ?: 256
    private val lowRamDevice = activityManager?.isLowRamDevice ?: false
    private val imageWorkConcurrency =
        ReaderConcurrencyPolicy.imageWorkConcurrency(lowRamDevice, memoryClassMb)
    private val maxDecodeConcurrency =
        ReaderConcurrencyPolicy.maxDecodeConcurrency(lowRamDevice, memoryClassMb)
    private val initialDecodeConcurrency = ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
        memoryOptEnabled = readerPreferences.memoryOptEnabled.value,
        userConcurrency = readerPreferences.decodeConcurrency.value,
        lowRamDevice = lowRamDevice,
        memoryClassMb = memoryClassMb,
    )
    private val networkConcurrency = if (imageWorkConcurrency > 1 && memoryClassMb >= 512) {
        3
    } else {
        imageWorkConcurrency
    }

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val networkScheduler = ReaderNetworkScheduler(
        totalConcurrency = networkConcurrency,
        initialBackgroundConcurrency = if (
            networkConcurrency >= 3 && readerPreferences.prefetchCount.value >= 5
        ) 2 else 1,
    )
    private val decodeLimiter = ReaderDynamicLimiter(initialDecodeConcurrency)
    private val backgroundDecodeLimiter = if (maxDecodeConcurrency > 1) {
        ReaderDynamicLimiter(initialDecodeConcurrency - 1)
    } else {
        null
    }
    private val bitmapCache = ReaderBitmapCache(readerBitmapCacheBudgetBytes(memoryClassMb))
    private val diskCache = ReaderImageDiskCache(
        directory = File(appContext.cacheDir, "reader_pages"),
        scope = scope,
    )
    private val visibleRequestTracker = ReaderVisibleRequestTracker()
    private val requests = ReaderInFlightRegistry<ReaderInFlightKey, ReaderDecodedPage>(
        scope = scope,
        visibleRequestTracker = visibleRequestTracker,
    )

    // DoH is shared by the HTTP client; CDN ordering remains in the source molecule.
    private val httpClient = OkHttpClient.Builder()
        .dns(dohManager)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val imageHostManager = ReaderImageHostManager(
        httpClient = httpClient,
        scope = scope,
        healthManager = imageHostHealthManager,
    )
    private val metrics = ReaderMetrics(BuildConfig.DEBUG)
    private val sourceLoader = ReaderSourceLoader(
        scope = scope,
        visibleRequestTracker = visibleRequestTracker,
        diskCache = diskCache,
        networkScheduler = networkScheduler,
        imageHostManager = imageHostManager,
        metrics = metrics,
        httpClient = httpClient,
        remoteObserver = ReaderRemoteTelemetry(metrics, imageHostManager),
    )

    init {
        appContext.registerComponentCallbacks(bitmapCache)
        observeDecodeConcurrency()
        observePrefetchConcurrency()
    }

    suspend fun loadVisiblePage(page: ReaderPage): ReaderDecodedPage =
        withReaderVisibleLoadDeadline(VISIBLE_LOAD_TIMEOUT_MILLIS) {
            request(page, ReaderRequestPriority.VISIBLE, currentProfile())
        }

    /** Download work is background throughput, not a visible request. */
    suspend fun loadForDownload(page: ReaderPage): ReaderDecodedPage =
        request(page, ReaderRequestPriority.BACKGROUND, ReaderDecodeProfile.DOWNLOAD)

    suspend fun prefetchPage(page: ReaderPage) {
        if (imageWorkConcurrency == 1) {
            prefetchPageSource(page)
        } else {
            request(page, ReaderRequestPriority.PREFETCH, currentProfile())
        }
    }

    /** Source-only warming keeps a single-slot device responsive and avoids bitmap heap growth. */
    suspend fun prefetchPageSource(page: ReaderPage) {
        sourceLoader.prefetchSource(page)
    }

    internal fun adaptivePrefetchPolicy(
        configuredDistance: Int,
        jumpDistance: Int,
        directionStreak: Int,
        pageVelocity: Float,
        turboMode: Boolean,
    ): ReaderPrefetchPolicy = readerAdaptivePrefetchPolicy(
        configuredDistance = configuredDistance,
        memoryClassMb = memoryClassMb,
        lowRamDevice = lowRamDevice,
        singleNetworkSlot = imageWorkConcurrency == 1,
        jumpDistance = jumpDistance,
        directionStreak = directionStreak,
        pageVelocity = pageVelocity,
        networkLatencyMillis = imageHostManager.preferredLatencyMillis(),
        turboMode = turboMode,
    )

    internal fun metricsSnapshot(): ReaderMetricsSnapshot = metrics.snapshot()

    internal fun imageHostSnapshot(): ReaderImageHostSnapshot = imageHostManager.snapshot()

    fun warmImageConnections(page: ReaderPage) {
        imageHostManager.warmImageConnections(page.originSrc)
    }

    fun cancelPrefetch(pageKey: ReaderPageKey): Boolean {
        val decodedCancelled = requests.cancelPrefetchMatching {
            it.page.stableIdentity() == pageKey.stableIdentity()
        } > 0
        val sourceCancelled = sourceLoader.cancelPrefetch(pageKey)
        if (decodedCancelled || sourceCancelled) metrics.prefetchCanceled()
        return decodedCancelled || sourceCancelled
    }

    fun cancelAllPrefetch() {
        requests.cancelAllPrefetch()
        sourceLoader.cancelAllPrefetch()
    }

    fun clearMemory() = bitmapCache.clear()

    suspend fun clearDiskCache() = diskCache.clear()

    fun close() {
        appContext.unregisterComponentCallbacks(bitmapCache)
        requests.cancelAllPrefetch()
        sourceLoader.cancelAllPrefetch()
        diskCache.close()
        scopeJob.cancel()
        bitmapCache.clear()
        imageHostManager.close()
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
    }

    private fun observeDecodeConcurrency() {
        scope.launch {
            combine(
                readerPreferences.memoryOptEnabled,
                readerPreferences.decodeConcurrency,
            ) { memoryOpt, userConcurrency -> memoryOpt to userConcurrency }
                .map { (memoryOpt, userConcurrency) ->
                    ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                        memoryOptEnabled = memoryOpt,
                        userConcurrency = userConcurrency,
                        lowRamDevice = lowRamDevice,
                        memoryClassMb = memoryClassMb,
                    )
                }
                .distinctUntilChanged()
                .collect { concurrency ->
                    decodeLimiter.updateLimit(concurrency)
                    backgroundDecodeLimiter?.updateLimit((concurrency - 1).coerceAtLeast(0))
                }
        }
    }

    private fun observePrefetchConcurrency() {
        scope.launch {
            readerPreferences.prefetchCount.collect { count ->
                networkScheduler.updateBackgroundLimit(
                    if (networkConcurrency >= 3 && count >= 5) 2 else 1,
                )
            }
        }
    }

    private suspend fun request(
        page: ReaderPage,
        priority: ReaderRequestPriority,
        profile: ReaderDecodeProfile,
    ): ReaderDecodedPage {
        val startedAt = SystemClock.elapsedRealtime()
        metrics.request(priority)
        val canonicalPageKey = canonicalPageKey(page.key)
        if (profile != ReaderDecodeProfile.DOWNLOAD) {
            bitmapCache.get(canonicalPageKey, profile)?.let { cached ->
                metrics.memoryHit()
                if (priority == ReaderRequestPriority.PREFETCH) metrics.prefetchCacheHit()
                metrics.requestFinished(priority, SystemClock.elapsedRealtime() - startedAt)
                return cached
            }
        }
        metrics.cacheMiss()

        val inFlightKey = ReaderInFlightKey(canonicalPageKey, profile.cacheToken)
        return try {
            requests.request(inFlightKey, priority) { handle ->
                performLoad(page, priority, profile, canonicalPageKey, handle)
            }
        } finally {
            metrics.requestFinished(priority, SystemClock.elapsedRealtime() - startedAt)
        }
    }

    private suspend fun performLoad(
        page: ReaderPage,
        priority: ReaderRequestPriority,
        profile: ReaderDecodeProfile,
        canonicalPageKey: ReaderPageKey,
        handle: ReaderLoadHandle,
    ): ReaderDecodedPage = withContext(Dispatchers.IO) {
        if (profile != ReaderDecodeProfile.DOWNLOAD) {
            bitmapCache.get(canonicalPageKey, profile)?.let { cached ->
                metrics.memoryHit()
                return@withContext cached
            }
        }

        val decodedFile = diskCache.decodedFile(page.key, profile)
        val localFile = page.localFile
        if (localFile?.isFile == true) {
            return@withContext decodeAndCache(
                cachePageKey = canonicalPageKey,
                decoded = withMeasuredDecode(handle) {
                    decodeReaderRawFile(localFile, page, profile)
                },
                decodedFile = decodedFile,
                profile = profile,
            )
        }

        if (profile != ReaderDecodeProfile.DOWNLOAD) {
            decodeReaderDecodedFile(decodedFile, profile)?.let { cached ->
                metrics.decodedDiskHit()
                bitmapCache.put(canonicalPageKey, profile, cached.bitmap)
                return@withContext ReaderDecodedPage(cached.bitmap, cached.aspectRatio)
            } ?: decodedFile.takeIf(File::exists)?.delete()
        }

        var sourceLease = sourceLoader.acquire(page, priority, retryFromDecode = false)
        var retriedAfterDecodeFailure = false
        var decodedPage: ReaderDecodedPage? = null
        try {
            while (decodedPage == null) {
                val decoded = try {
                    withMeasuredDecode(handle) {
                        decodeReaderRawFile(sourceLease.file, page, profile)
                    }
                } catch (error: Throwable) {
                    if (
                        !retriedAfterDecodeFailure &&
                        shouldInvalidateSourceAfterDecodeFailure(error)
                    ) {
                        retriedAfterDecodeFailure = true
                        sourceLoader.invalidate(page.key, sourceLease)
                        sourceLease.release()
                        sourceLease = sourceLoader.acquire(page, priority, retryFromDecode = true)
                        continue
                    }
                    throw error
                }
                sourceLoader.promote(
                    sourceLease = sourceLease,
                    forcePersistence = priority == ReaderRequestPriority.VISIBLE,
                )
                decodedPage = decodeAndCache(
                    cachePageKey = canonicalPageKey,
                    decoded = decoded,
                    decodedFile = decodedFile,
                    profile = profile,
                )
            }
        } finally {
            sourceLease.release()
        }
        checkNotNull(decodedPage)
    }

    private fun decodeAndCache(
        cachePageKey: ReaderPageKey,
        decoded: DecodedReaderImage,
        decodedFile: File,
        profile: ReaderDecodeProfile,
    ): ReaderDecodedPage {
        if (profile != ReaderDecodeProfile.DOWNLOAD) {
            bitmapCache.put(cachePageKey, profile, decoded.bitmap)
            diskCache.scheduleDecodedCacheWrite(decodedFile, decoded.bitmap, profile.webpQuality)
        }
        return ReaderDecodedPage(decoded.bitmap, decoded.aspectRatio)
    }

    private suspend fun <T> withDecodePriority(
        handle: ReaderLoadHandle,
        block: suspend () -> T,
    ): T {
        if (handle.isVisible) return decodeLimiter.withPermit(block)
        val backgroundLimiter = backgroundDecodeLimiter
        if (backgroundLimiter == null) {
            if (handle.priority == ReaderRequestPriority.PREFETCH) {
                handle.awaitVisible()
            } else {
                handle.awaitBackgroundTurn()
            }
            return decodeLimiter.withPermit(block)
        }
        if (backgroundLimiter.limit <= 0 && handle.priority == ReaderRequestPriority.PREFETCH) {
            while (!handle.isVisible && backgroundLimiter.limit <= 0) {
                delay(PRIORITY_RETRY_DELAY_MILLIS)
            }
            if (handle.isVisible) return decodeLimiter.withPermit(block)
        } else if (backgroundLimiter.limit <= 0) {
            handle.awaitBackgroundTurn()
            return decodeLimiter.withPermit(block)
        }

        while (true) {
            if (handle.isVisible) return decodeLimiter.withPermit(block)
            if (backgroundLimiter.tryAcquire()) {
                if (handle.isVisible) {
                    backgroundLimiter.release()
                    continue
                }
                return try {
                    decodeLimiter.withPermit(block)
                } finally {
                    backgroundLimiter.release()
                }
            }
            delay(PRIORITY_RETRY_DELAY_MILLIS)
        }
    }

    private suspend fun <T> withMeasuredDecode(
        handle: ReaderLoadHandle,
        block: suspend () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            withDecodePriority(handle, block)
        } finally {
            metrics.decodeFinished(SystemClock.elapsedRealtime() - startedAt)
        }
    }

    private fun canonicalPageKey(pageKey: ReaderPageKey): ReaderPageKey =
        pageKey.copy(sourceIdentity = readerLogicalSourceIdentity(pageKey.sourceIdentity))

    private fun currentProfile(): ReaderDecodeProfile =
        if (readerPreferences.memoryOptEnabled.value) {
            ReaderDecodeProfile.LOW
        } else {
            ReaderDecodeProfile.HIGH
        }

    private fun shouldInvalidateSourceAfterDecodeFailure(error: Throwable): Boolean =
        error !is CancellationException && error !is OutOfMemoryError

    private companion object {
        const val PRIORITY_RETRY_DELAY_MILLIS = 24L
        const val VISIBLE_LOAD_TIMEOUT_MILLIS = 20_000L
    }
}

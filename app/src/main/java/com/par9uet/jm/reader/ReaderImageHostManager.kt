package com.par9uet.jm.reader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

internal data class ReaderImageHostSnapshot(
    val hosts: List<String>,
    val preferredHost: String?,
    val latencyMillis: Map<String, Long>,
    val probeTimestamps: Map<String, Long>,
    val failedHosts: Set<String>,
)

/**
 * Keeps image-CDN routing separate from API routing. The logical source key remains the page
 * identity, so changing mirrors never creates a second source/decode cache entry.
 */
internal class ReaderImageHostManager(
    context: Context,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    configuredHostFlow: Flow<String>,
) {
    private data class HostState(
        @Volatile var latencyMillis: Long? = null,
        @Volatile var probedAtMillis: Long = 0L,
        @Volatile var failedAtMillis: Long = 0L,
        @Volatile var warmedAtMillis: Long = 0L,
    )

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val probeClient = httpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val hostStates = ConcurrentHashMap<String, HostState>()
    private val configuredHosts = ConcurrentHashMap.newKeySet<String>()
    private val refreshInFlight = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()
        override fun onLost(network: Network) = onNetworkChanged()
    }
    private val callbackRegistered = runCatching {
        connectivityManager?.registerNetworkCallback(
            NetworkRequest.Builder().build(),
            networkCallback,
        )
        connectivityManager != null
    }.getOrDefault(false)

    @Volatile
    private var preferredHost: String? = normalizeHost(preferences.getString(PREFERRED_HOST_KEY, null))
        ?.takeIf { it in KNOWN_IMAGE_HOSTS }

    init {
        val savedLatencies = preferences.getString(LATENCY_KEY, null)
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|', limit = 3)
                val host = normalizeHost(parts.getOrNull(0)) ?: return@mapNotNull null
                val latency = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it in 1..MAX_LATENCY_MILLIS }
                val probedAt = parts.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
                Triple(host, latency, probedAt)
            }
        savedLatencies.forEach { (host, latency, probedAt) ->
            hostStates.getOrPut(host) { HostState() }.apply {
                latencyMillis = latency
                probedAtMillis = probedAt
            }
        }
        preferredHost?.let { configuredHosts += it }
        scope.launch(Dispatchers.IO) {
            configuredHostFlow.collectLatest { raw ->
                normalizeHost(raw)?.let { host ->
                    configuredHosts += host
                    hostStates.putIfAbsent(host, HostState())
                    scheduleRefresh()
                }
            }
        }
        // The persisted preferred host is available synchronously. Probes only refine it.
        scheduleRefresh()
    }

    fun orderedImageUrls(originUrl: String): List<String> {
        val url = originUrl.toHttpUrlOrNull() ?: return listOf(originUrl)
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return listOf(originUrl)
        }
        val originHost = url.host
        val hosts = orderedHosts(originHost)
        val canMirror = canMirror(originHost, url.encodedPath)
        if (!canMirror) return listOf(originUrl)
        return hosts.map { host ->
            if (host == originHost) originUrl else replaceReaderImageHost(originUrl, host) ?: originUrl
        }.distinct()
    }

    fun preferredLatencyMillis(): Long? = preferredHost?.let { hostStates[it]?.latencyMillis }

    fun recordSuccess(url: String, elapsedMillis: Long) {
        val host = url.toHttpUrlOrNull()?.host ?: return
        if (host !in allHosts()) return
        val state = hostStates.getOrPut(host) { HostState() }
        state.failedAtMillis = 0L
        state.latencyMillis = ewma(state.latencyMillis, elapsedMillis.coerceAtLeast(1L))
        preferredHost = host
        preferences.edit {
            putString(PREFERRED_HOST_KEY, host)
            putString(LATENCY_KEY, serializeLatencies())
        }
    }

    fun recordFailure(url: String) {
        val host = url.toHttpUrlOrNull()?.host ?: return
        if (host !in allHosts()) return
        hostStates.getOrPut(host) { HostState() }.failedAtMillis = System.currentTimeMillis()
    }

    fun warmImageConnections(originUrl: String) {
        if (closed.get()) return
        val parsed = originUrl.toHttpUrlOrNull() ?: return
        if (!canMirror(parsed.host, parsed.encodedPath)) return
        orderedHosts(parsed.host).take(WARMUP_HOST_COUNT).forEach { host ->
            val state = hostStates.getOrPut(host) { HostState() }
            val now = SystemClock.elapsedRealtime()
            if (now - state.warmedAtMillis < WARMUP_REUSE_MILLIS) return@forEach
            state.warmedAtMillis = now
            val warmupUrl = parsed.newBuilder().host(host).build().toString()
            scope.launch(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(warmupUrl)
                    .head()
                    .header("X-Requested-With", "com.JMComic3.app")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .build()
                val call = httpClient.newCall(request)
                try {
                    runInterruptible(Dispatchers.IO) { call.execute().use { } }
                } catch (error: CancellationException) {
                    call.cancel()
                    state.warmedAtMillis = 0L
                    throw error
                } catch (_: Exception) {
                    state.warmedAtMillis = 0L
                }
            }
        }
    }

    fun scheduleRefresh() {
        if (!refreshInFlight.compareAndSet(false, true) || closed.get()) return
        scope.launch(Dispatchers.IO) {
            try {
                refreshProbes()
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    fun snapshot(): ReaderImageHostSnapshot {
        val now = System.currentTimeMillis()
        val hosts = allHosts()
        return ReaderImageHostSnapshot(
            hosts = hosts,
            preferredHost = preferredHost,
            latencyMillis = hosts.mapNotNull { host ->
                hostStates[host]?.latencyMillis?.let { host to it }
            }.toMap(),
            probeTimestamps = hosts.mapNotNull { host ->
                hostStates[host]?.probedAtMillis?.takeIf { it > 0L }?.let { host to it }
            }.toMap(),
            failedHosts = hosts.filter { isCoolingDown(it, now) }.toSet(),
        )
    }

    fun onNetworkChanged() {
        if (closed.get()) return
        hostStates.values.forEach {
            it.failedAtMillis = 0L
            it.latencyMillis = null
            it.probedAtMillis = 0L
            it.warmedAtMillis = 0L
        }
        preferredHost = null
        probeClient.dispatcher.cancelAll()
        probeClient.connectionPool.evictAll()
        httpClient.connectionPool.evictAll()
        scheduleRefresh()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (callbackRegistered) runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        probeClient.dispatcher.cancelAll()
        probeClient.connectionPool.evictAll()
        httpClient.connectionPool.evictAll()
    }

    private suspend fun refreshProbes() {
        if (closed.get()) return
        val hosts = allHosts()
        if (hosts.isEmpty()) return
        val hadPreferredHost = preferredHost != null
        val limiter = Semaphore(PROBE_CONCURRENCY)
        hosts.map { host ->
            scope.async(Dispatchers.IO) {
                limiter.withPermit { probe(host) }
            }
        }.awaitAll()
        if (!hadPreferredHost && preferredHost == null) preferredHost = fastestHost()
        preferences.edit {
            putString(LATENCY_KEY, serializeLatencies())
            putString(PREFERRED_HOST_KEY, preferredHost)
        }
    }

    private suspend fun probe(host: String) {
        val startedAt = SystemClock.elapsedRealtime()
        val url = "https://$host$IMAGE_PROBE_PATH"
        val request = Request.Builder()
            .url(url)
            .head()
            .header("X-Requested-With", "com.JMComic3.app")
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .build()
        val call = probeClient.newCall(request)
        try {
            runInterruptible(Dispatchers.IO) { call.execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
            } }
            val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
            hostStates.getOrPut(host) { HostState() }.apply {
                latencyMillis = ewma(latencyMillis, elapsed)
                probedAtMillis = System.currentTimeMillis()
                failedAtMillis = 0L
            }
        } catch (error: CancellationException) {
            call.cancel()
            throw error
        } catch (_: Exception) {
            hostStates.getOrPut(host) { HostState() }.apply {
                probedAtMillis = System.currentTimeMillis()
                failedAtMillis = probedAtMillis
            }
        }
    }

    private fun orderedHosts(originHost: String): List<String> {
        val now = System.currentTimeMillis()
        val hosts = (allHosts() + originHost).distinct()
        return orderReaderImageHosts(
            candidates = hosts,
            originHost = originHost,
            preferredHost = preferredHost,
            latencyMillis = hosts.associateWith { hostStates[it]?.latencyMillis },
            failedAtMillis = hosts.associateWith { hostStates[it]?.failedAtMillis ?: 0L },
            nowMillis = now,
            cooldownMillis = HOST_COOLDOWN_MILLIS,
        )
    }

    private fun allHosts(): List<String> = (KNOWN_IMAGE_HOSTS + configuredHosts)
        .mapNotNull(::normalizeHost)
        .distinct()
        .take(MAX_HOST_COUNT)

    private fun isCoolingDown(host: String, now: Long): Boolean {
        val failedAt = hostStates[host]?.failedAtMillis ?: return false
        return failedAt > 0L && now - failedAt in 0 until HOST_COOLDOWN_MILLIS
    }

    private fun fastestHost(): String? = allHosts()
        .mapNotNull { host -> hostStates[host]?.latencyMillis?.let { host to it } }
        .minByOrNull { it.second }
        ?.first

    private fun serializeLatencies(): String = allHosts().mapNotNull { host ->
        hostStates[host]?.latencyMillis?.let { "$host|$it|${hostStates[host]?.probedAtMillis ?: 0L}" }
    }.joinToString("\n").take(MAX_PREFERENCE_LENGTH)

    private fun canMirror(host: String, path: String): Boolean =
        isReaderImageMirrorAllowed(host, path, allHosts())

    private companion object {
        private const val PREFERENCES_NAME = "reader_image_hosts"
        private const val PREFERRED_HOST_KEY = "preferred_host"
        private const val LATENCY_KEY = "latencies"
        private const val IMAGE_PROBE_PATH = "/media/albums/220980_3x4.jpg"
        private const val HOST_COOLDOWN_MILLIS = 120_000L
        private const val WARMUP_REUSE_MILLIS = 4L * 60L * 1_000L
        private const val WARMUP_HOST_COUNT = 3
        private const val PROBE_CONCURRENCY = 3
        private const val MAX_HOST_COUNT = 12
        private const val MAX_LATENCY_MILLIS = 120_000L
        private const val MAX_PREFERENCE_LENGTH = 4_096
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; Mobile) AppleWebKit/537.36 Chrome/91.0 Safari/537.36"
        private const val REFERER = "https://18comic.vip"
        private val KNOWN_IMAGE_HOSTS = listOf(
            "cdn-msp.jmapiproxy1.cc",
            "cdn-msp.jmapiproxy2.cc",
            "cdn-msp2.jmapiproxy2.cc",
            "cdn-msp3.jmapiproxy2.cc",
            "cdn-msp.jmapinodeudzn.net",
            "cdn-msp3.jmapinodeudzn.net",
        )

        private fun normalizeHost(raw: String?): String? {
            val value = raw?.trim()?.trimEnd('/').orEmpty()
            if (value.isBlank()) return null
            val parsed = (if (value.contains("://")) value else "https://$value").toHttpUrlOrNull()
                ?: return null
            if (parsed.scheme != "https" || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                return null
            }
            val host = parsed.host.lowercase()
            if (
                host.length > 253 ||
                host.contains("..") ||
                host.any { it == '/' || it == '?' || it == '#' } ||
                host.split('.').any { label ->
                    label.isEmpty() || label.length > 63 ||
                        label.first().let { !it.isLetterOrDigit() } ||
                        label.last().let { !it.isLetterOrDigit() } ||
                        label.any { character -> !character.isLetterOrDigit() && character != '-' }
                }
            ) {
                return null
            }
            return host
        }

        private fun ewma(previous: Long?, current: Long): Long =
            if (previous == null) current else (previous * 3L + current) / 4L
    }
}

internal fun orderReaderImageHosts(
    candidates: List<String>,
    originHost: String,
    preferredHost: String?,
    latencyMillis: Map<String, Long?>,
    failedAtMillis: Map<String, Long>,
    nowMillis: Long,
    cooldownMillis: Long,
): List<String> = candidates.distinct().sortedWith(
    compareBy<String>(
        { host ->
            val failedAt = failedAtMillis[host] ?: 0L
            if (failedAt > 0L && nowMillis - failedAt in 0 until cooldownMillis) 1 else 0
        },
        { host -> if (host == preferredHost) 0 else 1 },
        { host -> latencyMillis[host] ?: Long.MAX_VALUE },
        { host -> if (host == originHost) 0 else 1 },
    ),
)

internal fun replaceReaderImageHost(originalUrl: String, host: String): String? {
    val url = originalUrl.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" || host.isBlank()) return null
    return runCatching { url.newBuilder().host(host).build().toString() }.getOrNull()
}

@Suppress("UNUSED_PARAMETER")
internal fun isReaderImageMirrorAllowed(
    host: String,
    path: String,
    allowlistedHosts: Collection<String>,
): Boolean = host in allowlistedHosts

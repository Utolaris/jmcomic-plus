package com.par9uet.jm.image

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class JmImageHostSnapshot(
    val hosts: List<String>,
    val preferredHost: String?,
    val latencyMillis: Map<String, Long>,
    val probeTimestamps: Map<String, Long>,
    val failedHosts: Set<String>,
)

internal data class JmImageHostLatency(
    val latencyMillis: Long,
    val probedAtMillis: Long,
)

internal data class JmImageHostPersistence(
    val preferredHost: String?,
    val latencies: Map<String, JmImageHostLatency>,
)

internal interface JmImageHostHealth {
    val preferredHost: StateFlow<String?>
    val networkGeneration: StateFlow<Long>
    fun registerHost(rawHost: String?): Boolean
    fun orderedHosts(originHost: String? = null): List<String>
    /** TTFB 级延迟样本（响应头到达耗时），进入延迟 EWMA 参与 Reader 排序。 */
    fun recordLatencySample(rawHost: String?, ttfbMillis: Long)

    /** 资源加载成功（如封面）：清除失败/冷却状态，但不注入延迟样本。 */
    fun recordHealthy(rawHost: String?)

    /** 主机/网络级失败：标记失败时间，进入冷却。 */
    fun recordHostFailure(rawHost: String?)
}

/** Pure, process-wide ranking state shared by Reader and cover routing. */
internal class JmImageHostHealthStore(
    knownHosts: List<String> = JM_IMAGE_HOSTS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val cooldownMillis: Long = JM_IMAGE_HOST_COOLDOWN_MILLIS,
    private val maxHosts: Int = MAX_JM_IMAGE_HOST_COUNT,
) : JmImageHostHealth {
    private data class HostState(
        var latencyMillis: Long? = null,
        var probedAtMillis: Long = 0L,
        var failedAtMillis: Long = 0L,
    )

    private val staticHosts = knownHosts.mapNotNull(::normalizeJmImageHost).distinct()
    private val configuredHosts = LinkedHashSet<String>()
    private val hostStates = ConcurrentHashMap<String, HostState>()
    private val _preferredHost = MutableStateFlow<String?>(null)
    private val _networkGeneration = MutableStateFlow(0L)

    override val preferredHost: StateFlow<String?> = _preferredHost.asStateFlow()
    override val networkGeneration: StateFlow<Long> = _networkGeneration.asStateFlow()

    @Synchronized
    override fun registerHost(rawHost: String?): Boolean {
        val host = normalizeJmImageHost(rawHost) ?: return false
        if (host in staticHosts || host in configuredHosts) return false
        if (allHostsLocked().size >= maxHosts.coerceAtLeast(1)) return false
        configuredHosts += host
        hostStates.putIfAbsent(host, HostState())
        return true
    }

    @Synchronized
    fun restore(persistence: JmImageHostPersistence) {
        persistence.latencies.forEach { (rawHost, saved) ->
            val host = normalizeJmImageHost(rawHost) ?: return@forEach
            if (saved.latencyMillis !in 1..MAX_JM_IMAGE_HOST_LATENCY_MILLIS) return@forEach
            registerHost(host)
            if (host !in allHostsLocked()) return@forEach
            hostStates.getOrPut(host) { HostState() }.apply {
                latencyMillis = saved.latencyMillis
                probedAtMillis = saved.probedAtMillis.coerceAtLeast(0L)
            }
        }
        _preferredHost.value = normalizeJmImageHost(persistence.preferredHost)
            ?.also { registerHost(it) }
            ?.takeIf { it in allHostsLocked() }
    }

    @Synchronized
    override fun orderedHosts(originHost: String?): List<String> {
        val origin = normalizeJmImageHost(originHost)
        val hosts = buildList {
            addAll(allHostsLocked())
            origin?.let(::add)
        }.distinct()
        val now = clockMillis()
        return orderJmImageHosts(
            candidates = hosts,
            originHost = origin,
            preferredHost = _preferredHost.value,
            latencyMillis = hosts.associateWith { hostStates[it]?.latencyMillis },
            failedAtMillis = hosts.associateWith { hostStates[it]?.failedAtMillis ?: 0L },
            nowMillis = now,
            cooldownMillis = cooldownMillis,
        )
    }

    @Synchronized
    override fun recordLatencySample(rawHost: String?, ttfbMillis: Long) {
        val host = normalizeJmImageHost(rawHost) ?: return
        if (host !in allHostsLocked()) return
        hostStates.getOrPut(host) { HostState() }.apply {
            failedAtMillis = 0L
            latencyMillis = jmImageHostLatencyEwma(
                latencyMillis,
                ttfbMillis.coerceAtLeast(1L),
            )
            probedAtMillis = clockMillis()
        }
        _preferredHost.value = fastestHealthyHostLocked()
    }

    @Synchronized
    override fun recordHealthy(rawHost: String?) {
        val host = normalizeJmImageHost(rawHost) ?: return
        if (host !in allHostsLocked()) return
        // 只清除失败/冷却状态；保留既有延迟测量，不注入伪延迟样本
        hostStates.getOrPut(host) { HostState() }.failedAtMillis = 0L
        _preferredHost.value = fastestHealthyHostLocked()
    }

    @Synchronized
    override fun recordHostFailure(rawHost: String?) {
        val host = normalizeJmImageHost(rawHost) ?: return
        if (host !in allHostsLocked()) return
        hostStates.getOrPut(host) { HostState() }.failedAtMillis = clockMillis()
        if (_preferredHost.value == host) {
            _preferredHost.value = fastestHealthyHostLocked()
        }
    }

    @Synchronized
    fun recordProbeFailure(rawHost: String?) {
        val host = normalizeJmImageHost(rawHost) ?: return
        if (host !in allHostsLocked()) return
        hostStates.getOrPut(host) { HostState() }.apply {
            probedAtMillis = clockMillis()
            failedAtMillis = probedAtMillis
        }
        if (_preferredHost.value == host) {
            _preferredHost.value = fastestHealthyHostLocked()
        }
    }

    @Synchronized
    fun onNetworkChanged() {
        hostStates.values.forEach { state ->
            state.latencyMillis = null
            state.probedAtMillis = 0L
            state.failedAtMillis = 0L
        }
        _preferredHost.value = null
        _networkGeneration.value += 1L
    }

    @Synchronized
    fun containsHost(rawHost: String?): Boolean {
        val host = normalizeJmImageHost(rawHost) ?: return false
        return host in allHostsLocked()
    }

    @Synchronized
    fun hosts(): List<String> = allHostsLocked()

    @Synchronized
    fun preferredLatencyMillis(): Long? =
        _preferredHost.value?.let { hostStates[it]?.latencyMillis }

    @Synchronized
    fun snapshot(): JmImageHostSnapshot {
        val now = clockMillis()
        val hosts = allHostsLocked()
        return JmImageHostSnapshot(
            hosts = hosts,
            preferredHost = _preferredHost.value,
            latencyMillis = hosts.mapNotNull { host ->
                hostStates[host]?.latencyMillis?.let { host to it }
            }.toMap(),
            probeTimestamps = hosts.mapNotNull { host ->
                hostStates[host]?.probedAtMillis?.takeIf { it > 0L }?.let { host to it }
            }.toMap(),
            failedHosts = hosts.filter { host ->
                val failedAt = hostStates[host]?.failedAtMillis ?: 0L
                failedAt > 0L && now - failedAt in 0 until cooldownMillis
            }.toSet(),
        )
    }

    @Synchronized
    fun persistence(): JmImageHostPersistence = JmImageHostPersistence(
        preferredHost = _preferredHost.value,
        latencies = allHostsLocked().mapNotNull { host ->
            val state = hostStates[host] ?: return@mapNotNull null
            state.latencyMillis?.let { latency ->
                host to JmImageHostLatency(latency, state.probedAtMillis)
            }
        }.toMap(),
    )

    private fun allHostsLocked(): List<String> = (staticHosts + configuredHosts)
        .distinct()
        .take(maxHosts.coerceAtLeast(1))

    private fun fastestHealthyHostLocked(): String? {
        val hosts = allHostsLocked()
        return selectJmPreferredHost(
            candidates = hosts,
            latencyMillis = hosts.associateWith { hostStates[it]?.latencyMillis },
            failedAtMillis = hosts.associateWith { hostStates[it]?.failedAtMillis ?: 0L },
            nowMillis = clockMillis(),
            cooldownMillis = cooldownMillis,
        )
    }
}

/** One application-level probe, persistence, and network-change controller. */
internal class JmImageHostHealthManager(
    context: Context,
    private val scope: CoroutineScope,
    configuredHostFlow: Flow<String>,
    baseHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
    private val store: JmImageHostHealthStore = JmImageHostHealthStore(),
) : JmImageHostHealth {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val probeClient = baseHttpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val refreshInFlight = AtomicBoolean(false)
    private val refreshRequested = AtomicBoolean(false)
    private val persistInFlight = AtomicBoolean(false)
    private val persistRequested = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val activeNetwork = AtomicReference(connectivityManager?.activeNetwork)
    private val networkChangeGeneration = AtomicLong()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (connectivityManager?.activeNetwork != network) return
            val previous = activeNetwork.getAndSet(network)
            if (previous != network) scheduleNetworkChanged()
        }

        override fun onLost(network: Network) {
            if (activeNetwork.compareAndSet(network, null)) scheduleNetworkChanged()
        }
    }
    private val callbackRegistered = runCatching {
        connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        connectivityManager != null
    }.getOrDefault(false)

    override val preferredHost: StateFlow<String?> = store.preferredHost
    override val networkGeneration: StateFlow<Long> = store.networkGeneration

    init {
        store.restore(readPersistence())
        scope.launch(Dispatchers.IO) {
            configuredHostFlow.collectLatest { rawHost ->
                if (store.registerHost(rawHost)) scheduleRefresh()
            }
        }
        scheduleRefresh()
    }

    override fun orderedHosts(originHost: String?): List<String> = store.orderedHosts(originHost)

    fun containsHost(host: String?): Boolean = store.containsHost(host)

    fun preferredLatencyMillis(): Long? = store.preferredLatencyMillis()

    fun snapshot(): JmImageHostSnapshot = store.snapshot()

    override fun registerHost(rawHost: String?): Boolean {
        val added = store.registerHost(rawHost)
        if (added) scheduleRefresh()
        return added
    }

    override fun recordLatencySample(rawHost: String?, ttfbMillis: Long) {
        store.recordLatencySample(rawHost, ttfbMillis)
        schedulePersistence()
    }

    override fun recordHealthy(rawHost: String?) {
        store.recordHealthy(rawHost)
        schedulePersistence()
    }

    override fun recordHostFailure(rawHost: String?) {
        store.recordHostFailure(rawHost)
        schedulePersistence()
    }

    fun onNetworkChanged() {
        if (closed.get()) return
        store.onNetworkChanged()
        probeClient.dispatcher.cancelAll()
        probeClient.connectionPool.evictAll()
        persistNow()
        scheduleRefresh()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (callbackRegistered) {
            runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        }
        probeClient.dispatcher.cancelAll()
        probeClient.connectionPool.evictAll()
    }

    private fun scheduleRefresh() {
        if (closed.get()) return
        refreshRequested.set(true)
        if (!refreshInFlight.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                do {
                    refreshRequested.set(false)
                    refreshProbes()
                } while (refreshRequested.get() && !closed.get())
            } finally {
                refreshInFlight.set(false)
                if (refreshRequested.get() && !closed.get()) scheduleRefresh()
            }
        }
    }

    private suspend fun refreshProbes() {
        if (closed.get()) return
        val hosts = store.hosts()
        if (hosts.isEmpty()) return
        val limiter = Semaphore(PROBE_CONCURRENCY)
        hosts.map { host ->
            scope.async(Dispatchers.IO) {
                limiter.withPermit { probe(host) }
            }
        }.awaitAll()
        persistNow()
    }

    private suspend fun probe(host: String) {
        val startedAt = SystemClock.elapsedRealtime()
        val request = Request.Builder()
            .url("https://$host$IMAGE_PROBE_PATH")
            .head()
            .header("X-Requested-With", "com.JMComic3.app")
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .build()
        val call = probeClient.newCall(request)
        try {
            val responseCode = runInterruptible(Dispatchers.IO) {
                call.execute().use { response -> response.code }
            }
            when {
                responseCode in 200..299 -> store.recordLatencySample(
                    host,
                    (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L),
                )
                classifyHttpCodeFailure(responseCode) == ImageHostFailureKind.HOST_FAILURE ->
                    store.recordProbeFailure(host)
                // 404/410/其它资源级响应证明主机可达，但不代表 probe 资源存在；
                // 清除冷却且保留旧延迟，不把它当作全局 CDN 故障。
                else -> store.recordHealthy(host)
            }
        } catch (error: CancellationException) {
            call.cancel()
            throw error
        } catch (error: Exception) {
            when (classifyImageHostFailure(error)) {
                ImageHostFailureKind.HOST_FAILURE -> store.recordProbeFailure(host)
                ImageHostFailureKind.RESOURCE_FAILURE -> store.recordHealthy(host)
                ImageHostFailureKind.CANCELLED,
                ImageHostFailureKind.UNKNOWN,
                -> Unit
            }
        }
    }

    private fun scheduleNetworkChanged() {
        if (closed.get()) return
        val generation = networkChangeGeneration.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            delay(NETWORK_CHANGE_DEBOUNCE_MILLIS)
            if (networkChangeGeneration.get() == generation) onNetworkChanged()
        }
    }

    private fun schedulePersistence() {
        if (closed.get()) return
        persistRequested.set(true)
        if (!persistInFlight.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                do {
                    persistRequested.set(false)
                    delay(PERSISTENCE_DEBOUNCE_MILLIS)
                    persistNow()
                } while (persistRequested.get() && !closed.get())
            } finally {
                persistInFlight.set(false)
                if (persistRequested.get() && !closed.get()) schedulePersistence()
            }
        }
    }

    private fun readPersistence(): JmImageHostPersistence {
        val latencies = preferences.getString(LATENCY_KEY, null)
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|', limit = 3)
                val host = normalizeJmImageHost(parts.getOrNull(0)) ?: return@mapNotNull null
                val latency = parts.getOrNull(1)?.toLongOrNull()
                    ?.takeIf { it in 1..MAX_JM_IMAGE_HOST_LATENCY_MILLIS }
                    ?: return@mapNotNull null
                val probedAt = parts.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                host to JmImageHostLatency(latency, probedAt)
            }
            .toMap()
        return JmImageHostPersistence(
            preferredHost = preferences.getString(PREFERRED_HOST_KEY, null),
            latencies = latencies,
        )
    }

    private fun persistNow() {
        val persistence = store.persistence()
        val serialized = persistence.latencies.entries.joinToString("\n") { (host, value) ->
            "$host|${value.latencyMillis}|${value.probedAtMillis}"
        }.take(MAX_PREFERENCE_LENGTH)
        preferences.edit {
            putString(PREFERRED_HOST_KEY, persistence.preferredHost)
            putString(LATENCY_KEY, serialized)
        }
    }

    private companion object {
        private const val PREFERENCES_NAME = "reader_image_hosts"
        private const val PREFERRED_HOST_KEY = "preferred_host"
        private const val LATENCY_KEY = "latencies"
        private const val IMAGE_PROBE_PATH = "/media/albums/220980_3x4.jpg"
        private const val PROBE_CONCURRENCY = 3
        private const val MAX_PREFERENCE_LENGTH = 4_096
        private const val NETWORK_CHANGE_DEBOUNCE_MILLIS = 300L
        private const val PERSISTENCE_DEBOUNCE_MILLIS = 500L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; Mobile) AppleWebKit/537.36 Chrome/91.0 Safari/537.36"
        private const val REFERER = "https://18comic.vip"
    }
}

internal const val JM_IMAGE_HOST_COOLDOWN_MILLIS = 120_000L
internal const val MAX_JM_IMAGE_HOST_COUNT = 12
internal const val MAX_JM_IMAGE_HOST_LATENCY_MILLIS = 120_000L

internal fun orderJmImageHosts(
    candidates: List<String>,
    originHost: String?,
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

internal fun jmImageHostLatencyEwma(previous: Long?, current: Long): Long =
    if (previous == null) current else (previous + current) / 2L

internal fun selectJmPreferredHost(
    candidates: Collection<String>,
    latencyMillis: Map<String, Long?>,
    failedAtMillis: Map<String, Long>,
    nowMillis: Long,
    cooldownMillis: Long,
): String? = candidates
    .asSequence()
    .filter { host ->
        val failedAt = failedAtMillis[host] ?: 0L
        failedAt <= 0L || nowMillis - failedAt !in 0 until cooldownMillis
    }
    .mapNotNull { host -> latencyMillis[host]?.let { host to it } }
    .minByOrNull { it.second }
    ?.first

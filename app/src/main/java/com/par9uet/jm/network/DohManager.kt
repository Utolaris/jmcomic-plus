package com.par9uet.jm.network

import android.util.Base64
import com.par9uet.jm.store.DohPreferences
import com.par9uet.jm.store.DohPreferencesEditor
import com.par9uet.jm.utils.applyCertificateTrust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DohRuntimeStatus(
    val active: Boolean = false,
    val serverName: String = "",
    val serverUrl: String = "",
    val cacheEntryCount: Int = 0,
    val lastError: String = "",
)

data class DohLatencyResult(
    val elapsedMs: Long? = null,
    val error: String = "",
)

/**
 * A process-wide DNS implementation for all app-owned OkHttp clients.
 * The DoH bootstrap client deliberately uses system DNS (or a fixed bootstrap IP)
 * so resolving the DNS server never recurses into this resolver.
 */
class DohManager(
    private val dohPrefs: DohPreferences,
    private val dohEditor: DohPreferencesEditor,
) : Dns {
    private val random = SecureRandom()
    private val _status = MutableStateFlow(DohRuntimeStatus())
    val status = _status.asStateFlow()
    private val _latencyState = MutableStateFlow<Map<String, DohLatencyResult>>(emptyMap())
    val latencyState = _latencyState.asStateFlow()

    @Volatile
    private var sessionEnabled = false

    @Volatile
    private var resolverKey = ""

    @Volatile
    private var resolver: DohResolver? = null

    @Volatile
    private var resolverFailure: String = ""

    override fun lookup(hostname: String): List<InetAddress> {
        val setting = dohPrefs.doh.value
        val selectedResolver = ensureResolver()
        if (selectedResolver != null) {
            return try {
                selectedResolver.lookup(hostname)
            } catch (error: Exception) {
                publishStatus(lastError = error.message ?: "DoH 解析失败")
                throw error
            }
        }
        // A user-enabled DoH connection must never silently fall back to a system
        // resolver. Failing the request makes the status visible and guarantees
        // that app-owned requests are either resolved through DoH or not sent.
        if (sessionEnabled && setting.enabled) {
            throw UnknownHostException(resolverFailure.ifBlank { "DoH 解析器未就绪" })
        }
        return Dns.SYSTEM.lookup(hostname)
            .filter { address -> dohPrefs.doh.value.preferIpv6 || address is Inet4Address }
            .sortedWith(compareBy<InetAddress> { it is Inet6Address })
    }

    fun setEnabled(enabled: Boolean) {
        dohEditor.persistEnabled(enabled)
        sessionEnabled = enabled
        rebuildResolver()
    }

    fun setAutoStart(enabled: Boolean) {
        dohEditor.persistAutoStart(enabled)
    }

    fun selectServer(serverId: String) {
        dohEditor.persistServer(serverId)
        rebuildResolver()
    }

    fun saveCustomServer(name: String, url: String) {
        require(isValidDohUrl(url)) { "请输入 HTTPS DoH 地址" }
        dohEditor.persistCustomServer(name, url)
        rebuildResolver()
    }

    fun setUseDeviceCertificates(enabled: Boolean) {
        dohEditor.persistUseDeviceCertificates(enabled)
        rebuildResolver()
    }

    fun setPreferIpv6(enabled: Boolean) {
        dohEditor.persistPreferIpv6(enabled)
        rebuildResolver()
    }

    fun clearCache() {
        resolver?.clearCache()
        publishStatus(lastError = "")
    }

    suspend fun testServer(server: DohServer): DohLatencyResult = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        var testResolver: DohResolver? = null
        val result = runCatching {
            val setting = dohPrefs.doh.value
            val resolver = DohResolver(
                server = server,
                preferIpv6 = setting.preferIpv6,
                useDeviceCertificates = setting.useDeviceCertificates,
                random = random,
            )
            testResolver = resolver
            resolver.lookup("www.qq.com")
            DohLatencyResult(elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
        }.getOrElse { DohLatencyResult(error = it.message ?: "测试失败") }.also {
            testResolver?.close()
        }
        _latencyState.value = _latencyState.value + (server.id to result)
        result
    }

    fun selectedServer(): DohServer = dohPrefs.doh.value.toDohServer()

    /** Activates DoH at app start according to the persisted enable/auto-start choices. */
    suspend fun init() {
        val setting = dohPrefs.doh.value
        sessionEnabled = setting.enabled && setting.autoStart
        rebuildResolver()
    }

    private fun ensureResolver(): DohResolver? {
        val setting = dohPrefs.doh.value
        val key = setting.resolverKey(sessionEnabled)
        if (key != resolverKey) rebuildResolver()
        return resolver
    }

    @Synchronized
    private fun rebuildResolver() {
        val setting = dohPrefs.doh.value
        val key = setting.resolverKey(sessionEnabled)
        resolverKey = key
        resolverFailure = ""
        val previousResolver = resolver
        resolver = if (sessionEnabled && setting.enabled) {
            runCatching {
                DohResolver(
                    server = setting.toDohServer(),
                    preferIpv6 = setting.preferIpv6,
                    useDeviceCertificates = setting.useDeviceCertificates,
                    random = random,
                )
            }.onFailure { error ->
                resolverFailure = error.message ?: "DoH 解析器初始化失败"
            }.getOrNull()
        } else {
            null
        }
        // Retire the previous resolver AFTER the swap so in-flight lookups finish on it while
        // new lookups use the replacement; closing eagerly would abort those calls.
        if (previousResolver != null && previousResolver !== resolver) {
            Thread {
                runCatching { previousResolver.close() }
            }.apply { isDaemon = true }.start()
        }
        publishStatus(lastError = resolverFailure)
    }

    private fun publishStatus(lastError: String = _status.value.lastError) {
        val activeResolver = resolver
        _status.value = DohRuntimeStatus(
            active = activeResolver != null,
            serverName = activeResolver?.server?.name.orEmpty(),
            serverUrl = activeResolver?.server?.displayUrl.orEmpty(),
            cacheEntryCount = activeResolver?.cacheSize ?: 0,
            lastError = lastError,
        )
    }
}

private class DohResolver(
    val server: DohServer,
    private val preferIpv6: Boolean,
    useDeviceCertificates: Boolean,
    private val random: SecureRandom,
) : Dns {
    private data class CachedAddress(
        val addresses: List<InetAddress>,
        val expiresAt: Long,
    )

    private val cache = ConcurrentHashMap<String, CachedAddress>()
    val cacheSize: Int get() = cache.size

    private val bootstrapDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (server.bootstrapHost != null && hostname.equals(server.bootstrapHost, ignoreCase = true)) {
                server.bootstrapIps.map { InetAddress.getByName(it) }
            } else {
                Dns.SYSTEM.lookup(hostname).let { addresses ->
                    if (preferIpv6) addresses else addresses.filterIsInstance<Inet4Address>()
                }
            }
        }
    private val client = OkHttpClient.Builder()
        .dns(bootstrapDns)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .applyCertificateTrust(includeDeviceCertificates = useDeviceCertificates)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isIpLiteral()) {
            val address = InetAddress.getByName(hostname)
            if (!preferIpv6 && address !is Inet4Address) {
                throw UnknownHostException("IPv6 已关闭：$hostname")
            }
            return listOf(address)
        }
        val key = hostname.lowercase()
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAt > now }?.let { return it.addresses }

        // IPv6 is opt-in. When disabled, do not even query AAAA records so a device
        // without IPv6 routing cannot fail before trying the usable IPv4 address.
        val types = if (preferIpv6) {
            listOf(DohPacketParser.TYPE_AAAA, DohPacketParser.TYPE_A)
        } else {
            listOf(DohPacketParser.TYPE_A)
        }
        val records = types.flatMap { type -> resolve(hostname, type) }
            .distinctBy { it.address.hostAddress }
        if (records.isEmpty()) {
            throw UnknownHostException("DoH 未返回 $hostname 的地址")
        }
        val ttlSeconds = records.minOf { it.ttlSeconds }.coerceIn(20L, 24 * 60 * 60L)
        val addresses = records.map { it.address }
        cache[key] = CachedAddress(addresses, now + ttlSeconds * 1000L)
        return addresses
    }

    fun clearCache() = cache.clear()

    fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun resolve(hostname: String, type: Int): List<DohDnsRecord> {
        val query = createQuery(hostname, type)
        val encoded = Base64.encodeToString(query, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val endpoint = server.endpointUrl.toHttpUrlOrNull()
            ?: throw UnknownHostException("DoH 地址无效")
        val request = Request.Builder()
            .url(endpoint.newBuilder().addQueryParameter("dns", encoded).build())
            .header("Accept", "application/dns-message")
            .header("User-Agent", "JM-Mobile-DoH")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UnknownHostException("DoH 服务返回 HTTP ${response.code}")
            }
            val bytes = response.body?.bytes() ?: throw UnknownHostException("DoH 服务返回空数据")
            return DohPacketParser.parse(bytes, type)
        }
    }

    private fun createQuery(hostname: String, type: Int): ByteArray {
        val labels = hostname.trimEnd('.').split('.')
        if (labels.isEmpty() || labels.any { it.isBlank() || it.length > 63 }) {
            throw UnknownHostException("域名格式无效：$hostname")
        }
        return ByteArrayOutputStream().apply {
            val id = random.nextInt(0x10000)
            writeU16(id)
            writeU16(0x0100) // Recursion desired.
            writeU16(1)
            writeU16(0)
            writeU16(0)
            writeU16(0)
            labels.forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                write(bytes.size)
                write(bytes)
            }
            write(0)
            writeU16(type)
            writeU16(DohPacketParser.CLASS_IN)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write((value shr 8) and 0xff)
        write(value and 0xff)
    }

}

private fun com.par9uet.jm.store.DohSettingsState.toDohServer(): DohServer = resolveDohServer(
    selectedId = serverId,
    customName = customServerName,
    customUrl = customServerUrl,
)

internal fun com.par9uet.jm.store.DohSettingsState.resolverKey(sessionEnabled: Boolean): String = listOf(
    enabled,
    sessionEnabled,
    serverId,
    customServerName,
    customServerUrl,
    useDeviceCertificates,
    preferIpv6,
).joinToString("|")

private fun String.isIpLiteral(): Boolean =
    matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) || contains(':')

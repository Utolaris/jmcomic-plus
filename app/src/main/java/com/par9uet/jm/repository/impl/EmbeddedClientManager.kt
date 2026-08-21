package com.par9uet.jm.repository.impl

import com.google.gson.JsonParser
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.utils.log
import io.github.jukomu.jmcomic.api.enums.ClientType
import io.github.jukomu.jmcomic.api.exception.ResponseException
import io.github.jukomu.jmcomic.api.model.JmUserInfo
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import io.github.jukomu.jmcomic.core.net.OkHttpBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cookie
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * 共享内置 API 客户端管理器。
 * 确保 UserRepositoryImpl 和 ComicRepositoryImpl 使用同一个 JmApiClient 实例，
 * 这样 login() 设置的 loggedInUserName 和内部 Cookie 状态可以被所有 Repository 共享。
 * 解决内置 API 模式下 POST 请求（如创建收藏夹）返回 401 "請先登入會員" 的问题。
 *
 * The client is created on the first embedded-API request, never while the application shell is
 * starting. Domain probing therefore belongs to the request path and cannot delay TTID/TTI.
 *
 * Android 6 兼容：JmDomainManager 的域名探活使用 CompletableFuture.runAsync（ForkJoinPool），
 * 在 Android 6 上可能初始化失败导致 blockUntilInitialized 永久阻塞。
 * 此处在创建客户端后启动守护线程，超时后强制解除阻塞。
 */
class EmbeddedClientManager(
    private val cookieStorage: CookieStorage,
) {
    sealed class EmbeddedLoginResult {
        data class Success(val userInfo: JmUserInfo) : EmbeddedLoginResult()
        data class Failure(
            val exception: ResponseException,
            val businessCode: Int?,
        ) : EmbeddedLoginResult()
    }

    private data class SharedClient(
        val client: JmApiClient,
        val loginBusinessCode: ThreadLocal<Int?>,
    )

    @Volatile
    private var sharedClient: SharedClient? = null
    private val sharedSessionGeneration = AtomicLong(0L)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getClient(): JmApiClient {
        return getSharedClient().client
    }

    private fun getSharedClient(): SharedClient {
        return sharedClient ?: synchronized(this) {
            sharedClient ?: run {
                val sessionGeneration = sharedSessionGeneration.get()
                val loginBusinessCode = ThreadLocal<Int?>()
                SharedClient(
                    client = createClient(
                        persistCookies = true,
                        loginBusinessCode = loginBusinessCode,
                        clientSessionGeneration = sessionGeneration,
                    ),
                    loginBusinessCode = loginBusinessCode,
                ).also { sharedClient = it }
            }
        }
    }

    /**
     * Runs a login on an isolated client when [isolatedSession] is true. The isolated client has
     * its own cookie jar, so a stale startup verification cannot change the active JM session.
     */
    fun login(
        username: String,
        password: String,
        isolatedSession: Boolean,
    ): EmbeddedLoginResult {
        val sharedClient = if (isolatedSession) null else getSharedClient()
        val loginBusinessCode = sharedClient?.loginBusinessCode ?: ThreadLocal<Int?>()
        loginBusinessCode.set(null)
        val loginClient = if (isolatedSession) {
            createClient(
                persistCookies = false,
                loginBusinessCode = loginBusinessCode,
                clientSessionGeneration = null,
            )
        } else {
            checkNotNull(sharedClient).client
        }

        return try {
            EmbeddedLoginResult.Success(loginClient.login(username, password))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            EmbeddedLoginResult.Failure(e, loginBusinessCode.get())
        } finally {
            loginBusinessCode.remove()
            if (isolatedSession) {
                closeAsync(loginClient)
            }
        }
    }

    /**
     * Clears the in-memory embedded client session without performing a blocking logout request.
     * Detaching the client also drops JMComic's private cached username/password state.
     */
    fun clearSession() {
        val staleClient = synchronized(this) {
            sharedSessionGeneration.incrementAndGet()
            sharedClient?.also { sharedClient = null }
        }
        staleClient?.client?.setCookies(emptyList())
        staleClient?.client?.let(::closeAsync)
    }

    private fun createClient(
        persistCookies: Boolean,
        loginBusinessCode: ThreadLocal<Int?>,
        clientSessionGeneration: Long?,
    ): JmApiClient {
        val config = JmConfiguration.Builder()
            .clientType(ClientType.API)
            .timeout(Duration.ofSeconds(20))
            .imageTimeout(Duration.ofSeconds(60))
            .downloadThreadPoolSize(2)
            .domainProbeTimeoutMs(3000)
            .build()
        val context = OkHttpBuilder.build(config)
        val domainManager = context.domainManager
        val clientWithCookieInjection = context.client.newBuilder()
            .addInterceptor { chain ->
                val request = if (persistCookies && isCurrentSession(clientSessionGeneration)) {
                    val cookies = cookieStorage.get()
                    if (cookies.isNotEmpty()) {
                        val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                        chain.request().newBuilder()
                            .header("Cookie", cookieHeader)
                            .build()
                    } else {
                        chain.request()
                    }
                } else {
                    chain.request()
                }
                val response = chain.proceed(request)
                captureLoginBusinessCode(request, response, loginBusinessCode)
                persistResponseCookies(request, response, persistCookies, clientSessionGeneration)
                response
            }
            .build()
        val jmClient = JmApiClient(config, clientWithCookieInjection, context.cookieManager, domainManager)

        // 守护线程：域名探活初始化超时后强制解除阻塞，避免 Android 6 上永久卡死。
        // 客户端只在首次 Embedded API 请求时创建，因此不会进入启动关键路径。
        Thread({
            try {
                // 等待 8 秒让域名探活完成
                Thread.sleep(8000)
                if (!domainManager.isInitialized) {
                    log("EmbeddedClientManager: 域名探活初始化超时，强制解除阻塞")
                    domainManager.setInitialized(true)
                }
            } catch (e: InterruptedException) {
                // 忽略
            }
        }, "embedded-domain-init-guard").apply {
            isDaemon = true
            start()
        }

        return jmClient
    }

    private fun isCurrentSession(clientSessionGeneration: Long?): Boolean {
        return clientSessionGeneration == null ||
            sharedSessionGeneration.get() == clientSessionGeneration
    }

    private fun persistResponseCookies(
        request: okhttp3.Request,
        response: okhttp3.Response,
        persistCookies: Boolean,
        clientSessionGeneration: Long?,
    ) {
        if (!persistCookies) return
        synchronized(this) {
            if (!isCurrentSession(clientSessionGeneration)) return
            // 从响应头提取 Set-Cookie，同步到 cookieStorage，保证登录态持久化
            val setCookieHeaders = response.headers("Set-Cookie")
            if (setCookieHeaders.isEmpty()) return
            val newCookies = setCookieHeaders.mapNotNull { Cookie.parse(request.url, it) }
            if (newCookies.isEmpty()) return
            val existing = cookieStorage.get().toMutableList()
            val newKeys = newCookies.map { "${it.domain}:${it.path}:${it.name}" }.toSet()
            existing.removeAll { "${it.domain}:${it.path}:${it.name}" in newKeys }
            existing.addAll(newCookies)
            cookieStorage.set(existing)
        }
    }

    private fun closeAsync(client: JmApiClient) {
        cleanupScope.launch {
            try {
                client.close()
            } catch (e: Exception) {
                log("关闭内置 API 客户端失败：${e.message}")
            }
        }
    }

    /**
     * JmApiResponse validates the JSON business code, while ResponseException.errorCode is built
     * from the HTTP status. Capture the former before the library consumes the response body.
     * JmApiClient.login() is synchronous, so the ThreadLocal associates the code with this login
     * call even when another login is using the shared client concurrently.
     */
    private fun captureLoginBusinessCode(
        request: okhttp3.Request,
        response: okhttp3.Response,
        businessCode: ThreadLocal<Int?>,
    ) {
        if (request.url.pathSegments.lastOrNull() != "login") return
        try {
            val json = JsonParser.parseString(response.peekBody(1024 * 1024L).string()).asJsonObject
            businessCode.set(json.get("code")?.takeUnless { it.isJsonNull }?.asInt)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A malformed/non-JSON response is classified as unknown by the repository.
        }
    }
}

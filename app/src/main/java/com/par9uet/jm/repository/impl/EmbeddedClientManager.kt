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
import java.util.concurrent.atomic.AtomicReference

/**
 * 共享内置 API 客户端管理器。
 * 所有活动认证请求共享同一个 JmApiClient。登录本身在隔离 candidate client 上完成；
 * UserManager 通过 generation 校验后，才把完整认证 cookie 提升到活动客户端，避免登录
 * 网络阶段提前改写 A 的 shared client。这样 POST 请求仍能共享 AVS 会话，同时 transition
 * 期间不会把 shared client 悄悄切成另一个账号。
 *
 * 会话持久化不变量：
 * 1. CookieStorage 只保存“活动会话”的完整 cookie 快照（含 JMComic-Api-Java 登录时根据
 *    JSON 字段 "s" 构造的 AVS cookie），而不是响应 Set-Cookie 的子集。
 * 2. 活动客户端创建时通过 [JmApiClient.setCookies] 恢复会话；候选验证/隔离客户端
 *    （persistCookies = false）从不读写 CookieStorage。
 * 3. 所有可能改动存储/共享客户端的操作都带 session generation 守卫：
 *    clearSession() 使旧 generation 失效，迟到响应或陈旧候选结果无法恢复已清除的会话。
 * 4. 登录成功后的 cookie 提交由 UserManager 在会话锁内完成（activateCandidateSession），
 *    网络请求本身不持有该锁。
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
    private val dohManager: com.par9uet.jm.network.DohManager,
) {
    sealed class EmbeddedLoginResult {
        /**
         * @param sessionCookies 登录完成后从客户端自身 CookieJar 读取的完整认证 cookie
         * 状态。JMComic-Api-Java 1.1.6 在 login() 内部解析 JSON 字段 "s" 并构造 AVS
         * cookie 写入 CookieJar，因此该快照包含 AVS，而响应头 Set-Cookie 不包含。
         */
        data class Success(
            val userInfo: JmUserInfo,
            val sessionCookies: List<Cookie>,
        ) : EmbeddedLoginResult()

        data class Failure(
            val exception: ResponseException,
            val businessCode: Int?,
        ) : EmbeddedLoginResult()
    }

    private data class SharedClient(
        val client: JmApiClient,
        val loginBusinessCode: ThreadLocal<Int?>,
        val clientSessionGeneration: Long,
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
                val client = createClient(
                    persistCookies = true,
                    loginBusinessCode = loginBusinessCode,
                    clientSessionGeneration = sessionGeneration,
                )
                SharedClient(
                    client = client,
                    loginBusinessCode = loginBusinessCode,
                    clientSessionGeneration = sessionGeneration,
                ).also { sharedClient = it }
            }
        }
    }

    /**
     * 候选会话验证：在隔离客户端上验证凭据。成功后返回候选会话的完整 cookie 快照
     * （含 AVS）。候选客户端不读写 CookieStorage、不影响共享客户端，验证完成后即关闭；
     * 是否提升为活动会话由 UserManager 按 generation 决定。
     */
    fun verifyCandidate(
        username: String,
        password: String,
    ): EmbeddedLoginResult {
        val loginBusinessCode = ThreadLocal<Int?>()
        loginBusinessCode.set(null)
        val candidate = createClient(
            persistCookies = false,
            loginBusinessCode = loginBusinessCode,
            clientSessionGeneration = null,
        )
        return try {
            val userInfo = candidate.login(username, password)
            val sessionCookies = candidate.getCookies()
            EmbeddedLoginResult.Success(userInfo, sessionCookies)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            EmbeddedLoginResult.Failure(e, loginBusinessCode.get())
        } finally {
            loginBusinessCode.remove()
            closeAsync(candidate)
        }
    }

    /**
     * 把验证通过的候选会话提升为活动会话：持久化完整 cookie（含 AVS），并同步到已存在的
     * 共享客户端 CookieJar。
     *
     * 调用方（UserManager）必须在持有会话锁、且确认用户 session generation 仍然有效之后
     * 调用；本方法内部再用内置会话 generation 做第二道守卫。
     */
    fun activateCandidateSession(cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val persistenceGeneration = sharedSessionGeneration.get()
        synchronized(this) {
            if (isCurrentSession(persistenceGeneration)) {
                cookieStorage.set(cookies)
            }
        }
        val shared = sharedClient
        if (shared != null && isCurrentSession(shared.clientSessionGeneration)) {
            runCatching { shared.client.setCookies(cookies) }
                .onFailure { log("EmbeddedClientManager: 同步活动客户端 cookie 失败：" + it.message) }
        }
    }

    /**
     * 清除内存中的内置 API 会话（不发送登出请求）。断开客户端也会丢弃 JMComic 私有的
     * username/加密密码缓存，并让旧 generation 失效，之后的迟到响应无法再写入存储。
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
        val clientRef = AtomicReference<JmApiClient?>(null)
        // Route every JMComic API request through the shared DoH resolver while keeping the
        // library's own domain manager, session generation, cookies and AVS handling intact.
        val clientWithCookieInjection = context.client.newBuilder()
            .dns(dohManager)
            .addInterceptor { chain ->
                // 兜底：当 CookieJar 因域名不匹配未能附带 cookie 时，显式注入持久化会话头。
                // （OkHttp BridgeInterceptor 在应用拦截器之后运行：jar 非空时会覆盖该头，
                // jar 为空时该头保留，两者不会重复。）
                val request = if (persistCookies && isCurrentSession(clientSessionGeneration)) {
                    val cookies = cookieStorage.get()
                    if (cookies.isNotEmpty()) {
                        val cookieHeader = cookies.joinToString("; ") { cookie ->
                            cookie.name + "=" + cookie.value
                        }
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
                if (persistCookies && clientSessionGeneration != null &&
                    isCurrentSession(clientSessionGeneration)
                ) {
                    clientRef.get()?.let { syncActiveSessionCookies(it, clientSessionGeneration) }
                }
                response
            }
            .build()
        val jmClient = JmApiClient(config, clientWithCookieInjection, context.cookieManager, domainManager)
        clientRef.set(jmClient)
        if (persistCookies && isCurrentSession(clientSessionGeneration)) {
            // 活动客户端创建时恢复完整持久化会话（含 AVS），进程重启后收藏等认证请求
            // 无需等待网络验证即可使用已恢复的会话。
            restoreSessionIntoClient(jmClient)
        }

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

    /**
     * 把持久化的完整会话（含 AVS）恢复到客户端自身的 CookieJar。
     */
    private fun restoreSessionIntoClient(client: JmApiClient) {
        val storedCookies = cookieStorage.get()
        if (storedCookies.isEmpty()) return
        runCatching { client.setCookies(storedCookies) }
            .onFailure { log("EmbeddedClientManager: 恢复内置 API 会话失败：" + it.message) }
    }

    /**
     * 响应完成后，把客户端 CookieJar 的当前完整快照同步到 cookieStorage（包含 AVS 与
     * 服务器 Set-Cookie 写入 jar 的全部 cookie）。候选客户端不会走到这里。
     */
    private fun syncActiveSessionCookies(client: JmApiClient, clientSessionGeneration: Long) {
        val currentCookies = runCatching { client.getCookies() }.getOrNull() ?: return
        synchronized(this) {
            if (!isCurrentSession(clientSessionGeneration)) return
            if (sameSessionCookies(cookieStorage.get(), currentCookies)) return
            cookieStorage.set(currentCookies)
        }
    }

    private fun sameSessionCookies(a: List<Cookie>, b: List<Cookie>): Boolean {
        if (a.size != b.size) return false
        val aKeys = a.map { cookieKey(it) }.toSet()
        val bKeys = b.map { cookieKey(it) }.toSet()
        return aKeys == bKeys
    }

    private fun cookieKey(cookie: Cookie): String =
        cookie.domain + ":" + cookie.path + ":" + cookie.name + "=" + cookie.value

    private fun isCurrentSession(clientSessionGeneration: Long?): Boolean {
        return clientSessionGeneration != null &&
            sharedSessionGeneration.get() == clientSessionGeneration
    }

    private fun closeAsync(client: JmApiClient) {
        cleanupScope.launch {
            try {
                client.close()
            } catch (e: Exception) {
                log("关闭内置 API 客户端失败：" + e.message)
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

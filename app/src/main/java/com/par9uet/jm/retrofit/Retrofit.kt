package com.par9uet.jm.retrofit

import com.par9uet.jm.retrofit.converter.PrimitiveToRequestBodyConverterFactory
import com.par9uet.jm.retrofit.converter.ResponseConverterFactory
import com.par9uet.jm.retrofit.interceptor.BaseUrlInterceptor
import com.par9uet.jm.retrofit.interceptor.ToastInterceptor
import com.par9uet.jm.retrofit.interceptor.TokenInterceptor
import com.par9uet.jm.storage.CookieStorage
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 活动网络会话 cookie 的清除入口。由 [Retrofit] 实现，会话管理层（UserManager）只依赖
 * 该窄接口，便于单元测试替换。
 */
interface ActiveSessionCookieStore {
    fun clearCookie()
}

class Retrofit(
    baseUrlInterceptor: BaseUrlInterceptor,
    toastInterceptor: ToastInterceptor,
    tokenInterceptor: TokenInterceptor,
    private val scalarsConverterFactory: ScalarsConverterFactory,
    private val responseConverterFactory: ResponseConverterFactory,
    private val primitiveToRequestBodyConverterFactory: PrimitiveToRequestBodyConverterFactory,
    private val cookieStorage: CookieStorage
) : ActiveSessionCookieStore {
    @Volatile
    private var cookieList = listOf<Cookie>()

    @Volatile
    private var cookiesLoaded = false
    private val cookieStateLock = Any()
    private val sessionGeneration = AtomicLong(0L)
    private val requestSessionGeneration = ThreadLocal<Long?>()

    private val cookieJar = object : CookieJar {

        override fun saveFromResponse(
            url: HttpUrl,
            cookies: List<Cookie>
        ) {
            synchronized(cookieStateLock) {
                if (requestSessionGeneration.get() != sessionGeneration.get()) return
                cookieList =
                    (cookieList + cookies).associateBy { "${it.domain}:${it.path}:${it.name}" }.values.toList()
                cookiesLoaded = true
                cookieStorage.set(cookieList)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!cookiesLoaded) {
                synchronized(cookieStateLock) {
                    if (!cookiesLoaded) {
                        cookieList = cookieStorage.get()
                        cookiesLoaded = true
                    }
                }
            }
            return cookieList
        }

    }
    // CookieJar callbacks do not expose the originating Request; carry the request generation
    // through this interceptor so an in-flight pre-clear response cannot repopulate storage.
    private val sessionGenerationInterceptor = Interceptor { chain ->
        val previousGeneration = requestSessionGeneration.get()
        requestSessionGeneration.set(sessionGeneration.get())
        try {
            chain.proceed(chain.request())
        } finally {
            if (previousGeneration == null) {
                requestSessionGeneration.remove()
            } else {
                requestSessionGeneration.set(previousGeneration)
            }
        }
    }
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(baseUrlInterceptor)
            .addInterceptor(tokenInterceptor)
            .addInterceptor(toastInterceptor)
            .addInterceptor(sessionGenerationInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .cookieJar(cookieJar)
            .build()
    }
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://placeholder.com/") // 占位，会在 okhttp 的拦截器中进行动态替换
            .client(okHttpClient)
            .addConverterFactory(scalarsConverterFactory)
            .addConverterFactory(responseConverterFactory)
            .addConverterFactory(primitiveToRequestBodyConverterFactory)
            .build()
    }

    fun <T> createService(cls: Class<T>): T {
        val service = retrofit.create(cls)
        return service
    }

    /** 是否存在可直接恢复的持久化会话（无需等待后台验证即可发认证请求）。 */
    fun hasPersistedSession(): Boolean {
        return cookieList.isNotEmpty() || cookieStorage.get().isNotEmpty()
    }

    /**
     * 创建隔离验证服务：请求不带任何已存 cookie，响应 Set-Cookie 只写入 [captured]，
     * 不会污染活动会话的 CookieJar。验证成功后由会话管理层在 generation 确认后调用
     * [promoteCapturedCookies] 提升。
     */
    fun <T> createCapturingService(cls: Class<T>, captured: CapturingCookieJar): T {
        val isolatedClient = okHttpClient.newBuilder()
            .cookieJar(captured)
            .build()
        return retrofit2.Retrofit.Builder()
            .baseUrl("https://placeholder.com/")
            .client(isolatedClient)
            .addConverterFactory(scalarsConverterFactory)
            .addConverterFactory(responseConverterFactory)
            .addConverterFactory(primitiveToRequestBodyConverterFactory)
            .build()
            .create(cls)
    }

    /**
     * 把隔离验证登录捕获的会话 cookie 合并进活动会话并持久化。
     * 调用方必须在会话锁内确认用户 session generation 仍然有效，避免陈旧验证结果覆盖
     * 更新的登录会话。
     */
    fun promoteCapturedCookies(captured: List<Cookie>) {
        if (captured.isEmpty()) return
        synchronized(cookieStateLock) {
            cookieList =
                (cookieList + captured)
                    .associateBy { c -> c.domain + ":" + c.path + ":" + c.name }
                    .values.toList()
            cookiesLoaded = true
            cookieStorage.set(cookieList)
        }
    }

    override fun clearCookie() {
        synchronized(cookieStateLock) {
            sessionGeneration.incrementAndGet()
            cookieList = listOf()
            cookiesLoaded = true
        }
    }
}

/** 隔离验证服务使用的捕获型 CookieJar：只记录响应 Set-Cookie，不发送任何已存 cookie。 */
class CapturingCookieJar : CookieJar {
    @Volatile
    var capturedCookies: List<Cookie> = emptyList()
        private set

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>
    ) {
        capturedCookies = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
}

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

    override fun clearCookie() {
        synchronized(cookieStateLock) {
            sessionGeneration.incrementAndGet()
            cookieList = listOf()
            cookiesLoaded = true
        }
    }
}

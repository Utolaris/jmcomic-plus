package com.par9uet.jm.retrofit

import com.par9uet.jm.retrofit.converter.PrimitiveToRequestBodyConverterFactory
import com.par9uet.jm.retrofit.converter.ResponseConverterFactory
import com.par9uet.jm.retrofit.interceptor.BaseUrlInterceptor
import com.par9uet.jm.retrofit.interceptor.ToastInterceptor
import com.par9uet.jm.retrofit.interceptor.TokenInterceptor
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.utils.applyTlsCompat
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

class Retrofit(
    baseUrlInterceptor: BaseUrlInterceptor,
    toastInterceptor: ToastInterceptor,
    tokenInterceptor: TokenInterceptor,
    private val scalarsConverterFactory: ScalarsConverterFactory,
    private val responseConverterFactory: ResponseConverterFactory,
    private val primitiveToRequestBodyConverterFactory: PrimitiveToRequestBodyConverterFactory,
    private val cookieStorage: CookieStorage
) {
    @Volatile
    private var cookieList = listOf<Cookie>()

    @Volatile
    private var cookiesLoaded = false

    private val cookieJar = object : CookieJar {

        override fun saveFromResponse(
            url: HttpUrl,
            cookies: List<Cookie>
        ) {
            cookieList =
                (cookieList + cookies).associateBy { "${it.domain}:${it.path}:${it.name}" }.values.toList()
            cookiesLoaded = true
            cookieStorage.set(cookieList)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!cookiesLoaded) {
                synchronized(this) {
                    if (!cookiesLoaded) {
                        cookieList = cookieStorage.get()
                        cookiesLoaded = true
                    }
                }
            }
            return cookieList
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
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .cookieJar(cookieJar)
            .applyTlsCompat()
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

    fun clearCookie() {
        cookieList = listOf()
        cookiesLoaded = true
    }
}

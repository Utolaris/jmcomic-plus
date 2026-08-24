package com.par9uet.jm.retrofit.interceptor

import com.par9uet.jm.retrofit.API_TOKEN_HASH
import com.par9uet.jm.retrofit.API_TS
import com.par9uet.jm.retrofit.API_VERSION
import com.par9uet.jm.retrofit.ApiContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class TokenInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest: Request = chain.request()
        // Retrofit is now scoped to the network Home recommendation/remote configuration path.
        val timestamp = API_TS
        val tokenParam = "${API_TS},${API_VERSION}"

        // 设置 ThreadLocal 供 ResponseConverterFactory 解密使用
        ApiContext.setTimestamp(timestamp)

        val newRequest = originalRequest.newBuilder()
            .addHeader("tokenparam", tokenParam)
            .addHeader("token", API_TOKEN_HASH)
            .build()
        return chain.proceed(newRequest)
    }
}

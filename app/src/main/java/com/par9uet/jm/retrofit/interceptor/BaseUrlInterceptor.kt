package com.par9uet.jm.retrofit.interceptor

import com.par9uet.jm.store.ApiEndpointPreference
import okhttp3.Interceptor
import okhttp3.Response

class BaseUrlInterceptor(
    private val apiEndpointPreference: ApiEndpointPreference,
) : Interceptor {

    private fun getBaseUrl() = apiEndpointPreference.apiEndpoint.value

    override fun intercept(chain: Interceptor.Chain): Response {
        val baseUrl = getBaseUrl()
        var request = chain.request()
        val newUrl = request.url.newBuilder()
            .scheme(baseUrl.split("://")[0]) // 处理 http 或 https
            .host(baseUrl.split("://")[1].removeSuffix("/"))
            .build()

        request = request.newBuilder().url(newUrl).build()
        return chain.proceed(request)
    }
}

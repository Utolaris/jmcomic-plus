package com.par9uet.jm.retrofit

import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.retrofit.converter.PrimitiveToRequestBodyConverterFactory
import com.par9uet.jm.retrofit.converter.ResponseConverterFactory
import com.par9uet.jm.retrofit.interceptor.BaseUrlInterceptor
import com.par9uet.jm.retrofit.interceptor.ToastInterceptor
import com.par9uet.jm.retrofit.interceptor.TokenInterceptor
import com.par9uet.jm.storage.ApiEndpointPreference
import com.par9uet.jm.storage.DohPreferences
import com.par9uet.jm.storage.DohPreferencesEditor
import com.par9uet.jm.storage.DohSettingsState

import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.converter.scalars.ScalarsConverterFactory

class RetrofitCookieIsolationTest {
    @Test fun publicRequestsIgnoreSetCookieAcrossPathsHostsAndLogout() {
        MockWebServer().use { server ->
            server.start()
            val endpoint = object : ApiEndpointPreference {
                override val apiEndpoint = MutableStateFlow("http://localhost")
            }
            val prefs = object : DohPreferences { override val doh = MutableStateFlow(DohSettingsState(enabled = false)) }
            val editor = object : DohPreferencesEditor {
                override fun persistEnabled(enabled: Boolean) = true
                override fun persistAutoStart(enabled: Boolean) = true
                override fun persistServer(serverId: String) = true
                override fun persistCustomServer(name: String, url: String) = true
                override fun persistUseDeviceCertificates(enabled: Boolean) = true
                override fun persistPreferIpv6(enabled: Boolean) = true
            }
            val toast = ToastManager()
            val retrofit = Retrofit(BaseUrlInterceptor(endpoint), ToastInterceptor(toast), TokenInterceptor(),
                ScalarsConverterFactory.create(), ResponseConverterFactory(toast), PrimitiveToRequestBodyConverterFactory(),
                DohManager(prefs, editor))
            for ((index, path) in listOf("/promote", "/setting", "/promote").withIndex()) {
                if (index == 2) { retrofit.clearCookie(); endpoint.apiEndpoint.value = "http://127.0.0.1" }
                server.enqueue(MockResponse().setBody("public data").addHeader("Set-Cookie", "AVS=foreign; Path=/"))
                retrofit.okHttpClient.newCall(Request.Builder().url(server.url(path)).build()).execute().use {
                    assertEquals("public data", it.body!!.string())
                }
                val request = server.takeRequest()
                assertNull(request.getHeader("Cookie"))
                assertNotNull(request.getHeader("token"))
            }
        }
    }
}

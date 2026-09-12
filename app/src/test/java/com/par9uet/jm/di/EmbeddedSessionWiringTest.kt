package com.par9uet.jm.di

import com.par9uet.jm.data.comic.ComicEmbeddedDataSource
import com.par9uet.jm.network.AuthenticatedEmbeddedClient
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.session.UserRepository
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.storage.DohPreferences
import com.par9uet.jm.storage.DohPreferencesEditor
import com.par9uet.jm.storage.DohSettingsState
import com.par9uet.jm.storage.UserStorage
import com.par9uet.jm.core.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Resolves the embedded-API session chain the same way production wires it. A missing Koin
 * definition here used to crash the app at startup (AuthenticatedEmbeddedClient disappeared
 * from the graph while its consumers kept resolving), which no screen-level test catches.
 */
class EmbeddedSessionWiringTest {
    @Test
    fun `embedded client, user repository and embedded data source all resolve`() {
        val app = koinApplication {
            modules(
                userModule,
                comicModule,
                module {
                    single<CookieStorage> { InMemoryCookieStorage() }
                    single<UserStorage> { InMemoryUserStorage() }
                    single { com.par9uet.jm.session.SessionReadinessHolder() }
                    single { DohManager(NoOpDohPreferences(), NoOpDohPreferencesEditor()) }
                    // The home-recommendation service is not under test here; a lazy proxy
                    // only satisfies the RetrofitNetworkHomeDataSource constructor.
                    single<com.par9uet.jm.retrofit.service.ComicService> {
                        java.lang.reflect.Proxy.newProxyInstance(
                            ComicServiceClassLoader.loader,
                            arrayOf(com.par9uet.jm.retrofit.service.ComicService::class.java),
                        ) { _, method, _ -> error("Unexpected ${method.name}") } as com.par9uet.jm.retrofit.service.ComicService
                    }
                },
            )
        }
        val koin = app.koin
        assertNotNull(koin.get<AuthenticatedEmbeddedClient>())
        assertNotNull(koin.get<UserRepository>())
        assertNotNull(koin.get<ComicEmbeddedDataSource>())
        assertNotNull(koin.get<ComicRepository>())
    }

    private class InMemoryCookieStorage : CookieStorage {
        override val state = MutableStateFlow<List<okhttp3.Cookie>?>(null)
        private var cookies: List<okhttp3.Cookie> = emptyList()
        override fun set(cookieStore: List<okhttp3.Cookie>) { cookies = cookieStore }
        override fun get(): List<okhttp3.Cookie> = cookies
        override fun remove() { cookies = emptyList() }
    }

    private class InMemoryUserStorage : UserStorage {
        private var stored: User = User.create()
        override fun get(): User = stored
        override fun set(user: User) { stored = user }
        override fun remove() { stored = User.create() }
    }

    private class NoOpDohPreferences : DohPreferences {
        override val doh = MutableStateFlow(DohSettingsState(enabled = false))
    }

    private class NoOpDohPreferencesEditor : DohPreferencesEditor {
        override fun persistEnabled(enabled: Boolean) = Unit
        override fun persistAutoStart(enabled: Boolean) = Unit
        override fun persistServer(serverId: String) = Unit
        override fun persistCustomServer(name: String, url: String) = Unit
        override fun persistUseDeviceCertificates(enabled: Boolean) = Unit
        override fun persistPreferIpv6(enabled: Boolean) = Unit
    }

    private object ComicServiceClassLoader {
        val loader = com.par9uet.jm.retrofit.service.ComicService::class.java.classLoader
    }
}

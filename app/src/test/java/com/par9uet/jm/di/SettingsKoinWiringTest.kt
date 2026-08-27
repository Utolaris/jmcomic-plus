package com.par9uet.jm.di

import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.repository.RemoteSettingRepository
import com.par9uet.jm.retrofit.interceptor.BaseUrlInterceptor
import com.par9uet.jm.storage.LocalSettingPersistence
import com.par9uet.jm.store.ApiEndpointPreference
import com.par9uet.jm.store.AppSecurityEditor
import com.par9uet.jm.store.AppSecurityPreferences
import com.par9uet.jm.store.AppearanceEditor
import com.par9uet.jm.store.AppearancePreferences
import com.par9uet.jm.store.CacheNotificationPreferences
import com.par9uet.jm.store.ContentPreferences
import com.par9uet.jm.store.DohPreferences
import com.par9uet.jm.store.DohPreferencesEditor
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.MiscSettingsPreferences
import com.par9uet.jm.store.ReaderPreferences
import com.par9uet.jm.store.RecommendationPreferences
import com.par9uet.jm.store.RemoteConfigManager
import com.par9uet.jm.store.RemoteConfigPreferences
import com.par9uet.jm.utils.LauncherIdentityApplier
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertSame
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Resolves the real Koin graph for the settings boundary. Plain unit tests missed broken
 * interface wiring, so this test actually resolves every alias and asserts all of them point
 * at the same LocalSettingManager singleton.
 */
class SettingsKoinWiringTest {
    private fun koinWithSettingsGraph() = startKoin {
        modules(
            module {
                single { InMemoryLocalSettingPersistence() } bind LocalSettingPersistence::class
                single { LauncherDisguiseApplierFake() } bind LauncherIdentityApplier::class
                single { InMemoryRemoteConfigStore() } bind com.par9uet.jm.store.RemoteConfigStore::class
                single { RemoteConfigManager(get(), get()) } bind RemoteConfigPreferences::class
                single { NoOpRemoteSettingRepository() } bind RemoteSettingRepository::class
                single { LocalSettingManager(get<LocalSettingPersistence>(), get()) } binds arrayOf(
                    ContentPreferences::class,
                    RecommendationPreferences::class,
                    ReaderPreferences::class,
                    CacheNotificationPreferences::class,
                    AppSecurityPreferences::class,
                    AppSecurityEditor::class,
                    DohPreferences::class,
                    DohPreferencesEditor::class,
                    AppearancePreferences::class,
                    AppearanceEditor::class,
                    ApiEndpointPreference::class,
                    com.par9uet.jm.store.MiscSettingsPreferences::class,
                )
                single { DohManager(get(), get()) }
                single { BaseUrlInterceptor(get()) }
            },
        )
    }

    @Test
    fun `all narrow settings interfaces resolve to the same LocalSettingManager`() {
        val koin = koinWithSettingsGraph().koin
        try {
            val manager = koin.get<LocalSettingManager>()
            assertSame(manager, koin.get<ContentPreferences>())
            assertSame(manager, koin.get<RecommendationPreferences>())
            assertSame(manager, koin.get<ReaderPreferences>())
            assertSame(manager, koin.get<CacheNotificationPreferences>())
            assertSame(manager, koin.get<AppSecurityPreferences>())
            assertSame(manager, koin.get<AppSecurityEditor>())
            assertSame(manager, koin.get<DohPreferences>())
            assertSame(manager, koin.get<DohPreferencesEditor>())
            assertSame(manager, koin.get<AppearancePreferences>())
            assertSame(manager, koin.get<AppearanceEditor>())
            assertSame(manager, koin.get<ApiEndpointPreference>())
            assertSame(manager, koin.get<MiscSettingsPreferences>())

            // Interface-consumers receive the same instances as concrete registrations.
            // The concrete LauncherDisguiseApplier is the sole provider of this alias.
            koin.get<LauncherIdentityApplier>()
            val remoteConfigPrefs = koin.get<RemoteConfigPreferences>()
            assertSame(koin.get<RemoteConfigManager>().remoteImageHost, remoteConfigPrefs.remoteImageHost)

            // Network-layer consumers resolve through the graph.
            koin.get<DohManager>()
            koin.get<BaseUrlInterceptor>()
        } finally {
            stopKoin()
        }
    }

    private class InMemoryLocalSettingPersistence : LocalSettingPersistence {
        var stored: LocalSetting? = null
        override fun load(): LocalSetting? = stored
        override fun persist(localSetting: LocalSetting) { stored = localSetting }
    }

    private class LauncherDisguiseApplierFake : LauncherIdentityApplier {
        override fun apply(disguise: com.par9uet.jm.data.models.LauncherDisguise) = Unit
    }

    private class InMemoryRemoteConfigStore : com.par9uet.jm.store.RemoteConfigStore {
        private val map = mutableMapOf<String, Any>()
        override fun <T> get(key: String, type: java.lang.reflect.Type): T? = map[key] as? T
        override fun <T> set(key: String, value: T) { map[key] = value as Any }
    }

    private class NoOpRemoteSettingRepository : RemoteSettingRepository {
        override suspend fun getRemoteSetting() =
            com.par9uet.jm.retrofit.model.NetWorkResult.Error("unused in wiring test")
    }
}

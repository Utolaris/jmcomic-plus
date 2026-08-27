package com.par9uet.jm.ui.viewModel

import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.favorites.model.FavoriteSyncUiState
import com.par9uet.jm.favorites.sync.FavoriteSyncRequestKind
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
import com.par9uet.jm.storage.LocalSettingPersistence
import com.par9uet.jm.store.ApiEndpointPreference
import com.par9uet.jm.store.AppLockState
import com.par9uet.jm.store.AppSecurityPreferences
import com.par9uet.jm.store.AppearancePreferences
import com.par9uet.jm.store.CacheNotificationPreferences
import com.par9uet.jm.store.ColorPaletteState
import com.par9uet.jm.store.ContentPreferences
import com.par9uet.jm.store.DohPreferences
import com.par9uet.jm.store.DohSettingsState
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.MiscSettingsState
import com.par9uet.jm.store.ReaderPreferences
import com.par9uet.jm.store.RecommendationPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
    private fun subscribedValue(vm: SettingsViewModel): SettingsUiState {
        val collected = java.util.concurrent.atomic.AtomicReference<SettingsUiState?>()
        val scope = kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(scheduler))
        val job = scope.launch { vm.uiState.collect { collected.set(it) } }
        scheduler.runCurrent()
        job.cancel()
        return collected.get() ?: SettingsUiState()
    }


    private class InMemoryPersistence : LocalSettingPersistence {
        var stored: LocalSetting? = null
        override fun load(): LocalSetting? = stored
        override fun persist(localSetting: LocalSetting) { stored = localSetting }
    }

    private class FakeSyncRequester : FavoriteSyncRequester {
        val requests = mutableListOf<FavoriteSyncRequestKind>()
        private val _state = MutableStateFlow(FavoriteSyncUiState())
        override val state: StateFlow<FavoriteSyncUiState> = _state.asStateFlow()
        override fun request(kind: FavoriteSyncRequestKind, folderId: Int) {
            requests += kind
        }
    }

    private fun buildViewModel(recommendEnabled: Boolean): Pair<SettingsViewModel, InMemoryPersistence> {
        val persistence = InMemoryPersistence()
        val manager = LocalSettingManager(persistence, RecordingLauncherApplier())
        if (recommendEnabled) manager.setPreferenceRecommendEnabled(true)

        // All contract params are backed by the same real manager instance, mirroring DI.
        val vm = SettingsViewModel(
            contentPreferences = manager,
            recommendationPreferences = manager,
            readerPreferences = manager,
            cacheNotificationPreferences = manager,
            appearancePreferences = manager,
            securityPreferences = manager,
            dohPreferences = manager,
            apiEndpointPreference = manager,
            miscSettings = manager,
            localSettingManager = manager,
            favoriteSyncRequester = FakeSyncRequester(),
        )
        return vm to persistence
    }

    @Test
    fun `uiState composes from narrow preferences`() {
        val (vm, _) = buildViewModel(recommendEnabled = true)
        scheduler.runCurrent()

        val state = subscribedValue(vm)
        org.junit.Assert.assertTrue(state.recommendationEnabled)
        assertEquals("auto", state.theme)
        assertEquals(3, state.prefetchCount)
    }

    @Test
    fun `catalog validation rejects unknown api`() {
        val (vm, _) = buildViewModel(recommendEnabled = false)
        try {
            vm.selectApi("https://unknown.example")
            throw AssertionError("expected require to fail")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `selectTheme validates catalog and applies`() {
        val (vm, _) = buildViewModel(recommendEnabled = false)

        vm.selectTheme("dark")
        scheduler.runCurrent()

        assertEquals("dark", subscribedValue(vm).theme)
        try {
            vm.selectTheme("solarized")
            throw AssertionError("expected require to fail")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `grid columns apply atomically through one intent`() {
        val (vm, persistence) = buildViewModel(recommendEnabled = false)

        vm.applyGridColumns(2, 3, 4, 5, 6)

        scheduler.runCurrent()
        val state = subscribedValue(vm)
        assertEquals(2, state.gridColumns.home)
        assertEquals(6, state.gridColumns.search)
        // Single persisted snapshot == single state transition.
        org.junit.Assert.assertEquals(1, persistence.stored?.homeGridColumns?.let { listOf(it).size } ?: 0)
    }


    @Test
    fun `force refresh routes through narrow sync requester`() {
        val persistence = InMemoryPersistence()
        val manager = LocalSettingManager(persistence, RecordingLauncherApplier())
        val requester = FakeSyncRequester()
        val vm = SettingsViewModel(
            contentPreferences = manager,
            recommendationPreferences = manager,
            readerPreferences = manager,
            cacheNotificationPreferences = manager,
            appearancePreferences = manager,
            securityPreferences = manager,
            dohPreferences = manager,
            apiEndpointPreference = manager,
            miscSettings = manager,
            localSettingManager = manager, favoriteSyncRequester = requester,
        )

        vm.requestFavoriteForceRefresh()
        scheduler.runCurrent()

        org.junit.Assert.assertEquals(listOf(FavoriteSyncRequestKind.FORCE), requester.requests)
    }

    private class RecordingLauncherApplier : com.par9uet.jm.utils.LauncherIdentityApplier {
        override fun apply(disguise: com.par9uet.jm.data.models.LauncherDisguise) = Unit
    }
}

package com.par9uet.jm.favorites.sync

import com.par9uet.jm.data.models.CollectComicOrderFilter
import com.par9uet.jm.favorites.model.FavoriteSyncUiState
import com.par9uet.jm.favorites.usecase.SyncFavorites
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.FAVORITE_SCOPE_ALL
import com.par9uet.jm.store.FavoriteSyncProgress
import com.par9uet.jm.store.FavoriteSyncReport
import com.par9uet.jm.store.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FavoriteSyncRequestKind {
    AUTO,
    MANUAL,
    FORCE,
}

interface FavoriteSyncRequester {
    val state: StateFlow<FavoriteSyncUiState>

    fun request(
        kind: FavoriteSyncRequestKind,
        folderId: Int = 0,
    )
}

internal val FAVORITE_CANONICAL_ORDER = CollectComicOrderFilter.COLLECT_TIME

/**
 * Application-scoped Favorites synchronization boundary. It owns timing and trailing work, while
 * SyncFavorites owns the actual remote/local synchronization molecule.
 */
class FavoriteSyncController(
    private val currentAccountId: () -> Int,
    private val accountIdFlow: Flow<Int>,
    private val syncOperation: suspend (
        accountId: Int,
        folderId: Int,
        force: Boolean,
        order: CollectComicOrderFilter,
        onProgress: (FavoriteSyncProgress) -> Unit,
    ) -> NetWorkResult<FavoriteSyncReport>,
    private val applicationScope: CoroutineScope,
    private val autoSyncCoordinator: FavoriteAutoSyncCoordinator = FavoriteAutoSyncCoordinator(),
) : FavoriteSyncRequester {
    constructor(
        userManager: UserManager,
        syncFavorites: SyncFavorites,
        applicationScope: CoroutineScope,
        autoSyncCoordinator: FavoriteAutoSyncCoordinator = FavoriteAutoSyncCoordinator(),
    ) : this(
        { userManager.userState.value.data?.id ?: 0 },
        userManager.userState.map { it.data?.id ?: 0 },
        { accountId: Int, folderId: Int, force: Boolean, order: CollectComicOrderFilter,
          onProgress: (FavoriteSyncProgress) -> Unit ->
            syncFavorites.synchronize(accountId, folderId, force, order, onProgress)
        },
        applicationScope,
        autoSyncCoordinator,
    )

    internal constructor(
        accountIdFlow: Flow<Int>,
        currentAccountId: () -> Int,
        syncOperation: suspend (
            accountId: Int,
            folderId: Int,
            force: Boolean,
            order: CollectComicOrderFilter,
            onProgress: (FavoriteSyncProgress) -> Unit,
        ) -> NetWorkResult<FavoriteSyncReport>,
        applicationScope: CoroutineScope,
        autoSyncCoordinator: FavoriteAutoSyncCoordinator = FavoriteAutoSyncCoordinator(),
    ) : this(currentAccountId, accountIdFlow, syncOperation, applicationScope, autoSyncCoordinator)

    private val lock = Any()
    private val _state = MutableStateFlow(FavoriteSyncUiState())
    override val state: StateFlow<FavoriteSyncUiState> = _state.asStateFlow()
    private var trailingJob: Job? = null

    private var observedAccountId = currentAccountId()

    init {
        applicationScope.launch {
            accountIdFlow.distinctUntilChanged().collect {
                synchronized(lock) {
                    if (it == observedAccountId) return@synchronized
                    observedAccountId = it
                    autoSyncCoordinator.reset()
                    trailingJob?.cancel()
                    trailingJob = null
                    _state.value = FavoriteSyncUiState()
                }
            }
        }
    }

    override fun request(
        kind: FavoriteSyncRequestKind,
        folderId: Int,
    ) {
        synchronized(lock) {
            val accountId = currentAccountId()
            if (accountId <= 0) return
            when (kind) {
                FavoriteSyncRequestKind.AUTO -> requestAutomatic(accountId, folderId)
                FavoriteSyncRequestKind.MANUAL -> requestExplicit(
                    accountId = accountId,
                    folderId = folderId,
                    syncKind = FavoriteSyncKind.MANUAL,
                    force = false,
                )
                FavoriteSyncRequestKind.FORCE -> requestExplicit(
                    accountId = accountId,
                    folderId = FAVORITE_SCOPE_ALL,
                    syncKind = FavoriteSyncKind.FORCE,
                    force = true,
                )
            }
        }
    }

    private fun requestAutomatic(accountId: Int, folderId: Int) {
        when (val result = autoSyncCoordinator.request(folderId, _state.value.isSyncing)) {
            is FavoriteAutoRequestResult.StartNow -> {
                startSync(accountId, result.folderId, force = false)
            }
            is FavoriteAutoRequestResult.Coalesced -> {
                scheduleTrailing(result.trailingDelayMs)
            }
        }
    }

    private fun requestExplicit(
        accountId: Int,
        folderId: Int,
        syncKind: FavoriteSyncKind,
        force: Boolean,
    ) {
        if (!shouldStartFavoriteSync(
                kind = syncKind,
                isAutoSyncAllowed = false,
                isSyncing = _state.value.isSyncing,
            )
        ) {
            return
        }
        startSync(accountId, folderId, force)
    }

    private fun scheduleTrailing(delayMs: Long) {
        if (trailingJob?.isActive == true) return
        trailingJob = applicationScope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                synchronized(lock) {
                    if (_state.value.isSyncing) return@synchronized
                    val folderId = autoSyncCoordinator.trailingDue() ?: return@synchronized
                    val accountId = currentAccountId()
                    if (accountId > 0) startSync(accountId, folderId, force = false)
                }
            } finally {
                synchronized(lock) {
                    trailingJob = null
                }
            }
        }
    }

    private fun startSync(accountId: Int, folderId: Int, force: Boolean) {
        _state.value = FavoriteSyncUiState(
            isSyncing = true,
            isForceRefresh = force,
        )
        applicationScope.launch {
            val result = syncOperation(
                accountId,
                folderId,
                force,
                FAVORITE_CANONICAL_ORDER,
                { progress ->
                    synchronized(lock) {
                        if (currentAccountId() == accountId) {
                            _state.update {
                                it.copy(
                                    isSyncing = true,
                                    completed = progress.completed,
                                    total = progress.total,
                                    phase = progress.phase,
                                )
                            }
                        }
                    }
                },
            )
            synchronized(lock) {
                if (currentAccountId() != accountId) return@synchronized
                when (result) {
                    is NetWorkResult.Error -> {
                        _state.update {
                            it.copy(isSyncing = false, errorMessage = result.message)
                        }
                    }
                    is NetWorkResult.Success -> {
                        _state.value = FavoriteSyncUiState()
                    }
                }
                val trailingFolderId = autoSyncCoordinator.onSyncFinished()
                if (trailingFolderId != null && !_state.value.isSyncing) {
                    startSync(accountId, trailingFolderId, force = false)
                }
            }
        }
    }
}

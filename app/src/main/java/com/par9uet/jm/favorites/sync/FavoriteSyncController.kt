package com.par9uet.jm.favorites.sync

import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.favorites.data.FavoriteSessionSnapshot
import com.par9uet.jm.favorites.model.FavoriteSyncUiState
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.FAVORITE_SCOPE_ALL
import com.par9uet.jm.store.FavoriteSyncProgress
import com.par9uet.jm.store.FavoriteSyncReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FavoriteSyncRequestKind {
    AUTO,
    MANUAL,
    FORCE,
}

interface FavoriteSyncRequester {
    val state: StateFlow<FavoriteSyncUiState>

    fun request(kind: FavoriteSyncRequestKind, folderId: Int = 0)
}

/** Owns the application sync job, its session, progress and automatic trailing requests. */
class FavoriteSyncController(
    private val session: FavoriteSession,
    private val syncOperation: suspend (
        snapshot: FavoriteSessionSnapshot,
        folderId: Int,
        force: Boolean,
        onProgress: (FavoriteSyncProgress) -> Unit,
    ) -> NetWorkResult<FavoriteSyncReport>,
    private val applicationScope: CoroutineScope,
    private val autoSyncCoordinator: FavoriteAutoSyncCoordinator = FavoriteAutoSyncCoordinator(),
) : FavoriteSyncRequester {
    private val lock = Any()
    private val _state = MutableStateFlow(FavoriteSyncUiState())
    override val state: StateFlow<FavoriteSyncUiState> = _state.asStateFlow()
    private var observedSession = session.snapshot()
    private var requestGeneration = 0L
    private var syncJob: Job? = null
    private var trailingJob: Job? = null

    init {
        applicationScope.launch {
            session.sessionFlow.distinctUntilChanged().collect {
                synchronized(lock) { refreshSession() }
            }
        }
    }

    override fun request(kind: FavoriteSyncRequestKind, folderId: Int) {
        synchronized(lock) {
            refreshSession()
            if (observedSession.accountId <= 0) return
            when (kind) {
                FavoriteSyncRequestKind.AUTO -> {
                    when (val result = autoSyncCoordinator.request(folderId, _state.value.isSyncing)) {
                        is FavoriteAutoRequestResult.StartNow -> startSync(result.folderId, force = false)
                        is FavoriteAutoRequestResult.Coalesced -> scheduleTrailing(result.trailingDelayMs)
                    }
                }
                // Explicit refresh bypasses the automatic interval. Repeated taps during a sync
                // are ignored; only automatic requests retain trailing work.
                FavoriteSyncRequestKind.MANUAL, FavoriteSyncRequestKind.FORCE -> {
                    if (_state.value.isSyncing) return
                    val force = kind == FavoriteSyncRequestKind.FORCE
                    startSync(if (force) FAVORITE_SCOPE_ALL else folderId, force)
                }
            }
        }
    }

    private fun refreshSession() {
        val current = session.snapshot()
        if (current == observedSession) return
        observedSession = current
        requestGeneration++
        syncJob?.cancel()
        syncJob = null
        trailingJob?.cancel()
        trailingJob = null
        autoSyncCoordinator.reset()
        _state.value = FavoriteSyncUiState()
    }

    private fun scheduleTrailing(delayMs: Long) {
        if (trailingJob?.isActive == true) return
        val snapshot = observedSession
        lateinit var job: Job
        job = applicationScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (delayMs > 0) delay(delayMs)
                synchronized(lock) {
                    if (!session.isCurrent(snapshot) || _state.value.isSyncing) return@synchronized
                    val folderId = autoSyncCoordinator.trailingDue() ?: return@synchronized
                    startSync(folderId, force = false)
                }
            } finally {
                synchronized(lock) {
                    if (trailingJob === job) trailingJob = null
                }
            }
        }
        trailingJob = job
        job.start()
    }

    private fun startSync(folderId: Int, force: Boolean) {
        val snapshot = observedSession
        val generation = ++requestGeneration
        _state.value = FavoriteSyncUiState(isSyncing = true, isForceRefresh = force)
        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            var errorMessage: String? = null
            try {
                val result = syncOperation(snapshot, folderId, force) { progress ->
                    synchronized(lock) {
                        if (isCurrentRequest(snapshot, generation)) {
                            _state.update {
                                it.copy(completed = progress.completed, total = progress.total, phase = progress.phase)
                            }
                        }
                    }
                }
                if (result is NetWorkResult.Error) errorMessage = result.message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = error.message ?: "同步收藏夹失败"
            } finally {
                synchronized(lock) {
                    if (isCurrentRequest(snapshot, generation)) {
                        syncJob = null
                        _state.value = if (errorMessage == null) {
                            FavoriteSyncUiState()
                        } else {
                            _state.value.copy(isSyncing = false, errorMessage = errorMessage)
                        }
                        autoSyncCoordinator.onSyncFinished()?.let { startSync(it, force = false) }
                    }
                }
            }
        }
        syncJob = job
        job.start()
    }

    private fun isCurrentRequest(snapshot: FavoriteSessionSnapshot, generation: Long): Boolean =
        requestGeneration == generation && session.isCurrent(snapshot)
}

package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.CollectComicOrderFilter
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.SignInData
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.repository.LoginSession
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.SignInDataResponse
import com.par9uet.jm.retrofit.model.SignInResponse
import com.par9uet.jm.store.DownloadManager
import com.par9uet.jm.store.FAVORITE_SCOPE_ALL
import com.par9uet.jm.store.FavoriteAutoRequestResult
import com.par9uet.jm.store.FavoriteAutoSyncCoordinator
import com.par9uet.jm.store.FavoriteSyncKind
import com.par9uet.jm.store.FavoriteStore
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.store.shouldStartFavoriteSync
import com.par9uet.jm.ui.models.CommonUIState
import com.par9uet.jm.ui.pagingSource.CollectComicPagingSource
import com.par9uet.jm.ui.pagingSource.HistoryComicPagingSource
import com.par9uet.jm.ui.pagingSource.HistoryCommentPagingSource
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectComicLocalFilter(
    val searchText: String = "",
    val selectedTags: Set<String> = emptySet(),
    val selectedAuthors: Set<String> = emptySet(),
    val tagLogic: TagFilterLogic = TagFilterLogic.AND
)

data class CollectEditState(
    val editing: Boolean = false,
    val selectedComicIds: Set<Int> = emptySet()
)

data class HistoryEditState(
    val editing: Boolean = false,
    val selectedComicIds: Set<Int> = emptySet()
)

private data class CollectPagerKey(
    val accountId: Int,
    val blockedTagList: List<String>,
    val filter: CollectComicLocalFilter,
    val folderId: Int
)

internal val FAVORITE_CANONICAL_ORDER = CollectComicOrderFilter.COLLECT_TIME

data class FavoriteSyncUiState(
    val isSyncing: Boolean = false,
    val isForceRefresh: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val phase: String = "",
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class UserViewModel(
    private val userManager: UserManager,
    private val userRepository: UserRepository,
    private val toastManager: ToastManager,
    private val localSettingManager: LocalSettingManager,
    private val comicRepository: ComicRepository,
    private val downloadManager: DownloadManager,
    private val favoriteStore: FavoriteStore,
) : ViewModel() {
    private val _loginState = MutableStateFlow(CommonUIState(data = null))
    val loginState = _loginState.asStateFlow()
    fun login(username: String, password: String) {
        viewModelScope.launch {
            _loginState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = userManager.login(username, password)) {
                is NetWorkResult.Error -> {
                    _loginState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<LoginSession> -> {
                    // UserManager persists the identity through a generation-checked commit, so
                    // a manual login cannot be overwritten by the startup verifier.
                }
            }
            _loginState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            userManager.clearUser()
        }
    }

    private val _collectComicFilter = MutableStateFlow(CollectComicLocalFilter())
    val collectComicFilter = _collectComicFilter.asStateFlow()
    private val _selectedFolderId = MutableStateFlow(0)
    val selectedFolderId = _selectedFolderId.asStateFlow()
    private val _collectEditState = MutableStateFlow(CollectEditState())
    val collectEditState = _collectEditState.asStateFlow()
    private val _favoriteSyncState = MutableStateFlow(FavoriteSyncUiState())
    val favoriteSyncState = _favoriteSyncState.asStateFlow()
    private val autoSyncCoordinator = FavoriteAutoSyncCoordinator()
    // Exactly one scheduled trailing automatic sync may exist at a time.
    private var trailingAutoSyncJob: Job? = null

    private val accountIdFlow = userManager.userState.map { it.data?.id ?: 0 }

    init {
        viewModelScope.launch {
            accountIdFlow.distinctUntilChanged().collect {
                _selectedFolderId.value = 0
                _favoriteSyncState.value = FavoriteSyncUiState()
                autoSyncCoordinator.reset()
                trailingAutoSyncJob?.cancel()
                trailingAutoSyncJob = null
            }
        }
    }

    val folderList = accountIdFlow.flatMapLatest { accountId ->
        if (accountId <= 0) kotlinx.coroutines.flow.flowOf(emptyMap())
        else favoriteStore.observeFolders(accountId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val collectTagCounts = combine(
        accountIdFlow,
        _selectedFolderId,
    ) { accountId, folderId -> accountId to folderId }
        .flatMapLatest { (accountId, folderId) ->
            if (accountId <= 0) kotlinx.coroutines.flow.flowOf(emptyMap())
            else favoriteStore.observeTagCounts(accountId, folderId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val collectAuthorCounts = combine(
        accountIdFlow,
        _selectedFolderId,
    ) { accountId, folderId -> accountId to folderId }
        .flatMapLatest { (accountId, folderId) ->
            if (accountId <= 0) kotlinx.coroutines.flow.flowOf(emptyMap())
            else favoriteStore.observeAuthorCounts(accountId, folderId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val collectComicPager = combine(
        localSettingManager.localSettingState,
        _collectComicFilter,
        _selectedFolderId,
        accountIdFlow,
    ) { localSetting, filter, folderId, accountId ->
        CollectPagerKey(accountId, localSetting.blockedTagList, filter, folderId)
    }.flatMapLatest { key ->
        Pager(
            config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
            pagingSourceFactory = {
                CollectComicPagingSource(
                    favoriteStore.pagingSource(
                        accountId = key.accountId,
                        blockedTagList = key.blockedTagList,
                        searchText = key.filter.searchText,
                        selectedTags = key.filter.selectedTags,
                        selectedAuthors = key.filter.selectedAuthors,
                        folderId = key.folderId,
                        tagLogic = key.filter.tagLogic,
                    )
                )
            }
        ).flow
    }.cachedIn(viewModelScope)

    fun updateCollectSearchText(value: String) {
        _collectComicFilter.update { it.copy(searchText = value) }
    }

    fun updateCollectSelectedTags(tags: Set<String>) {
        _collectComicFilter.update { it.copy(selectedTags = tags) }
    }

    fun updateCollectTagLogic(logic: TagFilterLogic) {
        _collectComicFilter.update { it.copy(tagLogic = logic) }
    }

    fun updateCollectSelectedAuthors(authors: Set<String>) {
        _collectComicFilter.update { it.copy(selectedAuthors = authors) }
    }

    fun changeFolder(folderId: Int) {
        clearCollectSelection()
        _selectedFolderId.update { folderId }
        requestFavoriteAutoSync(folderId)
    }

    fun enterCollectEdit(comicId: Int) {
        _collectEditState.update {
            it.copy(editing = true, selectedComicIds = it.selectedComicIds + comicId)
        }
    }

    fun toggleCollectSelected(comicId: Int) {
        _collectEditState.update {
            val selected = if (comicId in it.selectedComicIds) {
                it.selectedComicIds - comicId
            } else {
                it.selectedComicIds + comicId
            }
            it.copy(editing = selected.isNotEmpty(), selectedComicIds = selected)
        }
    }

    fun clearCollectSelection() {
        _collectEditState.update { CollectEditState() }
    }

    fun deleteCollectedComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        viewModelScope.launch {
            var success = 0
            var fail = 0
            comics.forEach { comic ->
                when (comicRepository.unCollectComic(comic.id)) {
                    is NetWorkResult.Error -> fail++
                    is NetWorkResult.Success -> {
                        success++
                        userRepository.removeCachedFavoriteComic(currentAccountId(), comic.id)
                    }
                }
            }
            toastManager.showAsync(
                if (fail == 0) "已取消收藏 $success 部漫画"
                else "成功 $success 部，失败 $fail 部"
            )
            clearCollectSelection()
        }
    }

    fun cacheCollectedComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        downloadManager.downloadComics(comics)
        clearCollectSelection()
    }

    fun moveCollectedToFolder(comics: List<Comic>, folderId: String) {
        if (comics.isEmpty()) return
        viewModelScope.launch {
            var success = 0
            var fail = 0
            comics.forEach { comic ->
                when (comicRepository.moveComicToFolder(comic.id, folderId)) {
                    is NetWorkResult.Error -> fail++
                    is NetWorkResult.Success -> {
                        success++
                        userRepository.moveCachedFavoriteComic(
                            currentAccountId(),
                            comic.id,
                            folderId.toIntOrNull() ?: 0,
                        )
                    }
                }
            }
            toastManager.showAsync(
                if (fail == 0) "已移动 $success 部漫画"
                else "成功 $success 部，失败 $fail 部"
            )
            clearCollectSelection()
        }
    }

    fun refreshFolderList() {
        requestFavoriteAutoSync()
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            when (val data = comicRepository.createFavoriteFolder(name)) {
                is NetWorkResult.Error -> toastManager.showAsync(data.message)
                is NetWorkResult.Success -> {
                    requestFavoriteAutoSync()
                    toastManager.showAsync("创建成功")
                }
            }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            when (val data = comicRepository.deleteFavoriteFolder(folderId)) {
                is NetWorkResult.Error -> toastManager.showAsync(data.message)
                is NetWorkResult.Success -> {
                    userRepository.removeCachedFavoriteFolder(
                        currentAccountId(),
                        folderId.toIntOrNull() ?: 0,
                    )
                    _selectedFolderId.update { 0 }
                    requestFavoriteAutoSync()
                    toastManager.showAsync("删除成功")
                }
            }
        }
    }

    fun renameFolder(folderId: String, newName: String) {
        viewModelScope.launch {
            when (val data = comicRepository.renameFavoriteFolder(folderId, newName)) {
                is NetWorkResult.Error -> toastManager.showAsync(data.message)
                is NetWorkResult.Success -> {
                    userRepository.renameCachedFavoriteFolder(
                        currentAccountId(),
                        folderId.toIntOrNull() ?: 0,
                        newName.trim(),
                    )
                    requestFavoriteAutoSync()
                    toastManager.showAsync("重命名成功")
                }
            }
        }
    }

    /**
     * Background/automatic favorites synchronization, globally throttled to at most once
     * every 30 seconds per account. Does nothing while another sync is already running.
     */
    /**
     * Background/automatic favorites synchronization with leading + trailing coalescing:
     * the first eligible request starts immediately, further requests inside the rolling
     * 30-second window are coalesced into exactly one trailing sync (latest requested folder
     * wins), and requests during an in-flight sync are retained, never run in parallel.
     */
    fun requestFavoriteAutoSync(folderId: Int = _selectedFolderId.value) {
        val accountId = currentAccountId()
        if (accountId <= 0) return
        when (val result = autoSyncCoordinator.request(folderId, _favoriteSyncState.value.isSyncing)) {
            is FavoriteAutoRequestResult.StartNow -> {
                launchFavoriteSync(accountId, result.folderId, force = false)
            }
            is FavoriteAutoRequestResult.Coalesced -> {
                scheduleTrailingAutoSync(result.trailingDelayMs)
            }
        }
    }

    private fun scheduleTrailingAutoSync(delayMs: Long) {
        if (trailingAutoSyncJob?.isActive == true) return
        trailingAutoSyncJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            if (_favoriteSyncState.value.isSyncing) return@launch
            val folderId = autoSyncCoordinator.trailingDue() ?: return@launch
            val accountId = currentAccountId()
            if (accountId <= 0) return@launch
            launchFavoriteSync(accountId, folderId, force = false)
        }
    }

    /**
     * Explicit user sync (Favorites top-right button). Bypasses the automatic 30-second gate.
     */
    fun requestFavoriteManualSync(folderId: Int = _selectedFolderId.value) {
        val accountId = currentAccountId()
        if (accountId <= 0) return
        if (!shouldStartFavoriteSync(
                kind = FavoriteSyncKind.MANUAL,
                isAutoSyncAllowed = false,
                isSyncing = _favoriteSyncState.value.isSyncing,
            )
        ) {
            return
        }
        launchFavoriteSync(accountId, folderId, force = false)
    }

    private fun launchFavoriteSync(accountId: Int, folderId: Int, force: Boolean) {
        // Claim synchronously so a second caller cannot observe isSyncing == false twice.
        _favoriteSyncState.update {
            FavoriteSyncUiState(isSyncing = true, isForceRefresh = force)
        }
        viewModelScope.launch {
            val result = userRepository.synchronizeFavorites(
                accountId = accountId,
                folderId = folderId,
                force = force,
                order = FAVORITE_CANONICAL_ORDER,
                onProgress = { progress ->
                    if (currentAccountId() == accountId) {
                        _favoriteSyncState.update {
                            it.copy(
                                isSyncing = true,
                                completed = progress.completed,
                                total = progress.total,
                                phase = progress.phase,
                            )
                        }
                    }
                },
            )
            if (currentAccountId() != accountId) return@launch
            when (result) {
                is NetWorkResult.Error -> {
                    _favoriteSyncState.update {
                        it.copy(isSyncing = false, errorMessage = result.message)
                    }
                }
                is NetWorkResult.Success -> {
                    _favoriteSyncState.value = FavoriteSyncUiState()
                }
            }
            // A coalesced automatic request may have become due while this sync ran; run the
            // single trailing sync now (it can never overlap: state was just cleared).
            val trailingFolderId = autoSyncCoordinator.onSyncFinished()
            if (trailingFolderId != null && !_favoriteSyncState.value.isSyncing) {
                launchFavoriteSync(accountId, trailingFolderId, force = false)
            }
        }
    }

    fun forceRefreshFavorites() {
        val accountId = currentAccountId()
        if (accountId <= 0) return
        if (!shouldStartFavoriteSync(
                kind = FavoriteSyncKind.FORCE,
                isAutoSyncAllowed = false,
                isSyncing = _favoriteSyncState.value.isSyncing,
            )
        ) {
            return
        }
        launchFavoriteSync(accountId, folderId = FAVORITE_SCOPE_ALL, force = true)
    }

    private fun currentAccountId(): Int = userManager.userState.value.data?.id ?: 0

    private val _historyRefreshVersion = MutableStateFlow(0)

    /**
     * Bumps the history pager generation. Used by the delete-success path (immediate refresh)
     * and, on the screen side, by the entry/resume lifecycle refresh.
     */
    fun refreshHistoryComicPager() {
        _historyRefreshVersion.update { it + 1 }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val historyComicPager = combine(
        localSettingManager.localSettingState,
        _historyRefreshVersion
    ) { localSetting, _ -> localSetting }
        .flatMapLatest { localSetting ->
        Pager(
            config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
            pagingSourceFactory = {
                HistoryComicPagingSource(
                    userRepository,
                    localSetting.blockedTagList
                )
            }
        ).flow
    }.cachedIn(viewModelScope)

    private val _historyEditState = MutableStateFlow(HistoryEditState())
    val historyEditState = _historyEditState.asStateFlow()

    fun enterHistoryEdit(comicId: Int) {
        _historyEditState.update {
            it.copy(editing = true, selectedComicIds = it.selectedComicIds + comicId)
        }
    }

    fun toggleHistorySelected(comicId: Int) {
        _historyEditState.update {
            val selected = if (comicId in it.selectedComicIds) {
                it.selectedComicIds - comicId
            } else {
                it.selectedComicIds + comicId
            }
            it.copy(editing = selected.isNotEmpty(), selectedComicIds = selected)
        }
    }

    fun clearHistorySelection() {
        _historyEditState.update { HistoryEditState() }
    }

    fun deleteHistoryComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        log("UserViewModel", "deleteHistoryComics: 开始删除 ${comics.size} 条历史记录, ids=${comics.map { it.id }}")
        viewModelScope.launch {
            var success = 0
            var fail = 0
            val errors = mutableListOf<String>()
            comics.forEach { comic ->
                log("UserViewModel", "deleteHistoryComics: 正在删除 comic.id=${comic.id}")
                when (val result = userRepository.deleteHistoryComic(comic.id)) {
                    is NetWorkResult.Error -> {
                        logError(
                            "UserViewModel",
                            "deleteHistoryComics: 删除 comic.id=${comic.id} 失败: ${result.message}"
                        )
                        errors += result.message
                        fail++
                    }
                    is NetWorkResult.Success -> success++
                }
            }
            log("UserViewModel", "deleteHistoryComics: 完成, 成功=$success, 失败=$fail")
            val message = when {
                fail == 0 -> "已删除 $success 条历史记录"
                success == 0 -> errors.firstOrNull() ?: "删除失败"
                else -> "成功 $success 条，失败 $fail 条：${errors.firstOrNull().orEmpty()}"
            }
            toastManager.showAsync(message)
            if (success > 0) {
                _historyRefreshVersion.update { it + 1 }
            }
            clearHistorySelection()
        }
    }

    fun cacheHistoryComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        downloadManager.downloadComics(comics)
        clearHistorySelection()
    }

    val historyCommentPager = Pager(
        config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
        pagingSourceFactory = {
            HistoryCommentPagingSource(
                userRepository,
                userManager.userState.value.data?.id ?: 0
            )
        }
    ).flow.cachedIn(viewModelScope)

    private val _signInDataState = MutableStateFlow(
        CommonUIState<SignInData>(
            isLoading = true
        )
    )
    val signDataState = _signInDataState.asStateFlow()
    fun getSignInData() {
        viewModelScope.launch {
            _signInDataState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = userRepository.getSignData(userManager.userState.value.data?.id ?: 0)) {
                is NetWorkResult.Error -> {
                    _signInDataState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<SignInDataResponse> -> {
                    _signInDataState.update {
                        it.copy(
                            data = data.data.toSignData()
                        )
                    }
                }
            }
            _signInDataState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }

    private val _signInState = MutableStateFlow(CommonUIState<String>())
    val signInState = _signInState.asStateFlow()
    fun signIn() {
        viewModelScope.launch {
            _signInState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = userRepository.signIn(
                userManager.userState.value.data?.id ?: 0,
                _signInDataState.value.data?.dailyId ?: 0
            )) {
                is NetWorkResult.Error -> {
                    _signInState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<SignInResponse> -> {
                    toastManager.showAsync(data.data.msg)
                    getSignInData()
                    _signInState.update {
                        it.copy(
                            data = data.data.msg
                        )
                    }
                }
            }
            _signInState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }
}

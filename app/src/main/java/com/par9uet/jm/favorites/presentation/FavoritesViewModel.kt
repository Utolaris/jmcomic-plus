package com.par9uet.jm.favorites.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.favorites.data.FavoriteLocalQuery
import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.favorites.model.FavoritesFilter
import com.par9uet.jm.favorites.model.FavoritesIntent
import com.par9uet.jm.favorites.model.FavoritesModal
import com.par9uet.jm.favorites.model.FavoritesSelectionState
import com.par9uet.jm.favorites.model.FavoritesUiState
import com.par9uet.jm.favorites.model.FavoritesViewportState
import com.par9uet.jm.favorites.sync.FavoriteSyncRequestKind
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
import com.par9uet.jm.favorites.sync.FavoriteVisibilityPolicy
import com.par9uet.jm.favorites.usecase.CreateFavoriteFolder
import com.par9uet.jm.favorites.usecase.DeleteFavoriteFolder
import com.par9uet.jm.favorites.usecase.DownloadSelectedFavorites
import com.par9uet.jm.favorites.usecase.MoveFavorites
import com.par9uet.jm.favorites.usecase.RenameFavoriteFolder
import com.par9uet.jm.favorites.usecase.UncollectFavorites
import com.par9uet.jm.store.ContentPreferences
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.ui.pagingSource.CollectComicPagingSource
import com.par9uet.jm.retrofit.model.NetWorkResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class FavoritesPagerKey(
    val accountId: Int,
    val blockedTagList: List<String>,
    val filter: FavoritesFilter,
    val folderId: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModel(
    private val favoriteSession: FavoriteSession,
    private val contentPreferences: ContentPreferences,
    private val localQuery: FavoriteLocalQuery,
    private val toastManager: ToastManager,
    private val uncollectFavorites: UncollectFavorites,
    private val moveFavorites: MoveFavorites,
    private val createFavoriteFolder: CreateFavoriteFolder,
    private val deleteFavoriteFolder: DeleteFavoriteFolder,
    private val renameFavoriteFolder: RenameFavoriteFolder,
    private val downloadSelectedFavorites: DownloadSelectedFavorites,
    private val syncController: FavoriteSyncRequester,
) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState = _uiState.asStateFlow()

    private val visibilityPolicy = FavoriteVisibilityPolicy()
    private val accountIdFlow = favoriteSession.accountIdFlow
    private val selectedFolderIdFlow = _uiState
        .map { it.selectedFolderId }
        .distinctUntilChanged()

    private val folderFlow = accountIdFlow.flatMapLatest { accountId ->
        if (accountId > 0) localQuery.observeFolders(accountId) else flowOf(emptyMap())
    }
    private val tagCountFlow = favoriteTermCountFlow { accountId, folderId ->
        localQuery.observeTagCounts(accountId, folderId)
    }
    private val authorCountFlow = favoriteTermCountFlow { accountId, folderId ->
        localQuery.observeAuthorCounts(accountId, folderId)
    }

    init {
        viewModelScope.launch {
            accountIdFlow.distinctUntilChanged().collect {
                _uiState.update { state ->
                    state.copy(
                        selectedFolderId = 0,
                        selection = FavoritesSelectionState(),
                        searchActive = false,
                        filter = FavoritesFilter(),
                        modal = null,
                        viewport = state.viewport.reset(),
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(folderFlow, tagCountFlow, authorCountFlow) { folders, tags, authors ->
                Triple(folders, tags, authors)
            }.collect { (folders, tags, authors) ->
                _uiState.update {
                    it.copy(
                        folders = folders,
                        tagCounts = tags,
                        authorCounts = authors,
                    )
                }
            }
        }
        viewModelScope.launch {
            syncController.state.collect { sync ->
                _uiState.update { it.copy(sync = sync) }
            }
        }
    }

    private fun favoriteTermCountFlow(
        observe: (accountId: Int, folderId: Int) -> Flow<Map<String, Int>>,
    ): Flow<Map<String, Int>> = combine(accountIdFlow, selectedFolderIdFlow) { accountId, folderId ->
        accountId to folderId
    }.flatMapLatest { (accountId, folderId) ->
        if (accountId > 0) observe(accountId, folderId) else flowOf(emptyMap())
    }

    val collectComicPager = combine(
        contentPreferences.blockedTags,
        _uiState.map { it.selectedFolderId to it.filter }.distinctUntilChanged(),
        accountIdFlow,
    ) { blockedTagList, (folderId, filter), accountId ->
        FavoritesPagerKey(
            accountId = accountId,
            blockedTagList = blockedTagList,
            filter = filter,
            folderId = folderId,
        )
    }.flatMapLatest { key ->
        Pager(
            config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
            pagingSourceFactory = {
                CollectComicPagingSource(
                    localQuery.pagingSource(
                        accountId = key.accountId,
                        blockedTagList = key.blockedTagList,
                        searchText = key.filter.searchText,
                        selectedTags = key.filter.selectedTags,
                        selectedAuthors = key.filter.selectedAuthors,
                        folderId = key.folderId,
                        tagLogic = key.filter.tagLogic,
                    )
                )
            },
        ).flow
    }.cachedIn(viewModelScope)

    fun onIntent(intent: FavoritesIntent) {
        when (intent) {
            FavoritesIntent.Entered -> {
                if (visibilityPolicy.onVisibilityChanged(true)) requestAutomaticSync()
            }
            FavoritesIntent.Left -> {
                visibilityPolicy.onVisibilityChanged(false)
                clearTransientState()
            }
            is FavoritesIntent.AccountStateChanged -> {
                if (visibilityPolicy.onAuthenticationChanged(intent.authenticated)) {
                    requestAutomaticSync()
                }
            }
            FavoritesIntent.ManualSync -> syncController.request(
                kind = FavoriteSyncRequestKind.MANUAL,
                folderId = _uiState.value.selectedFolderId,
            )
            FavoritesIntent.ForceRefresh -> syncController.request(FavoriteSyncRequestKind.FORCE)

            is FavoritesIntent.FolderSelected -> selectFolder(intent.folderId)
            FavoritesIntent.SearchEntered -> _uiState.update { it.copy(searchActive = true) }
            FavoritesIntent.SearchExited -> exitSearch()
            is FavoritesIntent.SearchChanged -> updateSearch(intent.query)
            FavoritesIntent.FilterOpened -> _uiState.update {
                it.copy(modal = reduceFavoritesModal(it.modal, FavoritesIntent.FilterOpened))
            }
            is FavoritesIntent.FilterApplied -> applyFilter(intent)
            FavoritesIntent.FilterCleared -> {
                _uiState.update {
                    it.copy(
                        filter = it.filter.copy(
                            selectedTags = emptySet(),
                            selectedAuthors = emptySet(),
                            tagLogic = TagFilterLogic.AND,
                        ),
                        modal = reduceFavoritesModal(it.modal, FavoritesIntent.FilterCleared),
                        viewport = it.viewport.reset(),
                    )
                }
            }
            FavoritesIntent.FilterDismissed -> transitionModal(FavoritesIntent.FilterDismissed)

            is FavoritesIntent.ComicLongPressed -> longPressComic(intent.comicId)
            is FavoritesIntent.ComicSelectionToggled -> toggleSelected(intent.comicId)
            FavoritesIntent.SelectionCleared -> clearSelection()
            FavoritesIntent.DownloadSelected -> downloadSelected()
            FavoritesIntent.MoveSelected -> transitionModal(FavoritesIntent.MoveSelected)
            FavoritesIntent.UncollectSelected -> transitionModal(FavoritesIntent.UncollectSelected)
            is FavoritesIntent.MoveConfirmed -> moveSelected(intent.folderId)
            FavoritesIntent.UncollectConfirmed -> uncollectSelected()
            FavoritesIntent.ModalDismissed -> transitionModal(FavoritesIntent.ModalDismissed)

            FavoritesIntent.FolderManagementOpened -> transitionModal(FavoritesIntent.FolderManagementOpened)
            FavoritesIntent.FolderManagementDismissed -> transitionModal(FavoritesIntent.FolderManagementDismissed)
            FavoritesIntent.CreateFolderOpened -> transitionModal(FavoritesIntent.CreateFolderOpened)
            is FavoritesIntent.RenameFolderOpened -> transitionModal(intent)
            is FavoritesIntent.DeleteFolderOpened -> transitionModal(intent)
            FavoritesIntent.FolderActionDismissed -> transitionModal(FavoritesIntent.FolderActionDismissed)
            is FavoritesIntent.CreateFolderSubmitted -> submitCreateFolder(intent.name)
            is FavoritesIntent.RenameFolderSubmitted -> submitRenameFolder(intent.folderId, intent.name)
            FavoritesIntent.DeleteFolderConfirmed -> submitDeleteFolder()

            is FavoritesIntent.ViewportSaved -> saveViewport(intent)
        }
    }

    private fun selectFolder(folderId: Int) {
        if (_uiState.value.selectedFolderId == folderId) {
            dismissModal()
            return
        }
        _uiState.update {
            it.copy(
                selectedFolderId = folderId,
                selection = FavoritesSelectionState(),
                searchActive = false,
                filter = it.filter.copy(searchText = ""),
                modal = null,
                viewport = it.viewport.reset(),
            )
        }
        requestAutomaticSync(folderId)
    }

    private fun updateSearch(query: String) {
        if (_uiState.value.filter.searchText == query) return
        _uiState.update {
            it.copy(
                filter = it.filter.copy(searchText = query),
                viewport = it.viewport.reset(),
            )
        }
    }

    private fun exitSearch() {
        val hadSearchQuery = _uiState.value.filter.searchText.isNotEmpty()
        _uiState.update {
            it.copy(
                searchActive = false,
                filter = it.filter.copy(searchText = ""),
                // Only an effective dataset change deserves a viewport reset; entering and
                // immediately leaving an empty search must keep the saved scroll position.
                viewport = if (hadSearchQuery) it.viewport.reset() else it.viewport,
            )
        }
    }

    private fun applyFilter(intent: FavoritesIntent.FilterApplied) {
        val current = _uiState.value
        if (current.filter.selectedTags == intent.selectedTags &&
            current.filter.selectedAuthors == intent.selectedAuthors &&
            current.filter.tagLogic == intent.tagLogic
        ) {
            _uiState.update { it.copy(modal = null) }
            return
        }
        _uiState.update {
            it.copy(
                filter = FavoritesFilter(
                    searchText = it.filter.searchText,
                    selectedTags = intent.selectedTags,
                    selectedAuthors = intent.selectedAuthors,
                    tagLogic = intent.tagLogic,
                ),
                modal = null,
                viewport = it.viewport.reset(),
            )
        }
    }

    private fun longPressComic(comicId: Int) {
        if (_uiState.value.searchActive) exitSearch()
        if (_uiState.value.selection.editing) {
            toggleSelected(comicId)
        } else {
            _uiState.update {
                it.copy(
                    selection = FavoritesSelectionState(
                        editing = true,
                        selectedComicIds = setOf(comicId),
                    )
                )
            }
        }
    }

    private fun toggleSelected(comicId: Int) {
        _uiState.update { state ->
            val ids = if (comicId in state.selection.selectedComicIds) {
                state.selection.selectedComicIds - comicId
            } else {
                state.selection.selectedComicIds + comicId
            }
            state.copy(
                selection = FavoritesSelectionState(
                    editing = ids.isNotEmpty(),
                    selectedComicIds = ids,
                )
            )
        }
    }

    private fun downloadSelected() {
        val accountId = currentAccountId()
        val ids = _uiState.value.selection.selectedComicIds
        if (accountId <= 0 || ids.isEmpty()) return
        viewModelScope.launch {
            downloadSelectedFavorites(accountId, ids)
            clearSelection()
        }
    }

    private fun moveSelected(folderId: Int) {
        val sessionSnapshot = favoriteSession.snapshot()
        val ids = _uiState.value.selection.selectedComicIds
        if (sessionSnapshot.accountId <= 0 || ids.isEmpty()) return
        dismissModal()
        viewModelScope.launch {
            val result = moveFavorites(sessionSnapshot, ids, folderId)
            toastManager.showAsync(batchMessage(result.succeeded, result.failed, "移动"))
            clearSelection()
        }
    }

    private fun uncollectSelected() {
        val sessionSnapshot = favoriteSession.snapshot()
        val ids = _uiState.value.selection.selectedComicIds
        if (sessionSnapshot.accountId <= 0 || ids.isEmpty()) return
        dismissModal()
        viewModelScope.launch {
            val result = uncollectFavorites(sessionSnapshot, ids)
            toastManager.showAsync(batchMessage(result.succeeded, result.failed, "取消收藏"))
            clearSelection()
        }
    }

    private fun submitCreateFolder(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return
        val sessionSnapshot = favoriteSession.snapshot()
        if (sessionSnapshot.accountId <= 0) return
        showFolderManagement()
        viewModelScope.launch {
            when (val result = createFavoriteFolder(sessionSnapshot, trimmedName)) {
                is NetWorkResult.Error -> toastManager.showAsync(result.message)
                is NetWorkResult.Success -> {
                    requestAutomaticSync()
                    toastManager.showAsync("创建成功")
                    showFolderManagement()
                }
            }
        }
    }

    private fun submitRenameFolder(folderId: Int, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return
        val sessionSnapshot = favoriteSession.snapshot()
        if (sessionSnapshot.accountId <= 0) return
        showFolderManagement()
        viewModelScope.launch {
            when (val result = renameFavoriteFolder(sessionSnapshot, folderId, trimmedName)) {
                is NetWorkResult.Error -> toastManager.showAsync(result.message)
                is NetWorkResult.Success -> {
                    requestAutomaticSync()
                    toastManager.showAsync("重命名成功")
                    showFolderManagement()
                }
            }
        }
    }

    private fun submitDeleteFolder() {
        val modal = _uiState.value.modal as? FavoritesModal.DeleteFolder ?: return
        val sessionSnapshot = favoriteSession.snapshot()
        if (sessionSnapshot.accountId <= 0) return
        showFolderManagement()
        viewModelScope.launch {
            when (val result = deleteFavoriteFolder(sessionSnapshot, modal.folderId)) {
                is NetWorkResult.Error -> toastManager.showAsync(result.message)
                is NetWorkResult.Success -> {
                    if (_uiState.value.selectedFolderId == modal.folderId) {
                        _uiState.update { it.copy(selectedFolderId = 0, viewport = it.viewport.reset()) }
                    }
                    requestAutomaticSync()
                    toastManager.showAsync("删除成功")
                }
            }
        }
    }

    private fun saveViewport(intent: FavoritesIntent.ViewportSaved) {
        _uiState.update { state ->
            if (intent.resetGeneration != state.viewport.resetGeneration) return@update state
            state.copy(
                viewport = state.viewport.copy(
                    firstVisibleItemIndex = intent.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = intent.firstVisibleItemScrollOffset,
                )
            )
        }
    }

    private fun showFolderManagement() {
        _uiState.update { it.copy(modal = FavoritesModal.FolderManagement) }
    }

    private fun transitionModal(intent: FavoritesIntent) {
        _uiState.update { state ->
            state.copy(
                modal = reduceFavoritesModal(
                    current = state.modal,
                    intent = intent,
                    hasSelection = state.selection.selectedComicIds.isNotEmpty(),
                )
            )
        }
    }

    private fun dismissModal() {
        _uiState.update { it.copy(modal = null) }
    }

    private fun clearSelection() {
        _uiState.update { it.copy(selection = FavoritesSelectionState()) }
    }

    private fun clearTransientState() {
        val hadSearchQuery = _uiState.value.filter.searchText.isNotEmpty()
        _uiState.update {
            it.copy(
                searchActive = false,
                filter = it.filter.copy(searchText = ""),
                selection = FavoritesSelectionState(),
                modal = null,
                // Leaving the tab alone never scrolls the user back to the top; a cleared
                // active search query changes the effective list and therefore does.
                viewport = if (hadSearchQuery) it.viewport.reset() else it.viewport,
            )
        }
    }

    private fun requestAutomaticSync(folderId: Int = _uiState.value.selectedFolderId) {
        syncController.request(FavoriteSyncRequestKind.AUTO, folderId)
    }

    private fun currentAccountId(): Int = favoriteSession.currentAccountId()

    private fun batchMessage(succeeded: Int, failed: Int, action: String): String =
        if (failed == 0) "已$action $succeeded 部漫画"
        else "成功 $succeeded 部，失败 $failed 部"
}

private fun FavoritesViewportState.reset(): FavoritesViewportState =
    FavoritesViewportState(resetGeneration = resetGeneration + 1)

internal fun reduceFavoritesModal(
    current: FavoritesModal?,
    intent: FavoritesIntent,
    hasSelection: Boolean = false,
): FavoritesModal? = when (intent) {
    FavoritesIntent.FilterOpened -> FavoritesModal.Filter
    FavoritesIntent.FilterCleared,
    FavoritesIntent.FilterDismissed,
    FavoritesIntent.ModalDismissed,
    FavoritesIntent.FolderManagementDismissed -> null
    FavoritesIntent.MoveSelected -> if (hasSelection) FavoritesModal.Move else current
    FavoritesIntent.UncollectSelected -> if (hasSelection) FavoritesModal.Uncollect else current
    FavoritesIntent.FolderManagementOpened -> FavoritesModal.FolderManagement
    FavoritesIntent.CreateFolderOpened -> FavoritesModal.CreateFolder
    is FavoritesIntent.RenameFolderOpened -> FavoritesModal.RenameFolder(
        folderId = intent.folderId,
        folderName = intent.folderName,
    )
    is FavoritesIntent.DeleteFolderOpened -> FavoritesModal.DeleteFolder(
        folderId = intent.folderId,
        folderName = intent.folderName,
    )
    FavoritesIntent.FolderActionDismissed -> FavoritesModal.FolderManagement
    else -> current
}

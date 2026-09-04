package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.favorites.data.FavoriteLocalQuery
import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.favorites.data.FavoriteSessionSnapshot
import com.par9uet.jm.favorites.sync.FavoriteSyncRequestKind
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
import com.par9uet.jm.favorites.usecase.CollectFavorite
import com.par9uet.jm.favorites.usecase.MoveFavorites
import com.par9uet.jm.favorites.usecase.UncollectFavorites
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.CommentComicResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.core.model.CommonUIState
import com.par9uet.jm.ui.pagingSource.ComicCommentPagingSource
import com.par9uet.jm.ui.state.CommentSubmissionGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val COMMENT_ERROR_VISIBLE_MILLIS = 5_000L

class ComicDetailViewModel(
    private val comicRepository: ComicRepository,
    private val toastManager: ToastManager,
    private val favoriteLocalQuery: FavoriteLocalQuery,
    private val favoriteSession: FavoriteSession,
    private val collectFavorite: CollectFavorite,
    private val uncollectFavorites: UncollectFavorites,
    private val moveFavorites: MoveFavorites,
    private val syncRequester: FavoriteSyncRequester,
) : ViewModel() {
    private val _comicDetailState = MutableStateFlow<CommonUIState<Comic>>(
        CommonUIState(
            isLoading = true,
        )
    )
    val comicDetailState = _comicDetailState.asStateFlow()

    /** Comic id whose FULL detail has been fetched; a seed-only comic is not enough. */
    private var fullDetailComicId: Int? = null
    private var currentDetailLoadJob: Job? = null
    private var detailRequestGeneration = 0L
    private var requestedDetailId: Int? = null

    /**
     * Seeds the state with the list-item the user just tapped so cover/title/author render on
     * the FIRST frame, then refreshes full detail in the background.
     */
    fun prepareDetail(comic: Comic) {
        currentDetailLoadJob?.cancel()
        detailRequestGeneration++
        requestedDetailId = null
        _comicDetailState.value = CommonUIState(
            data = comic,
            isLoading = true,
        )
        ensureFullComicDetail(comic.id)
    }

    /** Fetches full detail unless it is already loaded for this exact comic id. */
    private fun ensureFullComicDetail(id: Int) {
        if (fullDetailComicId == id && _comicDetailState.value.data?.id == id &&
            !_comicDetailState.value.isLoading
        ) {
            return
        }
        getComicDetail(id)
    }

    fun getComicDetail(id: Int) {
        currentDetailLoadJob?.cancel()
        val requestGeneration = ++detailRequestGeneration
        requestedDetailId = id
        val currentState = _comicDetailState.value
        val hasMatchingSeed = currentState.data?.id == id
        if (!hasMatchingSeed) {
            fullDetailComicId = null
            _comicDetailState.value = CommonUIState(isLoading = true)
        } else {
            _comicDetailState.value = currentState.copy(
                isLoading = true,
                isError = false,
                errorMsg = "",
            )
        }
        currentDetailLoadJob = viewModelScope.launch {
            val data = comicRepository.getComicDetail(id)
            if (requestGeneration != detailRequestGeneration || requestedDetailId != id) {
                return@launch
            }
            when (data) {
                is NetWorkResult.Error -> {
                    val state = _comicDetailState.value
                    if (state.data?.id == id) {
                        // Keep only a matching seed visible; surface the failure non-blockingly.
                        _comicDetailState.value = state.copy(
                            isLoading = false,
                            isError = false,
                            errorMsg = data.message,
                        )
                    } else {
                        _comicDetailState.value = CommonUIState(
                            isLoading = false,
                            isError = true,
                            errorMsg = data.message,
                        )
                    }
                }

                is NetWorkResult.Success<ComicDetailResponse> -> {
                    val state = _comicDetailState.value
                    if (state.data == null || state.data.id == id) {
                        fullDetailComicId = id
                        _comicDetailState.value = state.copy(
                            data = data.data.toComic(),
                            isError = false,
                            errorMsg = "",
                        )
                    }
                }
            }
            if (requestGeneration == detailRequestGeneration && requestedDetailId == id) {
                _comicDetailState.update { it.copy(isLoading = false) }
            }
        }
    }

    private val _collectComicState = MutableStateFlow(CommonUIState(data = null))
    val collectComicState = _collectComicState.asStateFlow()

    private suspend fun commitFavoriteUiIfCurrent(
        snapshot: FavoriteSessionSnapshot,
        block: () -> Unit,
    ): Boolean = favoriteSession.withCurrentSession(snapshot) {
        block()
        true
    } == true

    private fun showStaleFavoriteAction() {
        _collectComicState.update {
            it.copy(isError = true, errorMsg = "登录状态已变化，请重试")
        }
    }

    private fun launchFavoriteAction(block: suspend () -> Unit) {
        if (_collectComicState.value.isLoading) return
        _collectComicState.update { it.copy(isLoading = true, isError = false, errorMsg = "") }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _collectComicState.update {
                    it.copy(isError = true, errorMsg = error.message ?: "收藏操作失败，请重试")
                }
            } finally {
                _collectComicState.update { it.copy(isLoading = false) }
                val state = _collectComicState.value
                if (state.isError) toastManager.showAsync(state.errorMsg ?: "收藏操作失败，请重试")
            }
        }
    }

    fun collect(id: Int) {
        launchFavoriteAction {
            // One snapshot guards the whole action: remote toggle and local write both belong
            // to the account captured here, never to whoever is active after a mid-flight switch.
            val snapshot = favoriteSession.snapshot()
            val comic = _comicDetailState.value.data?.takeIf { it.id == id }
            if (comic == null) {
                _collectComicState.update {
                    it.copy(isLoading = false, isError = true, errorMsg = "漫画信息尚未加载")
                }
                return@launchFavoriteAction
            }
            when (val data = collectFavorite(snapshot, comic)) {
                is NetWorkResult.Error -> {
                    _collectComicState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success -> {
                    val committed = commitFavoriteUiIfCurrent(snapshot) {
                        toastManager.showAsync("收藏成功")
                        _comicDetailState.update { state ->
                            val currentData = state.data?.takeIf { it.id == id }
                            if (currentData != null) {
                                state.copy(data = currentData.copy(isCollect = true))
                            } else {
                                state
                            }
                        }
                    }
                    if (!committed) showStaleFavoriteAction()
                }
            }
        }
    }

    fun unCollect(id: Int) {
        launchFavoriteAction {
            // Same session-bound discipline as collect: the canonical L3 use case owns both
            // the remote toggle and the local removal, for the snapshot's account only.
            val snapshot = favoriteSession.snapshot()
            val batch = uncollectFavorites(snapshot, listOf(id))
            when {
                batch.succeeded > 0 -> {
                    val committed = commitFavoriteUiIfCurrent(snapshot) {
                        toastManager.showAsync("取消收藏成功")
                        _comicDetailState.update { state ->
                            val currentData = state.data?.takeIf { it.id == id }
                            if (currentData != null) {
                                state.copy(data = currentData.copy(isCollect = false))
                            } else {
                                state
                            }
                        }
                    }
                    if (!committed) showStaleFavoriteAction()
                }

                else -> _collectComicState.update {
                    val message = if (favoriteSession.isCurrent(snapshot)) {
                        "取消收藏失败，请重试"
                    } else {
                        "登录状态已变化，请重试"
                    }
                    it.copy(isError = true, errorMsg = message)
                }
            }
        }
    }

    // 收藏夹选择相关
    private val _folderList = MutableStateFlow<Map<String, String>>(emptyMap())
    val folderList = _folderList.asStateFlow()

    private val _showFolderPicker = MutableStateFlow(false)
    val showFolderPicker = _showFolderPicker.asStateFlow()

    private var folderObservationJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshFolderList() {
        // Local-first + account-scoped: show the new account's cached folders immediately,
        // then keep observing Room so background sync results flow in automatically. An
        // account switch rebinds the stream, so A's folders never leak into B.
        folderObservationJob?.cancel()
        folderObservationJob = viewModelScope.launch {
            favoriteSession.accountIdFlow
                .distinctUntilChanged()
                .flatMapLatest { accountId ->
                    if (accountId <= 0) {
                        flowOf(emptyMap())
                    } else {
                        favoriteLocalQuery.observeFolders(accountId).onStart {
                            emit(favoriteLocalQuery.getCachedFolders(accountId))
                        }
                    }
                }
                .collect { folders -> _folderList.value = folders }
        }
        syncRequester.request(FavoriteSyncRequestKind.AUTO, folderId = 0)
    }

    fun showFolderPicker() {
        _showFolderPicker.value = true
    }

    fun hideFolderPicker() {
        _showFolderPicker.value = false
    }

    fun collectWithFolder(comicId: Int, folderId: String) {
        launchFavoriteAction {
            _showFolderPicker.value = false

            // The WHOLE user action belongs to one authenticated identity: collect and the
            // optional move reuse this single snapshot. L2 sequences the two L3 operations;
            // neither captures a fresh snapshot, so a mid-flight switch fails stale instead
            // of letting "collect on A → move on B" happen.
            val snapshot = favoriteSession.snapshot()
            val comic = _comicDetailState.value.data?.takeIf { it.id == comicId }
            if (comic == null) {
                _collectComicState.update {
                    it.copy(isLoading = false, isError = true, errorMsg = "漫画信息尚未加载")
                }
                return@launchFavoriteAction
            }
            val collectResult = collectFavorite(snapshot, comic)
            when {
                collectResult is NetWorkResult.Error -> {
                    _collectComicState.update { it.copy(isError = true, errorMsg = collectResult.message) }
                }

                else -> {
                    val successMessage = if (folderId != "0") {
                        val targetFolderId = folderId.toIntOrNull() ?: 0
                        val moveResult = moveFavorites(snapshot, listOf(comicId), targetFolderId)
                        when {
                            moveResult.succeeded > 0 -> {
                                val folderName = _folderList.value[folderId] ?: "收藏夹"
                                "已收藏到 $folderName"
                            }
                            favoriteSession.isCurrent(snapshot) -> "已收藏，但移动到收藏夹失败"
                            else -> null
                        }
                    } else {
                        "收藏成功"
                    }

                    if (successMessage == null) {
                        showStaleFavoriteAction()
                    } else {
                        val committed = commitFavoriteUiIfCurrent(snapshot) {
                            toastManager.showAsync(successMessage)
                            _comicDetailState.update { state ->
                                val currentData = state.data?.takeIf { it.id == comicId }
                                if (currentData != null) {
                                    state.copy(data = currentData.copy(isCollect = true))
                                } else {
                                    state
                                }
                            }
                        }
                        if (!committed) showStaleFavoriteAction()
                    }
                }
            }
        }
    }

    fun reset(id: Int?) {
        val currentDataId = _comicDetailState.value.data?.id
        // A seed whose full fetch FAILED stays valid for this id (non-blocking error page);
        // any other mismatched/stale seed must be dropped so the next comic cannot flash it.
        if (id != null && currentDataId == id &&
            (fullDetailComicId == id || _comicDetailState.value.isLoading)
        ) {
            return
        }
        currentDetailLoadJob?.cancel()
        detailRequestGeneration++
        requestedDetailId = null
        fullDetailComicId = null
        _comicDetailState.update {
            CommonUIState(
                isLoading = true,
            )
        }
    }

    private fun currentAccountId(): Int = favoriteSession.currentAccountId()

    private val _commentComicIdState = MutableStateFlow(0)
    val commentComicIdState = _commentComicIdState.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val commentPager = _commentComicIdState.flatMapLatest { comicId ->
        Pager(
            config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
            pagingSourceFactory = {
                ComicCommentPagingSource(
                    comicRepository,
                    comicId
                )
            }
        ).flow
    }.cachedIn(viewModelScope)

    fun changeCommentComicId(comicId: Int) {
        _commentComicIdState.update {
            comicId
        }
    }

    private val _commentComicState = MutableStateFlow(CommonUIState(data = null))
    val commentComicState = _commentComicState.asStateFlow()
    private val commentSubmissionGate = CommentSubmissionGate()
    private var commentErrorClearJob: Job? = null

    /**
     * Inline comment errors are transient: each new error cancels the previous clear job and
     * schedules a fresh ~5s timeout. Success and new submissions clear the message immediately.
     */
    private fun showTransientCommentError(message: String) {
        commentErrorClearJob?.cancel()
        _commentComicState.update {
            it.copy(isError = true, errorMsg = message)
        }
        commentErrorClearJob = viewModelScope.launch {
            delay(COMMENT_ERROR_VISIBLE_MILLIS)
            _commentComicState.update {
                it.copy(isError = false, errorMsg = "")
            }
        }
    }

    private fun clearCommentError() {
        commentErrorClearJob?.cancel()
        commentErrorClearJob = null
        _commentComicState.update {
            it.copy(isError = false, errorMsg = "")
        }
    }

    fun comment(
        content: String,
        comicId: Int,
        commentId: Int? = null,
        onSuccess: (() -> Unit)? = null
    ) {
        if (content.isBlank() || !commentSubmissionGate.tryAcquire()) return
        clearCommentError()
        _commentComicState.update {
            it.copy(isLoading = true)
        }
        viewModelScope.launch {
            try {
                when (val data = comicRepository.comment(content, comicId, commentId)) {
                    is NetWorkResult.Error -> {
                        showTransientCommentError(data.message.ifBlank { "发送评论失败" })
                        toastManager.showAsync(data.message)
                    }

                    is NetWorkResult.Success<CommentComicResponse> -> {
                        val status = data.data.status.trim()
                        val isSuccess = status.isBlank()
                            || status.equals("ok", ignoreCase = true)
                            || status.equals("success", ignoreCase = true)
                        if (isSuccess) {
                            toastManager.showAsync(data.data.msg.ifBlank { "发送成功" })
                            onSuccess?.invoke()
                        } else {
                            val message = data.data.msg.ifBlank { "发送评论失败" }
                            showTransientCommentError(message)
                            toastManager.showAsync(message)
                        }
                    }
                }
            } finally {
                commentSubmissionGate.release()
                _commentComicState.update {
                    it.copy(isLoading = false)
                }
            }
        }
    }

}

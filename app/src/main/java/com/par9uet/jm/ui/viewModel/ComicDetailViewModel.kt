package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteLocalQuery
import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.CollectComicResponse
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.CommentComicResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.RemoteSettingManager
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.ui.models.CommonUIState
import com.par9uet.jm.ui.pagingSource.ComicCommentPagingSource
import com.par9uet.jm.ui.state.CommentSubmissionGate
import com.par9uet.jm.favorites.usecase.MoveFavorites
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val COMMENT_ERROR_VISIBLE_MILLIS = 5_000L

class ComicDetailViewModel(
    private val comicRepository: ComicRepository,
    private val toastManager: ToastManager,
    private val downloadComicDao: DownloadComicDao,
    private val remoteSettingManager: RemoteSettingManager,
    private val favoriteLocalQuery: FavoriteLocalQuery,
    private val favoriteLocalMutation: FavoriteLocalMutation,
    private val favoriteSession: FavoriteSession,
    private val moveFavorites: MoveFavorites,
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
    fun collect(id: Int) {
        viewModelScope.launch {
            _collectComicState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = comicRepository.collectComic(id)) {
                is NetWorkResult.Error -> {
                    _collectComicState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<CollectComicResponse> -> {
                    toastManager.showAsync("收藏成功")
                    _comicDetailState.value.data?.let { comic ->
                        favoriteLocalMutation.addFromComic(
                            accountId = currentAccountId(),
                            comic = comic,
                        )
                    }
                    _comicDetailState.update { state ->
                        val currentData = state.data
                        if (currentData != null) {
                            state.copy(data = currentData.copy(isCollect = true))
                        } else {
                            state
                        }
                    }
                }
            }
            _collectComicState.update {
                it.copy(
                    isLoading = false,
                )
            }
        }
    }

    fun unCollect(id: Int) {
        viewModelScope.launch {
            _collectComicState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = comicRepository.unCollectComic(id)) {
                is NetWorkResult.Error -> {
                    _collectComicState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<CollectComicResponse> -> {
                    toastManager.showAsync("取消收藏成功")
                    favoriteLocalMutation.remove(currentAccountId(), listOf(id))
                    _comicDetailState.update { state ->
                        val currentData = state.data
                        if (currentData != null) {
                            state.copy(data = currentData.copy(isCollect = false))
                        } else {
                            state
                        }
                    }
                }
            }
            _collectComicState.update {
                it.copy(
                    isLoading = false,
                )
            }
        }
    }

    // 收藏夹选择相关
    private val _folderList = MutableStateFlow<Map<String, String>>(emptyMap())
    val folderList = _folderList.asStateFlow()

    private val _showFolderPicker = MutableStateFlow(false)
    val showFolderPicker = _showFolderPicker.asStateFlow()

    fun refreshFolderList() {
        viewModelScope.launch {
            val accountId = currentAccountId()
            _folderList.value = favoriteLocalQuery.getCachedFolders(accountId)
        }
    }

    fun showFolderPicker() {
        _showFolderPicker.value = true
    }

    fun hideFolderPicker() {
        _showFolderPicker.value = false
    }

    fun collectWithFolder(comicId: Int, folderId: String) {
        viewModelScope.launch {
            _showFolderPicker.value = false
            _collectComicState.update { it.copy(isLoading = true, isError = false, errorMsg = "") }
            // 先收藏到默认夹
            when (val data = comicRepository.collectComic(comicId)) {
                is NetWorkResult.Error -> {
                    _collectComicState.update { it.copy(isError = true, errorMsg = data.message) }
                }
                is NetWorkResult.Success<CollectComicResponse> -> {
                    val accountId = currentAccountId()
                    val comic = _comicDetailState.value.data
                    if (comic != null) {
                        favoriteLocalMutation.addFromComic(accountId, comic, folderId = 0)
                    }
                    // 如果选择了非默认夹，再移动到目标夹
                    if (folderId != "0") {
                        // Route through the canonical L3 molecule: the remote move runs inside
                        // the session-bound boundary, and the local folder membership is only
                        // written after a successful, still-current commit.
                        val targetFolderId = folderId.toIntOrNull() ?: 0
                        val moveResult = moveFavorites(favoriteSession.snapshot(), listOf(comicId), targetFolderId)
                        val folderName = _folderList.value[folderId] ?: "收藏夹"
                        when {
                            moveResult.succeeded > 0 -> toastManager.showAsync("已收藏到 $folderName")
                            else -> toastManager.showAsync("已收藏，但移动到收藏夹失败")
                        }
                    } else {
                        toastManager.showAsync("收藏成功")
                    }
                    _comicDetailState.update { state ->
                        val currentData = state.data
                        if (currentData != null) {
                            state.copy(data = currentData.copy(isCollect = true))
                        } else {
                            state
                        }
                    }
                }
            }
            _collectComicState.update { it.copy(isLoading = false) }
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

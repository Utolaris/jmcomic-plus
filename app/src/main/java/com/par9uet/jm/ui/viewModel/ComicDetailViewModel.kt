package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.retrofit.model.CollectComicResponse
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.CommentComicResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.RemoteSettingManager
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.ui.models.CommonUIState
import com.par9uet.jm.ui.pagingSource.ComicCommentPagingSource
import com.par9uet.jm.ui.state.CommentSubmissionGate
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
    private val userRepository: UserRepository,
    private val userManager: UserManager,
) : ViewModel() {
    private val _comicDetailState = MutableStateFlow<CommonUIState<Comic>>(
        CommonUIState(
            isLoading = true,
        )
    )
    val comicDetailState = _comicDetailState.asStateFlow()

    /** Comic id whose FULL detail has been fetched; a seed-only comic is not enough. */
    private var fullDetailComicId: Int? = null
    private var currentDetailLoadJob: kotlinx.coroutines.Job? = null

    /**
     * Seeds the state with the list-item the user just tapped so cover/title/author render on
     * the FIRST frame, then refreshes full detail in the background.
     */
    fun prepareDetail(comic: Comic) {
        currentDetailLoadJob?.cancel()
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
        currentDetailLoadJob = viewModelScope.launch {
            _comicDetailState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = "",
                )
            }
            when (val data = comicRepository.getComicDetail(id)) {
                is NetWorkResult.Error -> {
                    val hasSeed = _comicDetailState.value.data != null
                    _comicDetailState.update {
                        if (hasSeed) {
                            // Keep the seeded page visible; surface the failure non-blockingly.
                            it.copy(
                                isLoading = false,
                                errorMsg = data.message,
                            )
                        } else {
                            it.copy(
                                isError = true,
                                errorMsg = data.message,
                            )
                        }
                    }
                }

                is NetWorkResult.Success<ComicDetailResponse> -> {
                    fullDetailComicId = id
                    _comicDetailState.update {
                        it.copy(
                            data = data.data.toComic()
                        )
                    }
                }
            }
            _comicDetailState.update {
                it.copy(
                    isLoading = false
                )
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
                        userRepository.cacheFavoriteComic(
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
                    userRepository.removeCachedFavoriteComic(currentAccountId(), id)
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
            _folderList.value = userRepository.getCachedFavoriteFolders(accountId)
            if (accountId > 0) {
                launch {
                    userRepository.synchronizeFavorites(accountId, folderId = 0)
                    _folderList.value = userRepository.getCachedFavoriteFolders(accountId)
                }
            }
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
                        userRepository.cacheFavoriteComic(accountId, comic, folderId = 0)
                    }
                    // 如果选择了非默认夹，再移动到目标夹
                    if (folderId != "0") {
                        when (val moveResult = comicRepository.moveComicToFolder(comicId, folderId)) {
                            is NetWorkResult.Error -> {
                                toastManager.showAsync("已收藏但移动到收藏夹失败：${moveResult.message}")
                            }
                            is NetWorkResult.Success<Unit> -> {
                                userRepository.moveCachedFavoriteComic(
                                    accountId,
                                    comicId,
                                    folderId.toIntOrNull() ?: 0,
                                )
                                val folderName = _folderList.value[folderId] ?: "收藏夹"
                                toastManager.showAsync("已收藏到 $folderName")
                            }
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
        fullDetailComicId = null
        _comicDetailState.update {
            CommonUIState(
                isLoading = true,
            )
        }
    }

    private fun currentAccountId(): Int = userManager.userState.value.data?.id ?: 0

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

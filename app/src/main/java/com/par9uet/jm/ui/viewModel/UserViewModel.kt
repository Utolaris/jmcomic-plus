package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.SignInData
import com.par9uet.jm.repository.CandidateSession
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.SignInDataResponse
import com.par9uet.jm.retrofit.model.SignInResponse
import com.par9uet.jm.store.DownloadManager
import com.par9uet.jm.store.ContentPreferences
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.core.model.CommonUIState
import com.par9uet.jm.ui.pagingSource.HistoryComicPagingSource
import com.par9uet.jm.ui.pagingSource.HistoryCommentPagingSource
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryEditState(
    val editing: Boolean = false,
    val selectedComicIds: Set<Int> = emptySet()
)

class UserViewModel(
    private val userManager: UserManager,
    private val userRepository: UserRepository,
    private val toastManager: ToastManager,
    private val contentPreferences: ContentPreferences,
    private val downloadManager: DownloadManager,
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

                is NetWorkResult.Success<CandidateSession> -> {
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
        contentPreferences.blockedTags,
        _historyRefreshVersion
    ) { blockedTagList, _ -> blockedTagList }
        .flatMapLatest { blockedTagList ->
        Pager(
            config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
            pagingSourceFactory = {
                HistoryComicPagingSource(userRepository, blockedTagList)
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

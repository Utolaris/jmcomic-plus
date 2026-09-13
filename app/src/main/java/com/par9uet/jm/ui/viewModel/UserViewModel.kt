package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.core.model.SignInData
import com.par9uet.jm.session.CandidateSession
import com.par9uet.jm.session.UserRepository
import com.par9uet.jm.session.UserSessionSnapshot
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.download.coordinator.DownloadManager
import com.par9uet.jm.storage.ContentPreferences
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.session.UserManager
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

/**
 * History multi-select is owned by one session: ids selected under account A must never be
 * submitted (download/delete) under account B. [session] records the owner and is cleared
 * together with the selection whenever [UserManager.sessionState] changes.
 */
data class HistoryEditState(
    val editing: Boolean = false,
    val selectedComicIds: Set<Int> = emptySet(),
    val session: UserSessionSnapshot? = null,
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

    /**
     * History comics and history comments share the same account+generation paging lifecycle:
     * [UserManager.sessionState] rebuilds the pager so account B never reuses A's cached pages.
     * blockedTags still participates because tag filtering is applied inside the paging source.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val historyComicPager = combine(
        userManager.sessionState,
        contentPreferences.blockedTags,
        _historyRefreshVersion,
    ) { session, blockedTagList, _ -> session to blockedTagList }
        .flatMapLatest { (_, blockedTagList) ->
        Pager(
            config = PagingConfig(
                pageSize = HistoryComicPagingSource.PAGE_SIZE,
                prefetchDistance = 6,
                initialLoadSize = HistoryComicPagingSource.PAGE_SIZE,
            ),
            pagingSourceFactory = {
                HistoryComicPagingSource(userRepository, blockedTagList)
            }
        ).flow
    }.cachedIn(viewModelScope)

    private val _historyEditState = MutableStateFlow(HistoryEditState())
    val historyEditState = _historyEditState.asStateFlow()

    init {
        // Session change (account switch / logout / generation bump) owns the selection:
        // publish empty edit state immediately so A's ids cannot outlive A's session.
        viewModelScope.launch {
            userManager.sessionState.collect { session ->
                if (_historyEditState.value.session != null &&
                    _historyEditState.value.session != session
                ) {
                    _historyEditState.value = HistoryEditState()
                }
            }
        }
    }

    fun enterHistoryEdit(comicId: Int) {
        val session = userManager.currentSessionSnapshot()
        if (session.accountId <= 0) return
        _historyEditState.update { current ->
            // A selection still holding another session's snapshot is already stale.
            val base = if (current.session == null || current.session == session) {
                current
            } else {
                HistoryEditState()
            }
            base.copy(
                editing = true,
                selectedComicIds = base.selectedComicIds + comicId,
                session = session,
            )
        }
    }

    fun toggleHistorySelected(comicId: Int) {
        val session = userManager.currentSessionSnapshot()
        if (session.accountId <= 0) return
        _historyEditState.update { current ->
            val base = if (current.session == null || current.session == session) {
                current
            } else {
                HistoryEditState()
            }
            val selected = if (comicId in base.selectedComicIds) {
                base.selectedComicIds - comicId
            } else {
                base.selectedComicIds + comicId
            }
            base.copy(
                editing = selected.isNotEmpty(),
                selectedComicIds = selected,
                session = if (selected.isEmpty()) null else session,
            )
        }
    }

    fun clearHistorySelection() {
        _historyEditState.update { HistoryEditState() }
    }

    fun deleteHistoryComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        log("UserViewModel", "deleteHistoryComics: 开始删除 ${comics.size} 条历史记录, ids=${comics.map { it.id }}")
        viewModelScope.launch {
            // Bind the whole batch to the session that started it. A mid-batch account switch
            // must stop instead of applying A's remaining ids under B's credentials.
            val session = userManager.currentSessionSnapshot()
            if (session.accountId <= 0) {
                toastManager.showAsync("请先登录")
                clearHistorySelection()
                return@launch
            }
            // Selections captured under another account/generation must never submit here:
            // the dialog may still hold A's comics after the pager has already switched to B.
            val selectionSession = _historyEditState.value.session
            if (selectionSession == null || selectionSession != session) {
                log("UserViewModel", "deleteHistoryComics: 选择不属于当前会话，拒绝提交")
                toastManager.showAsync("会话已切换，未继续删除")
                clearHistorySelection()
                return@launch
            }
            var success = 0
            var fail = 0
            var cancelled = false
            val errors = mutableListOf<String>()
            for (comic in comics) {
                if (userManager.currentSessionSnapshot() != session) {
                    cancelled = true
                    log("UserViewModel", "deleteHistoryComics: 会话已切换，中止剩余删除")
                    break
                }
                log("UserViewModel", "deleteHistoryComics: 正在删除 comic.id=${comic.id}")
                val result = userManager.withBoundRemoteSession(session.accountId, session.generation) {
                    userRepository.deleteHistoryComic(comic.id)
                }
                when {
                    result == null -> {
                        cancelled = true
                        log("UserViewModel", "deleteHistoryComics: 会话失效，中止剩余删除")
                        break
                    }
                    result is NetWorkResult.Error -> {
                        logError(
                            "UserViewModel",
                            "deleteHistoryComics: 删除 comic.id=${comic.id} 失败: ${result.message}"
                        )
                        errors += result.message
                        fail++
                    }
                    result is NetWorkResult.Success -> success++
                }
            }
            log("UserViewModel", "deleteHistoryComics: 完成, 成功=$success, 失败=$fail, cancelled=$cancelled")
            val message = when {
                cancelled && success == 0 && fail == 0 -> "会话已切换，未继续删除"
                cancelled -> "会话已切换，已停止剩余删除（成功 $success 条）"
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
        // Same ownership rule as delete: a stale selection must not enqueue work.
        val session = userManager.currentSessionSnapshot()
        val selectionSession = _historyEditState.value.session
        if (session.accountId <= 0 || selectionSession == null || selectionSession != session) {
            clearHistorySelection()
            return
        }
        downloadManager.downloadComics(comics)
        clearHistorySelection()
    }

    /**
     * Recreated whenever the session identity changes so account B never reuses A's
     * cached pages or userId. flatMapLatest + cachedIn keeps one active pager per session.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val historyCommentPager = userManager.sessionState
        .flatMapLatest { snapshot ->
            Pager(
                config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
                pagingSourceFactory = {
                    HistoryCommentPagingSource(userRepository, snapshot.accountId)
                }
            ).flow
        }.cachedIn(viewModelScope)

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

                is NetWorkResult.Success -> {
                    _signInDataState.update {
                        it.copy(
                            data = data.data
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

                is NetWorkResult.Success -> {
                    toastManager.showAsync(data.data.message)
                    getSignInData()
                    _signInState.update {
                        it.copy(
                            data = data.data.message
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

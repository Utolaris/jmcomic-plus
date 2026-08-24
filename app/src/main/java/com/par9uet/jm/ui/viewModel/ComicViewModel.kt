package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.data.models.WeekData
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.WeekResponse
import com.par9uet.jm.store.AppLocalSettings
import com.par9uet.jm.ui.models.CommonUIState
import com.par9uet.jm.ui.pagingSource.SearchComicFilter
import com.par9uet.jm.ui.pagingSource.SearchComicPagingSource
import com.par9uet.jm.ui.pagingSource.WeekComicPagingSource
import com.par9uet.jm.ui.pagingSource.WeekFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal fun reorderPromoteSections(
    input: List<HomeSwiperComicListItemResponse>,
): List<HomeSwiperComicListItemResponse> {
    if (input.size < 2 || !input[1].title.contains("推荐本本")) return input
    return buildList(input.size) {
        add(input[1])
        add(input[0])
        addAll(input.drop(2))
    }
}

class ComicViewModel(
    private val comicRepository: ComicRepository,
    private val localSettingManager: AppLocalSettings,
) : ViewModel() {
    /** 首页分类描述：id 供仓库加载，title 为展示名。 */
    data class HomeCategoryInfo(
        val id: String,
        val title: String,
    )

    data class HomeCategoryLoadState(
        val content: List<HomeSwiperComicListItemResponse.ListItem> = emptyList(),
        val isLoading: Boolean = false,
        val isError: Boolean = false,
        val errorMsg: String? = null,
    )

    data class HomeUIState(
        val categories: List<HomeCategoryInfo> = emptyList(),
        val selectedCategoryId: String? = null,
        val states: Map<String, HomeCategoryLoadState> = emptyMap(),
    )

    private val _homeState = MutableStateFlow(HomeUIState())
    val homeState = _homeState.asStateFlow()

    private data class HomeRequestToken(
        val topologyGeneration: Long,
        val requestGeneration: Long,
        val key: String,
    )

    private var homeTopologyGeneration = 0L
    private var homeRequestGeneration = 0L
    private var lastPreferenceRecommendEnabled: Boolean? = null
    private val homeCategoryCache =
        mutableMapOf<String, List<HomeSwiperComicListItemResponse.ListItem>>()
    private var networkHomeCache: List<HomeSwiperComicListItemResponse>? = null
    private val activeCategoryLoads = mutableMapOf<String, HomeRequestToken>()

    companion object {
        const val CATEGORY_LATEST = "builtin_latest"
        private const val NETWORK_CATEGORY_PREFIX = "net_"
        private const val PROMOTE_LOAD_KEY = "promote_sections"
        val EMBEDDED_CATEGORIES = listOf(
            HomeCategoryInfo(CATEGORY_LATEST, "最新上架"),
            HomeCategoryInfo("builtin_week_hot", "本周热门"),
            HomeCategoryInfo("builtin_month_hot", "本月热门"),
            HomeCategoryInfo("builtin_most_liked", "最多喜欢"),
            HomeCategoryInfo("builtin_random", "随机推荐"),
            HomeCategoryInfo("builtin_doujin", "同人"),
            HomeCategoryInfo("builtin_single", "单本"),
            HomeCategoryInfo("builtin_short", "短篇"),
            HomeCategoryInfo("builtin_korean", "韩漫"),
            HomeCategoryInfo("builtin_american", "美漫"),
            HomeCategoryInfo("builtin_cosplay", "Cosplay"),
            HomeCategoryInfo("builtin_3d", "3D"),
            HomeCategoryInfo("builtin_most_images", "图片最多"),
        )
    }

    /** Rebuild Home from Embedded categories plus the optional network recommendation feed. */
    fun refreshHome() {
        val setting = localSettingManager.localSettingState.value
        val recommendEnabled = setting.preferenceRecommendEnabled
        val topologyChanged = recommendEnabled != lastPreferenceRecommendEnabled
        if (topologyChanged) {
            // 分类结构变化时作废所有旧 token。阻塞式 JM 请求即使稍后返回，也不能写回新结构。
            homeTopologyGeneration++
            activeCategoryLoads.clear()
            _homeState.value = HomeUIState()
        }
        lastPreferenceRecommendEnabled = recommendEnabled

        if (recommendEnabled) {
            val cached = networkHomeCache
            if (cached != null) {
                applyPromoteHome(cached)
            } else {
                loadPromoteSections()
            }
        } else {
            applyEmbeddedHome()
        }
    }

    fun selectHomeCategory(categoryId: String) {
        val current = _homeState.value
        if (current.categories.none { it.id == categoryId }) return
        if (current.selectedCategoryId == categoryId) {
            // A → B → A 或旧请求被作废后，即使 UI 已选中 A，也要保证 A 有缓存或有效请求。
            ensureCategoryLoaded(categoryId)
            return
        }
        _homeState.update { it.copy(selectedCategoryId = categoryId) }
        ensureCategoryLoaded(categoryId)
    }

    /** Explicit retry for the currently selected category after a load failure. */
    fun refreshSelectedHomeCategory() {
        val selected = _homeState.value.selectedCategoryId ?: return
        ensureCategoryLoaded(selected, force = true)
    }

    private fun ensureCategoryLoaded(categoryId: String, force: Boolean = false) {
        val state = _homeState.value.states[categoryId]
        val active = activeCategoryLoads[categoryId]
        if (!force && active?.topologyGeneration == homeTopologyGeneration) return
        if (!force && homeCategoryCache.containsKey(categoryId)) {
            hydrateCategory(categoryId)
            return
        }

        if (isPromoteCategory(categoryId)) {
            loadPromoteSections(
                force = force,
                refreshingCategoryId = categoryId,
            )
            return
        }

        val token = newHomeRequestToken(categoryId)
        activeCategoryLoads[categoryId] = token
        updateCategoryState(
            categoryId,
            (state ?: HomeCategoryLoadState()).copy(
                isLoading = true,
                isError = false,
                errorMsg = null,
            )
        )
        viewModelScope.launch {
            try {
                if (!isCurrentHomeRequest(token, requireCategory = true)) return@launch
                val result = comicRepository.getEmbeddedHomeCategory(categoryId)
                ensureActive()
                if (!isCurrentHomeRequest(token, requireCategory = true)) return@launch

                when (result) {
                    is NetWorkResult.Error -> {
                        val current = _homeState.value.states[categoryId] ?: HomeCategoryLoadState()
                        updateCategoryState(
                            categoryId,
                            current.copy(
                                isLoading = false,
                                isError = true,
                                errorMsg = result.message,
                            )
                        )
                    }

                    is NetWorkResult.Success -> {
                        homeCategoryCache[categoryId] = result.data
                        updateCategoryState(categoryId, HomeCategoryLoadState(content = result.data))
                    }
                }
            } finally {
                if (activeCategoryLoads[categoryId] == token) {
                    activeCategoryLoads.remove(categoryId)
                }
            }
        }
    }

    private fun loadPromoteSections(
        force: Boolean = false,
        refreshingCategoryId: String? = null,
    ) {
        val active = activeCategoryLoads[PROMOTE_LOAD_KEY]
        if (!force && active?.topologyGeneration == homeTopologyGeneration) return

        val token = newHomeRequestToken(PROMOTE_LOAD_KEY)
        activeCategoryLoads[PROMOTE_LOAD_KEY] = token
        refreshingCategoryId?.let { categoryId ->
            val current = _homeState.value.states[categoryId] ?: HomeCategoryLoadState()
            updateCategoryState(
                categoryId,
                current.copy(isLoading = true, isError = false, errorMsg = null),
            )
        }
        viewModelScope.launch {
            try {
                if (!isCurrentHomeRequest(token)) return@launch
                val result = comicRepository.getNetworkHomePage()
                ensureActive()
                if (!isCurrentHomeRequest(token)) return@launch
                when (result) {
                    is NetWorkResult.Success -> {
                        networkHomeCache = result.data
                        applyPromoteHome(result.data)
                    }

                    is NetWorkResult.Error -> {
                        when {
                            refreshingCategoryId != null -> {
                                val current = _homeState.value.states[refreshingCategoryId]
                                    ?: HomeCategoryLoadState()
                                updateCategoryState(
                                    refreshingCategoryId,
                                    current.copy(
                                        isLoading = false,
                                        isError = true,
                                        errorMsg = result.message,
                                    ),
                                )
                            }

                            else -> applyEmbeddedHome()
                        }
                    }
                }
            } finally {
                if (activeCategoryLoads[PROMOTE_LOAD_KEY] == token) {
                    activeCategoryLoads.remove(PROMOTE_LOAD_KEY)
                }
            }
        }
    }

    private fun applyPromoteHome(categories: List<HomeSwiperComicListItemResponse>) {
        val promoteSections = reorderPromoteSections(categories.filter { it.content.isNotEmpty() })
        if (promoteSections.isEmpty()) {
            applyEmbeddedHome()
            return
        }

        val networkInfos = promoteSections.map { item ->
            HomeCategoryInfo(networkCategoryId(item.id), item.title)
        }
        val allInfos = networkInfos + EMBEDDED_CATEGORIES
        val validIds = allInfos.mapTo(mutableSetOf()) { it.id }
        val newStates = _homeState.value.states
            .filterKeys { it in validIds }
            .toMutableMap()
        promoteSections.forEach { item ->
            val id = networkCategoryId(item.id)
            homeCategoryCache[id] = item.content
            newStates[id] = HomeCategoryLoadState(content = item.content)
        }
        EMBEDDED_CATEGORIES.forEach { info ->
            homeCategoryCache[info.id]?.let { content ->
                newStates[info.id] = HomeCategoryLoadState(content = content)
            }
        }
        val selected = _homeState.value.selectedCategoryId
            ?.takeIf { it in validIds }
            ?: networkInfos.first().id
        _homeState.value = HomeUIState(
            categories = allInfos,
            selectedCategoryId = selected,
            states = newStates,
        )
    }

    private fun applyEmbeddedHome() {
        val validIds = EMBEDDED_CATEGORIES.mapTo(mutableSetOf()) { it.id }
        val states = _homeState.value.states
            .filterKeys { it in validIds }
            .toMutableMap()
        EMBEDDED_CATEGORIES.forEach { info ->
            homeCategoryCache[info.id]?.let { content ->
                states[info.id] = HomeCategoryLoadState(content = content)
            }
        }
        val selected = _homeState.value.selectedCategoryId
            ?.takeIf { it in validIds }
            ?: CATEGORY_LATEST
        _homeState.value = HomeUIState(
            categories = EMBEDDED_CATEGORIES,
            selectedCategoryId = selected,
            states = states,
        )
        ensureCategoryLoaded(selected)
    }

    private fun hydrateCategory(categoryId: String) {
        val content = homeCategoryCache[categoryId].orEmpty()
        updateCategoryState(categoryId, HomeCategoryLoadState(content = content))
    }

    private fun updateCategoryState(
        categoryId: String,
        loadState: HomeCategoryLoadState,
    ) {
        _homeState.update {
            it.copy(states = it.states + (categoryId to loadState))
        }
    }

    private fun networkCategoryId(rawId: String): String = "$NETWORK_CATEGORY_PREFIX$rawId"

    private fun isPromoteCategory(categoryId: String): Boolean =
        categoryId.startsWith(NETWORK_CATEGORY_PREFIX)

    private fun newHomeRequestToken(key: String): HomeRequestToken =
        HomeRequestToken(
            topologyGeneration = homeTopologyGeneration,
            requestGeneration = ++homeRequestGeneration,
            key = key,
        )

    private fun isCurrentHomeRequest(
        token: HomeRequestToken,
        requireCategory: Boolean = false,
    ): Boolean =
        token.topologyGeneration == homeTopologyGeneration &&
            activeCategoryLoads[token.key] == token &&
            (!requireCategory || _homeState.value.categories.any { it.id == token.key })

    private val _searchComicFilterState = MutableStateFlow(SearchComicFilter())
    val searchComicFilterState = _searchComicFilterState.asStateFlow()
    private val _searchComicIdState = MutableStateFlow<Int?>(null)
    val searchComicIdState = _searchComicIdState.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchComicPager = combine(
        _searchComicFilterState,
        localSettingManager.localSettingState
    ) { filter, localSetting -> filter to localSetting.blockedTagList }
        .flatMapLatest { (filter, blockedTagList) ->
        Pager(
            config = PagingConfig(
                pageSize = 20,
                prefetchDistance = 6,
                initialLoadSize = 20
            ),
            pagingSourceFactory = {
                SearchComicPagingSource(
                    comicRepository,
                    filter.copy(excludedTags = (filter.excludedTags + blockedTagList).distinct()),
                ) { id ->
                    _searchComicIdState.update {
                        id
                    }
                }
            }
        ).flow
    }.cachedIn(viewModelScope)

    fun changeSearchComicOrderFilter(order: ComicSearchOrderFilter) {
        _searchComicIdState.update { null }
        _searchComicFilterState.update {
            it.copy(
                order = order
            )
        }
    }

    fun changeSearchComicContent(searchContent: String) {
        _searchComicIdState.update { null }
        _searchComicFilterState.update {
            it.copy(
                searchContent = searchContent
            )
        }
    }

    fun changeSearchComicContent(searchContent: String, excludedTags: List<String>) {
        _searchComicIdState.update { null }
        _searchComicFilterState.update {
            it.copy(
                searchContent = searchContent,
                excludedTags = excludedTags
            )
        }
    }

    fun consumeSearchComicId() {
        _searchComicIdState.update { null }
    }

    private val _weekDataState = MutableStateFlow(CommonUIState<WeekData>())
    val weekDataState = _weekDataState.asStateFlow()
    fun getWeekData() {
        viewModelScope.launch {
            _weekDataState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = comicRepository.getWeekData()) {
                is NetWorkResult.Error -> {
                    _weekDataState.update {
                        it.copy(isError = true, errorMsg = data.message)
                    }
                }

                is NetWorkResult.Success<WeekResponse> -> {
                    val d = data.data.toWeekData()
                    _weekDataState.update {
                        it.copy(data = d)
                    }
                    if (d.categoryList.isNotEmpty()) {
                        _weekFilterState.update {
                            it.copy(categoryId = d.categoryList[0].first)
                        }
                    }
                    if (d.typeList.isNotEmpty()) {
                        _weekFilterState.update {
                            it.copy(typeId = d.typeList[0].first)
                        }
                    }
                }
            }
            _weekDataState.update {
                it.copy(isLoading = false)
            }
        }
    }

    private val _weekFilterState = MutableStateFlow(WeekFilter())
    val weekFilterState = _weekFilterState.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val weekComicPager = combine(
        _weekFilterState,
        localSettingManager.localSettingState
    ) { filter, localSetting ->
        filter to (localSetting.blockedTagList + localSetting.homeExcludedTags).distinct()
    }
        .flatMapLatest { (filter, blockedTagList) ->
        Pager(
            config = PagingConfig(
                pageSize = 20,
                prefetchDistance = 6,
                initialLoadSize = 20
            ),
            pagingSourceFactory = {
                WeekComicPagingSource(
                    comicRepository,
                    filter,
                    blockedTagList
                )
            }
        ).flow
    }.cachedIn(viewModelScope)

    fun changeWeekCategoryFilter(categoryId: String?) {
        _weekFilterState.update {
            it.copy(
                categoryId = categoryId
            )
        }
    }

    fun changeWeekTypeFilter(typeId: String?) {
        _weekFilterState.update {
            it.copy(
                typeId = typeId
            )
        }
    }
}

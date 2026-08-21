package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.COMIC_API_SOURCE_NETWORK
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

    private var homeRequestGeneration = 0L
    private var lastHomeSource: String? = null
    private var lastPreferenceRecommendEnabled: Boolean? = null
    private val homeCategoryCache =
        mutableMapOf<String, List<HomeSwiperComicListItemResponse.ListItem>>()
    private var networkHomeCache: List<HomeSwiperComicListItemResponse>? = null
    private val activeCategoryLoads = mutableMapOf<String, Long>()

    companion object {
        const val CATEGORY_RECOMMEND = "home_recommend"
        const val CATEGORY_LATEST = "builtin_latest"
        const val CATEGORY_NETWORK_HOME = "home_network"
        val BUILTIN_CATEGORIES = listOf(
            HomeCategoryInfo(CATEGORY_LATEST, "最新上架"),
            HomeCategoryInfo("builtin_week_hot", "本周热门"),
            HomeCategoryInfo("builtin_month_hot", "本月热门"),
            HomeCategoryInfo("builtin_most_liked", "最多喜欢"),
            HomeCategoryInfo("builtin_random", "随机推荐"),
            HomeCategoryInfo("builtin_serialization", "连载系列"),
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

    /**
     * 按“当前数据源 + 推荐开关”重建首页分类表，并只加载默认分类：
     * - 内置/混合数据源：推荐开启时默认“推荐本本”，否则默认“最新上架”。
     * - 网络数据源：首页整页来自网络 API（单次请求），分类表由响应展开。
     * 其它分类保持 lazy：点击后才请求，结果按 (source, categoryId) 缓存。
     */
    fun refreshHome() {
        val setting = localSettingManager.localSettingState.value
        val source = setting.comicApiSource
        if (source != lastHomeSource) {
            // 数据源变化：作废在途请求、丢弃旧数据源缓存，避免错误复用。
            homeRequestGeneration++
            homeCategoryCache.clear()
            networkHomeCache = null
            lastHomeSource = source
            _homeState.update { it.copy(states = emptyMap()) }
        }
        if (source == COMIC_API_SOURCE_NETWORK) {
            val cached = networkHomeCache
            if (cached != null) {
                applyNetworkHome(cached)
            } else {
                _homeState.update {
                    it.copy(
                        categories = listOf(HomeCategoryInfo(CATEGORY_NETWORK_HOME, "首页")),
                        selectedCategoryId = CATEGORY_NETWORK_HOME,
                    )
                }
                ensureCategoryLoaded(CATEGORY_NETWORK_HOME)
            }
        } else {
            val categories = buildList {
                if (setting.preferenceRecommendEnabled) {
                    add(HomeCategoryInfo(CATEGORY_RECOMMEND, "推荐本本"))
                }
                addAll(BUILTIN_CATEGORIES)
            }
            // 推荐开关发生切换时，默认分类跟随开关：ON → 推荐本本，OFF → 最新上架。
            val prefEnabled = setting.preferenceRecommendEnabled
            val prefChanged = lastPreferenceRecommendEnabled != prefEnabled
            lastPreferenceRecommendEnabled = prefEnabled
            val selected = when {
                prefChanged -> if (prefEnabled) CATEGORY_RECOMMEND else CATEGORY_LATEST
                else -> _homeState.value.selectedCategoryId
                    ?.takeIf { id -> categories.any { it.id == id } }
                    ?: if (prefEnabled) CATEGORY_RECOMMEND else CATEGORY_LATEST
            }
            _homeState.update {
                it.copy(categories = categories, selectedCategoryId = selected)
            }
            ensureCategoryLoaded(selected)
        }
    }

    fun selectHomeCategory(categoryId: String) {
        val current = _homeState.value
        if (current.selectedCategoryId == categoryId) return
        if (current.categories.none { it.id == categoryId }) return
        _homeState.update { it.copy(selectedCategoryId = categoryId) }
        ensureCategoryLoaded(categoryId)
    }

    /** 下拉刷新：只刷新当前分类。 */
    fun refreshSelectedHomeCategory() {
        val selected = _homeState.value.selectedCategoryId ?: return
        ensureCategoryLoaded(selected, force = true)
    }

    private fun ensureCategoryLoaded(categoryId: String, force: Boolean = false) {
        val source = localSettingManager.localSettingState.value.comicApiSource
        val state = _homeState.value.states[categoryId]
        // 同一个分类同时只允许一个 active request。
        if (!force && activeCategoryLoads.containsKey(categoryId)) return
        // 仅当 loading 状态对应一个仍在运行的请求时才去重；被 generation 作废的孤立
        // loading（请求已被取消/陈旧）不得阻塞后续点击加载。
        if (!force && state?.isLoading == true && activeCategoryLoads.containsKey(categoryId)) return
        if (!force && homeCategoryCache.containsKey(cacheKey(source, categoryId))) {
            hydrateCategory(categoryId, source)
            return
        }
        if (!force && categoryId == CATEGORY_RECOMMEND && networkHomeCache != null) {
            val content = networkHomeCache.orEmpty()
                .flatMap { it.content }
                .distinctBy { it.id }
            homeCategoryCache[cacheKey(source, categoryId)] = content
            updateCategoryState(categoryId, HomeCategoryLoadState(content = content))
            return
        }

        val generation = ++homeRequestGeneration
        activeCategoryLoads[categoryId] = generation
        viewModelScope.launch {
            try {
                if (!isCurrentHomeRequest(generation, source, categoryId)) return@launch
                updateCategoryState(
                    categoryId,
                    (state ?: HomeCategoryLoadState()).copy(
                        isLoading = true,
                        isError = false,
                        errorMsg = null,
                    )
                )
                if (categoryId == CATEGORY_NETWORK_HOME) {
                    // 网络数据源：整页一次请求，展开为多个分类 tab。
                    val page = comicRepository.getNetworkHomePage()
                    ensureActive()
                    if (!isCurrentHomeRequest(generation, source, categoryId)) return@launch
                    when (page) {
                        is NetWorkResult.Error -> {
                            updateCategoryState(
                                categoryId,
                                HomeCategoryLoadState(
                                    isLoading = false,
                                    isError = true,
                                    errorMsg = page.message,
                                )
                            )
                        }

                        is NetWorkResult.Success -> {
                            networkHomeCache = page.data
                            applyNetworkHome(page.data)
                        }
                    }
                    return@launch
                }

                val result = if (categoryId == CATEGORY_RECOMMEND) {
                    comicRepository.getNetworkHomePage().mapPageToFlatList()
                } else {
                    comicRepository.getEmbeddedHomeCategory(categoryId)
                }
                ensureActive()
                if (!isCurrentHomeRequest(generation, source, categoryId)) return@launch

                when (result) {
                    is NetWorkResult.Error -> {
                        updateCategoryState(
                            categoryId,
                            HomeCategoryLoadState(
                                isLoading = false,
                                isError = true,
                                errorMsg = result.message,
                            )
                        )
                    }

                    is NetWorkResult.Success -> {
                        homeCategoryCache[cacheKey(source, categoryId)] = result.data
                        updateCategoryState(categoryId, HomeCategoryLoadState(content = result.data))
                    }
                }
            } finally {
                if (activeCategoryLoads[categoryId] == generation) {
                    activeCategoryLoads.remove(categoryId)
                }
            }
        }
    }

    private fun applyNetworkHome(categories: List<HomeSwiperComicListItemResponse>) {
        val filtered = categories.filter { it.content.isNotEmpty() }
        if (filtered.isEmpty()) {
            val state = _homeState.value.states[CATEGORY_NETWORK_HOME]
                ?: HomeCategoryLoadState()
            _homeState.update {
                it.copy(
                    categories = listOf(HomeCategoryInfo(CATEGORY_NETWORK_HOME, "首页")),
                    selectedCategoryId = CATEGORY_NETWORK_HOME,
                    states = it.states + (
                        CATEGORY_NETWORK_HOME to state.copy(
                            isLoading = false,
                            isError = true,
                            errorMsg = "暂无首页数据",
                        )
                    ),
                )
            }
            return
        }
        val source = localSettingManager.localSettingState.value.comicApiSource
        val infos = filtered.map { HomeCategoryInfo(networkCategoryId(it.id), it.title) }
        val newStates = mutableMapOf<String, HomeCategoryLoadState>()
        filtered.forEach { item ->
            val id = networkCategoryId(item.id)
            newStates[id] = HomeCategoryLoadState(content = item.content)
            homeCategoryCache[cacheKey(source, id)] = item.content
        }
        val selected = _homeState.value.selectedCategoryId
            ?.takeIf { id -> infos.any { it.id == id } }
            ?: infos.first().id
        _homeState.update {
            it.copy(
                categories = infos,
                selectedCategoryId = selected,
                states = it.states + newStates,
            )
        }
    }

    private fun hydrateCategory(categoryId: String, source: String) {
        val content = homeCategoryCache[cacheKey(source, categoryId)].orEmpty()
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

    private fun cacheKey(source: String, categoryId: String): String = "$source:$categoryId"

    private fun networkCategoryId(rawId: String): String = "net_$rawId"

    private fun isCurrentHomeRequest(
        generation: Long,
        source: String,
        categoryId: String,
    ): Boolean =
        homeRequestGeneration == generation &&
            lastHomeSource == source &&
            localSettingManager.localSettingState.value.comicApiSource == source &&
            _homeState.value.categories.any { it.id == categoryId }

    private fun NetWorkResult<List<HomeSwiperComicListItemResponse>>
        .mapPageToFlatList(): NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>> {
        return when (this) {
            is NetWorkResult.Success -> NetWorkResult.Success(
                data.flatMap { it.content }.distinctBy { it.id }
            )

            is NetWorkResult.Error -> this
        }
    }

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

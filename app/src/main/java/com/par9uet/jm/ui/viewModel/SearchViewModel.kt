package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.store.ContentPreferences
import com.par9uet.jm.ui.pagingSource.SearchComicFilter
import com.par9uet.jm.ui.pagingSource.SearchComicPagingSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

data class SearchViewportState(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val resetGeneration: Long = 0L,
) {
    fun reset(): SearchViewportState = SearchViewportState(
        resetGeneration = resetGeneration + 1L,
    )
}

class SearchViewModel(
    private val comicRepository: ComicRepository,
    private val contentPreferences: ContentPreferences,
) : ViewModel() {
    private val _searchComicFilterState = MutableStateFlow(SearchComicFilter())
    val searchComicFilterState = _searchComicFilterState.asStateFlow()
    private val _searchComicIdState = MutableStateFlow<Int?>(null)
    val searchComicIdState = _searchComicIdState.asStateFlow()
    private val _searchViewportState = MutableStateFlow(SearchViewportState())
    val searchViewportState = _searchViewportState.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchComicPager = combine(
        _searchComicFilterState,
        contentPreferences.blockedTags
    ) { filter, blockedTagList -> filter to blockedTagList }
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
        val current = _searchComicFilterState.value
        val next = current.copy(order = order)
        if (next == current) return
        _searchComicFilterState.value = next
        _searchViewportState.update(SearchViewportState::reset)
    }

    fun changeSearchComicContent(searchContent: String) {
        _searchComicIdState.update { null }
        updateSearchFilter(_searchComicFilterState.value.copy(searchContent = searchContent))
    }

    fun changeSearchComicContent(searchContent: String, excludedTags: List<String>) {
        _searchComicIdState.update { null }
        updateSearchFilter(
            _searchComicFilterState.value.copy(
                searchContent = searchContent,
                excludedTags = excludedTags,
            )
        )
    }

    fun saveSearchViewport(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
        resetGeneration: Long,
    ) {
        _searchViewportState.update { current ->
            if (current.resetGeneration != resetGeneration) {
                current
            } else {
                current.copy(
                    firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
                    firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0),
                )
            }
        }
    }

    private fun updateSearchFilter(next: SearchComicFilter) {
        if (next == _searchComicFilterState.value) return
        _searchComicFilterState.value = next
        _searchViewportState.update(SearchViewportState::reset)
    }

    fun consumeSearchComicId() {
        _searchComicIdState.update { null }
    }

}

package com.par9uet.jm.ui.pagingSource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.UserHistoryComicListResponse
import com.par9uet.jm.contentfilter.filterBlockedTags

class HistoryComicPagingSource(
    private val userRepository: UserRepository,
    private val blockedTagList: List<String> = listOf(),
) : PagingSource<Int, Comic>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Comic> {
        val currentPage = params.key ?: 1
        return when (val data =
            userRepository.getHistoryComicList(currentPage)) {
            is NetWorkResult.Error -> {
                LoadResult.Error(Exception(data.message))
            }

            is NetWorkResult.Success<UserHistoryComicListResponse> -> {
                val list = data.data.toComicList().filterBlockedTags(blockedTagList)
                // watch_list has no total and always uses the server page size. Paging may
                // request a larger loadSize; tag filtering must not truncate pagination.
                val isLastPage = data.data.list.size < PAGE_SIZE
                LoadResult.Page(
                    data = list,
                    prevKey = if (currentPage == 1) null else currentPage - 1,
                    nextKey = if (isLastPage) null else currentPage + 1
                )
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 20
    }

    override fun getRefreshKey(state: PagingState<Int, Comic>): Int? = null
}

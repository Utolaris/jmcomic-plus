package com.par9uet.jm.favorites.model

import androidx.paging.PagingSource
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.TagFilterLogic
import kotlinx.coroutines.flow.Flow

/** L4 query capabilities for the Room-backed local Favorites snapshot. */
interface FavoriteLocalQuery {
    fun pagingSource(
        accountId: Int,
        blockedTagList: List<String>,
        searchText: String,
        selectedTags: Set<String>,
        selectedAuthors: Set<String>,
        folderId: Int,
        tagLogic: TagFilterLogic,
    ): PagingSource<Int, Comic>

    fun observeFolders(accountId: Int): Flow<Map<String, String>>

    fun observeTagCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>>

    fun observeAuthorCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>>

    suspend fun getCachedFolders(accountId: Int): Map<String, String>

    suspend fun getComics(accountId: Int, albumIds: Collection<Int>): List<Comic>
}

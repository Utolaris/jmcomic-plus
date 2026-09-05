package com.par9uet.jm.store

import com.par9uet.jm.database.model.FavoriteComicEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteSyncPlannerTest {

    @Test
    fun deltaSeparatesAddedRemovedChangedAndUnchangedItems() {
        val existing = mapOf(
            1 to comic(1, "不变", authors = listOf("作者"), tags = listOf("标签")),
            2 to comic(2, "旧标题"),
            3 to comic(3, "已移除"),
        )
        val remote = listOf(
            FavoriteRemoteItem(1, "不变"),
            FavoriteRemoteItem(2, "新标题"),
            FavoriteRemoteItem(4, "新增"),
        )

        val delta = planFavoriteSync(setOf(1, 2, 3), existing, remote)

        assertEquals(1, delta.added)
        assertEquals(1, delta.removed)
        assertEquals(1, delta.changed)
        assertEquals(1, delta.unchanged)
        assertEquals(listOf(2, 4), delta.metadataIds)
    }

    @Test
    fun unchangedCompleteMetadataIsNotScheduledForAlbumFetch() {
        val local = comic(1, "漫画", authors = listOf("作者"), tags = listOf("标签"))
        val remote = FavoriteRemoteItem(
            albumId = 1,
            title = "漫画",
            authors = listOf("作者"),
            tags = listOf("标签"),
        )

        val delta = planFavoriteSync(setOf(1), mapOf(1 to local), listOf(remote))

        assertEquals(0, delta.changed)
        assertEquals(1, delta.unchanged)
        assertEquals(emptyList<Int>(), delta.metadataIds)
    }

    @Test
    fun changedRemoteAuthorOrTagIsScheduledForAlbumFetch() {
        val local = comic(1, "漫画", authors = listOf("旧作者"), tags = listOf("旧标签"))
        val remote = FavoriteRemoteItem(
            albumId = 1,
            title = "漫画",
            authors = listOf("新作者"),
            tags = listOf("新标签"),
        )

        val delta = planFavoriteSync(setOf(1), mapOf(1 to local), listOf(remote))

        assertEquals(listOf(1), delta.metadataIds)
    }

    private fun comic(
        id: Int,
        title: String,
        authors: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ) = FavoriteComicEntity(
        accountId = 7,
        albumId = id,
        title = title,
        authorList = authors,
        tagList = tags,
        metadataComplete = true,
    )
}

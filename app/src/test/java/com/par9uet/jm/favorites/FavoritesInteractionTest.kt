package com.par9uet.jm.favorites

import com.par9uet.jm.database.model.FavoriteComicEntity
import com.par9uet.jm.store.FavoriteRemoteItem
import com.par9uet.jm.store.FAVORITE_SCOPE_ALL
import com.par9uet.jm.store.nextTemporaryRemoteOrder
import com.par9uet.jm.store.planFavoriteSync
import com.par9uet.jm.store.resolveGlobalOrderAfterScopeSync
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesInteractionTest {
    @Test
    fun `sync planner distinguishes added changed removed and unchanged rows`() {
        val existing = mapOf(
            1 to FavoriteComicEntity(7, 1, "旧标题", lastFavoriteOrder = 5),
            2 to FavoriteComicEntity(7, 2, "相同", lastFavoriteOrder = 6),
        )
        val delta = planFavoriteSync(
            oldScopeIds = setOf(1, 2, 3),
            existing = existing,
            remoteItems = listOf(
                FavoriteRemoteItem(1, "新标题"),
                FavoriteRemoteItem(2, "相同"),
                FavoriteRemoteItem(4, "新增"),
            ),
        )

        assertEquals(1, delta.added)
        assertEquals(1, delta.changed)
        assertEquals(1, delta.removed)
        assertEquals(1, delta.unchanged)
    }

    @Test
    fun `all favorites adopt remote order while folder scopes keep global order`() {
        assertEquals(3, resolveGlobalOrderAfterScopeSync(FAVORITE_SCOPE_ALL, 3, 99))
        assertEquals(99, resolveGlobalOrderAfterScopeSync(4, 3, 99))
        assertEquals(10, nextTemporaryRemoteOrder(9))
    }
}

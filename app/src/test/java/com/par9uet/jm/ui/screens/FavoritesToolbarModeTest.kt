package com.par9uet.jm.ui.screens

import com.par9uet.jm.ui.screens.tabScreen.FavoritesToolbarMode
import com.par9uet.jm.ui.screens.tabScreen.resolveFavoriteFolderTitle
import com.par9uet.jm.ui.screens.tabScreen.resolveFavoritesToolbarMode
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesToolbarModeTest {
    @Test
    fun `normal mode has no search or selection`() {
        assertEquals(
            FavoritesToolbarMode.NORMAL,
            resolveFavoritesToolbarMode(searchActive = false, selectedCount = 0),
        )
    }

    @Test
    fun `search mode follows active local search`() {
        assertEquals(
            FavoritesToolbarMode.SEARCH,
            resolveFavoritesToolbarMode(searchActive = true, selectedCount = 0),
        )
    }

    @Test
    fun `selection wins over search`() {
        assertEquals(
            FavoritesToolbarMode.SELECTION,
            resolveFavoritesToolbarMode(searchActive = true, selectedCount = 2),
        )
    }

    @Test
    fun `all favorites and custom folders use the required titles`() {
        val folders = mapOf("0" to "全部", "7" to "旅行收藏")
        assertEquals("我的收藏", resolveFavoriteFolderTitle(0, folders))
        assertEquals("旅行收藏", resolveFavoriteFolderTitle(7, folders))
    }
}

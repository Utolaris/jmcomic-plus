package com.par9uet.jm.ui.screens

import com.par9uet.jm.ui.viewModel.HomeViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCategoryTitleTest {
    private val categories = listOf(
        HomeViewModel.HomeCategoryInfo("latest", "最新上架"),
        HomeViewModel.HomeCategoryInfo("popular", "本周热门"),
    )

    @Test
    fun `title follows the selected category id`() {
        assertEquals("本周热门", resolveHomeCategoryTitle(categories, "popular"))
    }

    @Test
    fun `missing selection falls back to home`() {
        assertEquals("首页", resolveHomeCategoryTitle(categories, "unknown"))
        assertEquals("首页", resolveHomeCategoryTitle(emptyList(), null))
    }
}

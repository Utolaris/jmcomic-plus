package com.par9uet.jm.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicSearchResultNavigationTest {
    @Test
    fun `search result returns to comic detail when opened from a tag or author`() {
        assertEquals(
            SearchResultBackTarget.PREVIOUS_SCREEN,
            searchResultBackTarget("comicDetail/{id}"),
        )
    }

    @Test
    fun `search result returns to download detail when opened from its tags`() {
        assertEquals(
            SearchResultBackTarget.PREVIOUS_SCREEN,
            searchResultBackTarget("downloadComicDetail/{id}"),
        )
    }

    @Test
    fun `search result returns to existing search editor`() {
        assertEquals(
            SearchResultBackTarget.PREVIOUS_SCREEN,
            searchResultBackTarget("comicSearch?searchContent={searchContent}"),
        )
    }

    @Test
    fun `search result without a previous screen falls back to search editor`() {
        assertEquals(
            SearchResultBackTarget.SEARCH_EDITOR,
            searchResultBackTarget(null),
        )
    }
}

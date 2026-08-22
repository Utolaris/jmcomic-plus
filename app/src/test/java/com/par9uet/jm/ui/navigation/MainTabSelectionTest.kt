package com.par9uet.jm.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainTabSelectionTest {
    private val home = MainTab.Home.index
    private val collect = MainTab.Collect.index
    private val settings = MainTab.Settings.index

    @Test
    fun `same settled page and idle pager ignores the tap`() {
        assertTrue(
            shouldIgnoreTabSelection(
                settledPage = home,
                currentPage = home,
                targetPage = home,
                isScrollInProgress = false,
                requestedPage = home,
            )
        )
    }

    @Test
    fun `tap while a scroll is in progress is never ignored`() {
        assertFalse(
            shouldIgnoreTabSelection(
                settledPage = collect,
                currentPage = home,
                targetPage = collect,
                isScrollInProgress = true,
                requestedPage = home,
            )
        )
    }

    @Test
    fun `tap after an interrupted scroll recovers the requested page`() {
        assertFalse(
            shouldIgnoreTabSelection(
                settledPage = collect,
                currentPage = home,
                targetPage = collect,
                isScrollInProgress = false,
                requestedPage = home,
            )
        )
    }

    @Test
    fun `rapid retarget never locks out a tab and the last request wins`() {
        val pager = FakePager(settled = home)
        // Home -> Favorites: animation starts, settles on Favorites.
        assertTrue(pager.request(collect))
        // Favorites -> Settings -> Home while the previous animation is still in flight.
        assertTrue(pager.requestInFlight(settings))
        assertTrue(pager.requestInFlight(home))

        assertEquals(home, pager.settledPage)
        assertEquals(home, pager.currentPage)
    }

    @Test
    fun `tapping the current destination while animating settles it again`() {
        val pager = FakePager(settled = collect)
        pager.requestInFlight(home)
        // Mid-flight: settled is still Favorites, so Home remains actionable and settles.
        pager.requestInFlight(home)
        assertEquals(home, pager.settledPage)
    }

    @Test
    fun `entry detection fires only on a real transition into favorites`() {
        // First composition seeds the previous page: already on Favorites is not an entry.
        assertFalse(shouldTriggerFavoriteEntrySync(previousSettledPage = collect, currentSettledPage = collect, favoritesPage = collect))
        assertFalse(shouldTriggerFavoriteEntrySync(previousSettledPage = null, currentSettledPage = collect, favoritesPage = collect))
        // Home -> Favorites: one entry event.
        assertTrue(shouldTriggerFavoriteEntrySync(previousSettledPage = home, currentSettledPage = collect, favoritesPage = collect))
        // Favorites recomposed while still settled: no new entry.
        assertFalse(shouldTriggerFavoriteEntrySync(previousSettledPage = collect, currentSettledPage = collect, favoritesPage = collect))
        // Favorites -> Settings -> Favorites: a new entry event.
        assertFalse(shouldTriggerFavoriteEntrySync(previousSettledPage = collect, currentSettledPage = settings, favoritesPage = collect))
        assertTrue(shouldTriggerFavoriteEntrySync(previousSettledPage = settings, currentSettledPage = collect, favoritesPage = collect))
    }

    /**
     * Minimal pager state model: an accepted request animates current through the request
     * while settled stays behind until the animation completes.
     */
    private class FakePager(
        settled: Int,
    ) {
        var settledPage = settled
            private set
        var currentPage = settled
            private set
        private var isScrollInProgress = false

        fun request(requestedPage: Int): Boolean {
            return accept(requestedPage)
        }

        /** A tap issued while the previous animation is still running. */
        fun requestInFlight(requestedPage: Int): Boolean {
            isScrollInProgress = true
            return accept(requestedPage)
        }

        private fun accept(requestedPage: Int): Boolean {
            val ignored = shouldIgnoreTabSelection(
                settledPage = settledPage,
                currentPage = currentPage,
                targetPage = -1,
                isScrollInProgress = isScrollInProgress,
                requestedPage = requestedPage,
            )
            if (ignored) return false
            // The last tap wins and the animation settles on it.
            currentPage = requestedPage
            settledPage = requestedPage
            isScrollInProgress = false
            return true
        }
    }
}

package com.par9uet.jm.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainTabSelectionTest {
    private val home = MainTab.Home.index
    private val collect = MainTab.Collect.index
    private val settings = MainTab.Settings.index

    // ----- shouldIgnoreTabSelection -----

    @Test
    fun `case A - different pending target overrides same settled page`() {
        // settled = Home, pending = Favorites, not scrolling, request = Home.
        assertFalse(
            shouldIgnoreTabSelection(
                settledPage = home,
                pendingTargetPage = collect,
                isScrollInProgress = false,
                requestedPage = home,
            )
        )
    }

    @Test
    fun `case B - same settled page and idle pager ignores the tap`() {
        assertTrue(
            shouldIgnoreTabSelection(
                settledPage = home,
                pendingTargetPage = null,
                isScrollInProgress = false,
                requestedPage = home,
            )
        )
    }

    @Test
    fun `case C - pending target equal to the request still ignores`() {
        assertTrue(
            shouldIgnoreTabSelection(
                settledPage = home,
                pendingTargetPage = home,
                isScrollInProgress = false,
                requestedPage = home,
            )
        )
    }

    @Test
    fun `case D - scroll in progress is never ignored even with other pending target`() {
        assertFalse(
            shouldIgnoreTabSelection(
                settledPage = collect,
                pendingTargetPage = settings,
                isScrollInProgress = true,
                requestedPage = home,
            )
        )
    }

    @Test
    fun `case E - rapid Home Favorites Home before the first animation starts`() {
        val pager = FakePager(settledPage = home)
        // Tap Favorites: accepted, pending target set, animation NOT started yet.
        assertTrue(pager.press(collect))
        assertEquals(collect, pager.pendingTarget)
        assertEquals(home, pager.settledPage)
        assertFalse(pager.isScrollInProgress)

        // Tap Home while Favorites is still pending and no scrolling has begun.
        assertTrue(pager.press(home))
        assertEquals(home, pager.pendingTarget)

        // The last tap wins: the animation settles on Home.
        pager.settle()
        assertEquals(home, pager.settledPage)
        assertNull(pager.pendingTarget)
    }

    @Test
    fun `case E - rapid Favorites Settings Favorites settles on Favorites`() {
        val pager = FakePager(settledPage = collect)
        assertTrue(pager.press(settings))
        assertTrue(pager.press(collect))
        pager.settle()
        assertEquals(collect, pager.settledPage)
    }

    @Test
    fun `tapping the current settled page with a different pending target retargets it`() {
        val pager = FakePager(settledPage = home)
        pager.press(collect)
        // Re-tap Home: must cancel the pending Favorites target, not ignore the tap.
        assertTrue(pager.press(home))
        pager.settle()
        assertEquals(home, pager.settledPage)
    }

    /**
     * Minimal pager model that models the pending-target state explicitly: a tap only sets
     * the pending target; the animation settles separately, so rapid taps observe the real
     * pre-animation window (settledPage unchanged, pending target set, no scrolling).
     */
    private class FakePager(
        var settledPage: Int,
    ) {
        var currentPage: Int = settledPage
        var pendingTarget: Int? = null
        var isScrollInProgress: Boolean = false

        fun press(requestedPage: Int): Boolean {
            val ignored = shouldIgnoreTabSelection(
                settledPage = settledPage,
                pendingTargetPage = pendingTarget,
                isScrollInProgress = isScrollInProgress,
                requestedPage = requestedPage,
            )
            if (ignored) return false
            pendingTarget = requestedPage
            return true
        }

        fun settle() {
            settledPage = pendingTarget ?: settledPage
            currentPage = settledPage
            pendingTarget = null
            isScrollInProgress = false
        }
    }
}

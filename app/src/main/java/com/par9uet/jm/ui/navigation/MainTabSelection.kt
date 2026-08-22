package com.par9uet.jm.ui.navigation

/**
 * Decides whether tapping [requestedPage] can be skipped because the pager is
 * already on that page and idle.
 *
 * [currentPage] and [targetPage] are intentionally not part of the decision:
 * a click must always be able to recover the pager when the settled page differs
 * from the request, even if the pager is mid-flight or stranded between states.
 */
internal fun shouldIgnoreTabSelection(
    settledPage: Int,
    currentPage: Int,
    targetPage: Int,
    isScrollInProgress: Boolean,
    requestedPage: Int,
): Boolean = settledPage == requestedPage && !isScrollInProgress

/**
 * Detects an actual pager transition INTO [favoritesPage].
 *
 * [previousSettledPage] is null on the first composition (seeded by the caller), so an
 * already-open Favorites page never counts as an entry event (recomposition, rotation,
 * precomposition).
 */
internal fun shouldTriggerFavoriteEntrySync(
    previousSettledPage: Int?,
    currentSettledPage: Int,
    favoritesPage: Int,
): Boolean =
    previousSettledPage != null &&
        previousSettledPage != favoritesPage &&
        currentSettledPage == favoritesPage

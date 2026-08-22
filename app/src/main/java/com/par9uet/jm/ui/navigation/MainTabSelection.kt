package com.par9uet.jm.ui.navigation

/**
 * Decides whether tapping [requestedPage] can be skipped because the pager is already on that
 * page, idle, and no other destination is pending.
 *
 * A tap may only be ignored when ALL of the following hold:
 * - no different pending target exists (or the pending target equals the request), and
 * - the settled page equals the requested page, and
 * - the pager is not scrolling.
 *
 * When a DIFFERENT destination is pending (e.g. the user tapped Favorites and immediately taps
 * Home before the animation started), the tap must NOT be ignored: the previous target must be
 * cancelled so the last tap wins.
 */
internal fun shouldIgnoreTabSelection(
    settledPage: Int,
    pendingTargetPage: Int?,
    isScrollInProgress: Boolean,
    requestedPage: Int,
): Boolean {
    if (pendingTargetPage != null && pendingTargetPage != requestedPage) return false
    return settledPage == requestedPage && !isScrollInProgress
}

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

/**
 * Detects the authentication transition logged-out -> logged-in while Favorites is settled.
 * Only fires once per transition; stable remembered state prevents repeated triggers.
 */
internal fun shouldTriggerFavoriteSyncAfterLogin(
    previousIsLogin: Boolean,
    isLogin: Boolean,
    settledPage: Int,
    favoritesPage: Int,
): Boolean = !previousIsLogin && isLogin && settledPage == favoritesPage

/**
 * Whether the app booting directly onto the Favorites pager (initial route == Favorites,
 * authenticated) should request one eligible automatic sync. [alreadyRequested] prevents
 * repeats after recomposition/rotation.
 */
internal fun shouldRequestInitialFavoriteSync(
    initialPage: Int,
    favoritesPage: Int,
    isLogin: Boolean,
    alreadyRequested: Boolean,
): Boolean = initialPage == favoritesPage && isLogin && !alreadyRequested

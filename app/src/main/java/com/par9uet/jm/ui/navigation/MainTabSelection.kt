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

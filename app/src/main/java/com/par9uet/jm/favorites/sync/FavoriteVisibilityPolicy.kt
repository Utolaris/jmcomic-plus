package com.par9uet.jm.favorites.sync

/**
 * Keeps pager composition separate from Favorites entry policy. Repeated lifecycle reports with
 * the same value do not create another entry or login request.
 */
class FavoriteVisibilityPolicy {
    private var visibilityKnown = false
    private var favoritesVisible = false
    private var authenticationKnown = false
    private var authenticated = false

    fun onVisibilityChanged(visible: Boolean): Boolean {
        val entered = visible && (!visibilityKnown || !favoritesVisible)
        visibilityKnown = true
        favoritesVisible = visible
        return entered && authenticated
    }

    fun onAuthenticationChanged(isAuthenticated: Boolean): Boolean {
        val becameAuthenticated = isAuthenticated &&
            (!authenticationKnown || !authenticated)
        authenticationKnown = true
        authenticated = isAuthenticated
        return becameAuthenticated && favoritesVisible
    }
}

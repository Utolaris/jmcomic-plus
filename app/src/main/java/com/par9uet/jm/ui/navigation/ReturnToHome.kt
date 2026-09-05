package com.par9uet.jm.ui.navigation

import androidx.navigation.NavHostController

internal fun NavHostController.returnToHome() {
    navigate("tab/home") {
        // Recreate the tab destination so a retained Favorites/Settings pager cannot win.
        popUpTo(graph.id)
        launchSingleTop = true
    }
}

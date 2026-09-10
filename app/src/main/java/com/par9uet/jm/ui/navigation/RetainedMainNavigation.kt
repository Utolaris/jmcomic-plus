package com.par9uet.jm.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController

/** Keep the navigation saver registered even while the app lock removes the main UI. */
@Composable
internal fun RetainedMainNavigation(visible: Boolean, content: @Composable (NavHostController) -> Unit) {
    val stateHolder = rememberSaveableStateHolder()
    if (visible) {
        stateHolder.SaveableStateProvider("main-navigation") {
            content(rememberNavController())
        }
    }
}

package com.par9uet.jm.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RetainedMainNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlineReaderBackStackSurvivesLockAndSavedStateRecreation() = checkRestoration("comicRead/42")
    @Test fun localReaderBackStackSurvivesLockAndSavedStateRecreation() = checkRestoration("localComicRead/42")

    private fun checkRestoration(route: String) {
        val visible = mutableStateOf(true)
        lateinit var nav: NavHostController
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            RetainedMainNavigation(visible.value) { controller ->
                nav = controller
                NavHost(controller, "home") {
                    composable("home") { Text("Home") }
                    composable("comicRead/{id}") { Text("Online reader") }
                    composable("localComicRead/{id}") { Text("Local reader") }
                }
            }
        }
        compose.runOnIdle { nav.navigate(route) }
        compose.onNodeWithText(if (route.startsWith("local")) "Local reader" else "Online reader").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText(if (route.startsWith("local")) "Local reader" else "Online reader").assertIsDisplayed()
        compose.runOnIdle { assertTrue(nav.popBackStack()) }
        compose.onNodeWithText("Home").assertIsDisplayed()
    }
}

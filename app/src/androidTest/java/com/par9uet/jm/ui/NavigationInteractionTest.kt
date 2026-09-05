package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.par9uet.jm.ui.components.BackIconButton
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.screens.LocalMainNavController
import com.par9uet.jm.ui.screens.SearchPageFocusEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class NavigationInteractionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var navController: NavHostController

    @Test
    fun shortBackKeepsCustomBehaviorAndLongBackClearsLayersToHome() {
        var shortClicks = 0
        compose.setContent {
            navController = rememberNavController()
            MaterialTheme {
                CompositionLocalProvider(LocalMainNavController provides navController) {
                    NavHost(navController, startDestination = "tab/collect") {
                        composable("tab/{tabName}?", arguments = listOf(navArgument("tabName") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        })) { Text(it.arguments?.getString("tabName").orEmpty()) }
                        composable("detail/{id}") {
                            BackIconButton(onClick = {
                                shortClicks++
                                navController.popBackStack()
                            })
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            navController.navigate("detail/1")
            navController.navigate("detail/2")
        }
        compose.onNodeWithContentDescription("返回上一页，长按返回首页").performClick()
        compose.runOnIdle {
            assertEquals(1, shortClicks)
            assertEquals("1", navController.currentBackStackEntry?.arguments?.getString("id"))
            navController.navigate("detail/2")
        }
        compose.onNodeWithContentDescription("返回上一页，长按返回首页")
            .performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(1, shortClicks)
            assertEquals("home", navController.currentBackStackEntry?.arguments?.getString("tabName"))
            assertFalse(navController.popBackStack())
        }
    }

    @Test
    fun systemBackFromSearchHidesIme() {
        openSearchWithIme()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertImeHiddenOn("home")
    }

    @Test
    fun navigatingFromSearchToResultsHidesIme() {
        openSearchWithIme()
        compose.runOnIdle { navController.navigate("results") }
        assertImeHiddenOn("results")
    }

    private fun openSearchWithIme() {
        compose.setContent {
            navController = rememberNavController()
            MaterialTheme {
                CompositionLocalProvider(LocalMainNavController provides navController) {
                    NavHost(navController, startDestination = "home") {
                        composable("home") { Text("首页") }
                        composable("results") { Text("结果") }
                        composable("search") { entry ->
                            val requester = remember { FocusRequester() }
                            GlassCaptureHost(
                                modifier = Modifier.fillMaxSize(),
                                sourceContent = {
                                    SearchPageFocusEffect(entry)
                                    LaunchedEffect(requester) { requester.requestFocus() }
                                    BasicTextField(
                                        value = "搜索",
                                        onValueChange = {},
                                        modifier = Modifier.focusRequester(requester).testTag("search-input"),
                                    )
                                },
                                overlayContent = { Box(Modifier.fillMaxSize()) },
                            )
                        }
                    }
                }
            }
        }
        compose.runOnIdle { navController.navigate("search") }
        compose.onNodeWithTag("search-input").assertIsFocused()
        compose.waitUntil(5_000) { imeVisible() }
    }

    private fun assertImeHiddenOn(route: String) {
        compose.waitUntil(5_000) { !imeVisible() }
        compose.runOnIdle { assertEquals(route, navController.currentDestination?.route) }
    }

    private fun imeVisible(): Boolean = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
}

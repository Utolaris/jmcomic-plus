package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.par9uet.jm.ui.navigation.returnToHome
import com.par9uet.jm.ui.screens.LocalMainNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class MainNavigationFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `long back navigation target clears nested pages`() {
        lateinit var controller: androidx.navigation.NavHostController
        compose.setContent {
            controller = rememberNavController()
            MaterialTheme {
                CompositionLocalProvider(LocalMainNavController provides controller) {
                    NavHost(controller, startDestination = "tab/home") {
                        composable("tab/{name}", arguments = listOf(navArgument("name") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = "home"
                        })) {}
                        composable("detail/{id}") {}
                    }
                }
            }
        }
        compose.runOnIdle {
            controller.navigate("detail/1")
            controller.navigate("detail/2")
            controller.returnToHome()
        }
        compose.runOnIdle {
            assertEquals("tab/{name}", controller.currentDestination?.route)
            assertEquals("home", controller.currentBackStackEntry?.arguments?.getString("name"))
            assertFalse(controller.popBackStack())
        }
    }
}

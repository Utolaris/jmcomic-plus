package com.par9uet.jm.ui.screens.tabScreen

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.ui.navigation.MainTab
import com.par9uet.jm.ui.navigation.MainTabDirection
import com.par9uet.jm.ui.navigation.NavigationMotion
import com.par9uet.jm.ui.screens.HomeScreen
import com.par9uet.jm.ui.screens.LocalMainNavController
import com.par9uet.jm.ui.screens.UserCollectComicScreen
import com.par9uet.jm.ui.screens.UserScreen
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

@Composable
fun TabScreen(
    tabName: String,
    userManager: UserManager = getKoin().get(),
) {
    val mainNavController = LocalMainNavController.current
    val isLogin by userManager.isLoginState.collectAsState(false)
    val initialTab = MainTab.fromRoute(tabName) ?: MainTab.Home
    val pagerState = rememberPagerState(
        initialPage = initialTab.index,
        pageCount = { MainTab.ordered.size },
    )
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = MainTab.fromIndex(pagerState.currentPage)

    fun selectTab(tab: MainTab) {
        if (selectedTab.directionTo(tab) == MainTabDirection.NONE) return
        coroutineScope.launch {
            pagerState.animateScrollToPage(
                page = tab.index,
                animationSpec = NavigationMotion.MainTabAnimationSpec,
            )
        }
    }

    LaunchedEffect(pagerState.settledPage, isLogin) {
        if (pagerState.settledPage == MainTab.Collect.index && !isLogin) {
            mainNavController.navigate("login")
        }
    }

    BoxWithConstraints {
        val useNavigationRail = maxWidth >= 700.dp
        Scaffold(
            bottomBar = {
                if (!useNavigationRail) {
                    BottomNavigationBarComponent(
                        selectedTab = selectedTab,
                        onTabSelected = ::selectTab,
                    )
                }
            },
            topBar = {
                TopBarComponent(selectedTab)
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            ) {
                if (useNavigationRail) {
                    NavigationRailComponent(
                        selectedTab = selectedTab,
                        onTabSelected = ::selectTab,
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    key = { page -> MainTab.fromIndex(page).route },
                    // There are only three primary pages; retain them to preserve scroll and
                    // paging composition state without recreating work on every tab switch.
                    beyondViewportPageCount = MainTab.ordered.lastIndex,
                    // Home already owns horizontal gestures for switching comic categories.
                    userScrollEnabled = pagerState.settledPage != MainTab.Home.index,
                ) { page ->
                    when (MainTab.fromIndex(page)) {
                        MainTab.Home -> HomeScreen()
                        MainTab.Collect -> if (isLogin) {
                            UserCollectComicScreen(useScaffold = false)
                        }
                        MainTab.Settings -> UserScreen()
                    }
                }
            }
        }
    }
}

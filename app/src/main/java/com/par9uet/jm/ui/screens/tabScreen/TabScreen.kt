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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.ui.navigation.MainTab
import com.par9uet.jm.ui.navigation.NavigationMotion
import com.par9uet.jm.ui.navigation.shouldIgnoreTabSelection
import com.par9uet.jm.ui.navigation.shouldTriggerFavoriteEntrySync
import com.par9uet.jm.ui.screens.HomeScreen
import com.par9uet.jm.ui.screens.LocalMainNavController
import com.par9uet.jm.ui.screens.UserCollectComicScreen
import com.par9uet.jm.ui.screens.UserScreen
import com.par9uet.jm.ui.viewModel.UserViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun TabScreen(
    tabName: String,
    userManager: UserManager = getKoin().get(),
    userViewModel: UserViewModel = koinActivityViewModel(),
) {
    val mainNavController = LocalMainNavController.current
    val isLogin by userManager.isLoginState.collectAsState(false)
    val initialTab = MainTab.fromRoute(tabName) ?: MainTab.Home
    val pagerState = rememberPagerState(
        initialPage = initialTab.index,
        pageCount = { MainTab.ordered.size },
    )
    val coroutineScope = rememberCoroutineScope()

    // Only one programmatic pager animation may run at a time; the last click wins.
    var tabNavigationJob by remember { mutableStateOf<Job?>(null) }
    var tabNavigationGeneration by remember { mutableIntStateOf(0) }
    // While a programmatic animation is active, the bottom bar shows its target page.
    var pendingTargetPage by remember { mutableIntStateOf(-1) }
    val selectedTab = MainTab.fromIndex(
        when {
            pendingTargetPage >= 0 -> pendingTargetPage
            pagerState.isScrollInProgress -> pagerState.currentPage
            else -> pagerState.settledPage
        }
    )

    fun selectTab(tab: MainTab) {
        val targetIndex = tab.index
        if (shouldIgnoreTabSelection(
                settledPage = pagerState.settledPage,
                currentPage = pagerState.currentPage,
                targetPage = pendingTargetPage,
                isScrollInProgress = pagerState.isScrollInProgress,
                requestedPage = targetIndex,
            )
        ) {
            return
        }
        tabNavigationJob?.cancel()
        pendingTargetPage = targetIndex
        val generation = ++tabNavigationGeneration
        tabNavigationJob = coroutineScope.launch {
            try {
                pagerState.animateScrollToPage(
                    page = targetIndex,
                    animationSpec = NavigationMotion.MainTabAnimationSpec,
                )
            } finally {
                // Only the latest click may clear the pending target; superseded jobs must
                // leave the newer target untouched.
                if (tabNavigationGeneration == generation) {
                    pendingTargetPage = -1
                }
            }
        }
    }

    LaunchedEffect(pagerState.settledPage, isLogin) {
        if (pagerState.settledPage == MainTab.Collect.index && !isLogin) {
            mainNavController.navigate("login")
        }
    }

    // Favorites entry is an actual pager transition, not composition: the three primary
    // pages stay composed, so recomposition/prefetch must never count as entering.
    var previousSettledPage by rememberSaveable { mutableIntStateOf(initialTab.index) }
    LaunchedEffect(pagerState.settledPage, isLogin) {
        val currentlySettledPage = pagerState.settledPage
        if (isLogin &&
            shouldTriggerFavoriteEntrySync(
                previousSettledPage = previousSettledPage,
                currentSettledPage = currentlySettledPage,
                favoritesPage = MainTab.Collect.index,
            )
        ) {
            userViewModel.requestFavoriteAutoSync()
        }
        previousSettledPage = currentlySettledPage
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

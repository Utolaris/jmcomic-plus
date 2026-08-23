package com.par9uet.jm.ui.screens.tabScreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.glass.GlassStyle
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.ui.navigation.MainTab
import com.par9uet.jm.ui.navigation.NavigationMotion
import com.par9uet.jm.ui.navigation.shouldIgnoreTabSelection
import com.par9uet.jm.ui.navigation.shouldRequestInitialFavoriteSync
import com.par9uet.jm.ui.navigation.shouldTriggerFavoriteSyncAfterLogin
import com.par9uet.jm.ui.navigation.shouldTriggerFavoriteEntrySync
import com.par9uet.jm.ui.screens.HomeScreen
import com.par9uet.jm.ui.screens.HomeGlassTopBar
import com.par9uet.jm.ui.screens.HomeGlassTopBarDefaults
import com.par9uet.jm.ui.screens.LocalMainNavController
import com.par9uet.jm.ui.screens.resolveHomeCategoryTitle
import com.par9uet.jm.ui.screens.UserCollectComicScreen
import com.par9uet.jm.ui.screens.UserScreen
import com.par9uet.jm.ui.viewModel.ComicViewModel
import com.par9uet.jm.ui.viewModel.UserViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun TabScreen(
    tabName: String,
    userManager: UserManager = getKoin().get(),
    comicViewModel: ComicViewModel = koinActivityViewModel(),
    userViewModel: UserViewModel = koinActivityViewModel(),
) {
    val mainNavController = LocalMainNavController.current
    val isLogin by userManager.isLoginState.collectAsState(false)
    val homeState by comicViewModel.homeState.collectAsState()
    val homeTitle = resolveHomeCategoryTitle(homeState.categories, homeState.selectedCategoryId)
    val onHomeSearch = { mainNavController.navigate("comicSearch") }
    val onHomeDownload = { mainNavController.navigate("download") }
    val onHomeWeekly = { mainNavController.navigate("comicRecommend") }
    val onHomeExtract = { mainNavController.navigate("extractCode") }
    val onHomeSign = {
        if (isLogin) {
            mainNavController.navigate("sign")
        } else {
            mainNavController.navigate("login")
        }
    }
    val initialTab = MainTab.fromRoute(tabName) ?: MainTab.Home
    val pagerState = rememberPagerState(
        initialPage = initialTab.index,
        pageCount = { MainTab.ordered.size },
    )
    val coroutineScope = rememberCoroutineScope()
    val favoritesController = rememberFavoritesUiController()
    val keyboardController = LocalSoftwareKeyboardController.current

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
                pendingTargetPage = pendingTargetPage.takeIf { it >= 0 },
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

    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage != MainTab.Collect.index && favoritesController.searchActive) {
            favoritesController.exitSearch()
            userViewModel.updateCollectSearchText("")
            keyboardController?.hide()
        }
    }

    // Logging in while already settled on Favorites must also trigger one eligible auto sync.
    var previousIsLogin by rememberSaveable { mutableStateOf(isLogin) }
    var initialCollectSyncRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isLogin) {
        if (shouldTriggerFavoriteSyncAfterLogin(
                previousIsLogin = previousIsLogin,
                isLogin = isLogin,
                settledPage = pagerState.settledPage,
                favoritesPage = MainTab.Collect.index,
            )
        ) {
            initialCollectSyncRequested = true
            userViewModel.requestFavoriteAutoSync()
        }
        previousIsLogin = isLogin
    }

    // Booting directly onto the Favorites route while authenticated gets one initial sync.
    LaunchedEffect(Unit) {
        if (shouldRequestInitialFavoriteSync(
                initialPage = initialTab.index,
                favoritesPage = MainTab.Collect.index,
                isLogin = isLogin,
                alreadyRequested = initialCollectSyncRequested,
            )
        ) {
            initialCollectSyncRequested = true
            userViewModel.requestFavoriteAutoSync()
        }
    }

    BoxWithConstraints {
        val useNavigationRail = maxWidth >= 700.dp
        val glassStyle = GlassStyle.Default
        val navigationBarInset = with(LocalDensity.current) {
            WindowInsets.navigationBars.getBottom(this).toDp()
        }
        val statusBarInset = with(LocalDensity.current) {
            WindowInsets.statusBars.getTop(this).toDp()
        }
        val homeTopContentPadding = if (useNavigationRail) {
            0.dp
        } else {
            statusBarInset + HomeGlassTopBarDefaults.toolbarHeight + 12.dp
        }
        val favoritesTopContentPadding = if (useNavigationRail) {
            0.dp
        } else {
            statusBarInset + FavoritesToolbarDefaults.toolbarHeight +
                FavoritesToolbarDefaults.outerMargin * 2
        }
        val settingsTopContentPadding = if (useNavigationRail) {
            0.dp
        } else {
            statusBarInset + 64.dp
        }
        val contentBottomPadding =
            if (useNavigationRail) {
                0.dp
            } else {
                glassStyle.barHeight + glassStyle.outerMargin + navigationBarInset
            }

        val pagerContent: @Composable (Modifier) -> Unit = { pagerModifier ->
            HorizontalPager(
                state = pagerState,
                modifier = pagerModifier,
                key = { page -> MainTab.fromIndex(page).route },
                // There are only three primary pages; retain them to preserve scroll and
                // paging composition state without recreating work on every tab switch.
                beyondViewportPageCount = MainTab.ordered.lastIndex,
                userScrollEnabled = true,
            ) { page ->
                when (MainTab.fromIndex(page)) {
                    MainTab.Home -> HomeScreen(
                        topContentPadding = homeTopContentPadding,
                        bottomContentPadding = contentBottomPadding,
                        onPullDownSearch = onHomeSearch,
                    )
                    MainTab.Collect -> if (isLogin) {
                        UserCollectComicScreen(
                            useScaffold = false,
                            uiController = favoritesController,
                            topContentPadding = favoritesTopContentPadding,
                            bottomContentPadding = contentBottomPadding,
                        )
                    }
                    MainTab.Settings -> UserScreen(
                        topContentPadding = settingsTopContentPadding,
                        bottomContentPadding = contentBottomPadding,
                    )
                }
            }
        }

        Scaffold(
            contentWindowInsets = if (useNavigationRail) {
                ScaffoldDefaults.contentWindowInsets
            } else {
                WindowInsets()
            },
            topBar = {
                if (useNavigationRail) {
                    TopBarComponent(
                        tab = selectedTab,
                        homeTitle = homeTitle,
                        homeCategories = homeState.categories,
                        selectedHomeCategoryId = homeState.selectedCategoryId,
                        onHomeCategorySelected = comicViewModel::selectHomeCategory,
                        favoritesController = favoritesController,
                        onHomeSearch = onHomeSearch,
                        onHomeDownload = onHomeDownload,
                        onHomeWeekly = onHomeWeekly,
                        onHomeExtract = onHomeExtract,
                        onHomeSign = onHomeSign,
                    )
                }
            },
        ) { innerPadding ->
            if (useNavigationRail) {
                Row(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                ) {
                    NavigationRailComponent(
                        selectedTab = selectedTab,
                        onTabSelected = ::selectTab,
                    )
                    pagerContent(Modifier.weight(1f))
                }
            } else {
                Box(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                ) {
                    GlassCaptureHost(
                        modifier = Modifier.fillMaxSize(),
                        sourceContent = {
                            pagerContent(Modifier.fillMaxSize())
                        },
                        overlayContent = {
                            Box(Modifier.fillMaxSize()) {
                                if (selectedTab == MainTab.Home) {
                                    HomeGlassTopBar(
                                        title = homeTitle,
                                        categories = homeState.categories,
                                        selectedCategoryId = homeState.selectedCategoryId,
                                        statusBarInset = statusBarInset,
                                        modifier = Modifier.align(Alignment.TopCenter),
                                        onSearch = onHomeSearch,
                                        onDownload = onHomeDownload,
                                        onWeekly = onHomeWeekly,
                                        onExtract = onHomeExtract,
                                        onSign = onHomeSign,
                                        onCategorySelected = comicViewModel::selectHomeCategory,
                                    )
                                } else if (selectedTab == MainTab.Collect && isLogin) {
                                    FavoritesVariableGlassTopBar(
                                        statusBarInset = statusBarInset,
                                        controller = favoritesController,
                                        userViewModel = userViewModel,
                                        modifier = Modifier.align(Alignment.TopCenter),
                                    )
                                } else if (selectedTab == MainTab.Settings) {
                                    TopBarComponent(
                                        tab = MainTab.Settings,
                                    )
                                }
                                BottomNavigationBarComponent(
                                    selectedTab = selectedTab,
                                    onTabSelected = ::selectTab,
                                    modifier = Modifier.fillMaxSize(),
                                    navigationBarInset = navigationBarInset,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

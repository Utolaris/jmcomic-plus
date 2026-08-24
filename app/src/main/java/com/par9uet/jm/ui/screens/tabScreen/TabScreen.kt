package com.par9uet.jm.ui.screens.tabScreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.par9uet.jm.ui.glass.AppGlassTopBar
import com.par9uet.jm.ui.glass.AppGlassTopBarDefaults
import com.par9uet.jm.ui.glass.GlassAnchoredMenu
import com.par9uet.jm.ui.glass.GlassMenuAlignment
import com.par9uet.jm.ui.glass.GlassMenuDivider
import com.par9uet.jm.ui.glass.GlassMenuItem
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.glass.GlassConfirmDialog
import com.par9uet.jm.ui.glass.GlassStyle
import com.par9uet.jm.ui.glass.rememberGlassAnchoredMenuState
import com.par9uet.jm.ui.interaction.PullDownSearchIndicator
import com.par9uet.jm.ui.interaction.rememberPullDownActionState
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.store.SessionReadiness
import com.par9uet.jm.ui.navigation.MainTab
import com.par9uet.jm.ui.navigation.NavigationMotion
import com.par9uet.jm.ui.navigation.shouldIgnoreTabSelection
import com.par9uet.jm.ui.navigation.shouldRequestInitialFavoriteSync
import com.par9uet.jm.ui.navigation.shouldTriggerFavoriteSyncAfterLogin
import com.par9uet.jm.ui.navigation.shouldTriggerFavoriteEntrySync
import com.par9uet.jm.ui.screens.HomeScreen
import com.par9uet.jm.ui.screens.HomeGlassTopBar
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
    val authState by userManager.authState.collectAsState()
    val isAuthenticated = authState == SessionReadiness.Authenticated
    val canShowAuthenticatedUi = authState != SessionReadiness.Unauthenticated
    val homeState by comicViewModel.homeState.collectAsState()
    val homeTitle = resolveHomeCategoryTitle(homeState.categories, homeState.selectedCategoryId)
    val onHomeSearch = { mainNavController.navigate("comicSearch") }
    val onHomeDownload = { mainNavController.navigate("download") }
    val onHomeWeekly = { mainNavController.navigate("comicRecommend") }
    val onHomeExtract = { mainNavController.navigate("extractCode") }
    val onHomeSign = {
        when (authState) {
            SessionReadiness.Authenticated -> mainNavController.navigate("sign")
            SessionReadiness.Unauthenticated -> mainNavController.navigate("login")
            SessionReadiness.Unknown,
            SessionReadiness.Restoring -> Unit
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
    val homePullDownState = rememberPullDownActionState()
    val favoritesPullDownState = rememberPullDownActionState()
    val homeCategoryMenuState = rememberGlassAnchoredMenuState()
    val homeMoreMenuState = rememberGlassAnchoredMenuState()
    val favoritesFolderMenuState = rememberGlassAnchoredMenuState()
    val selectedFavoriteFolderId by userViewModel.selectedFolderId.collectAsState()
    val favoriteFolderList by userViewModel.folderList.collectAsState()

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

    LaunchedEffect(selectedTab) {
        homeCategoryMenuState.dismiss()
        homeMoreMenuState.dismiss()
        favoritesFolderMenuState.dismiss()
    }

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

    LaunchedEffect(pagerState.settledPage, authState) {
        if (pagerState.settledPage == MainTab.Collect.index && authState == SessionReadiness.Unauthenticated) {
            mainNavController.navigate("login")
        }
    }

    // Favorites entry is an actual pager transition, not composition: the three primary
    // pages stay composed, so recomposition/prefetch must never count as entering.
    var previousSettledPage by rememberSaveable { mutableIntStateOf(initialTab.index) }
    LaunchedEffect(pagerState.settledPage, isAuthenticated) {
        val currentlySettledPage = pagerState.settledPage
        if (isAuthenticated &&
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

    // When leaving Favorites, clear every transient UI flag so no scrim / selection / sheet
    // can leak onto Home or Settings. Persistent state (folder, paging, filters) is untouched.
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage == MainTab.Collect.index) return@LaunchedEffect
        userViewModel.clearCollectSelection()
        favoritesController.clearAllTransientUi()
        favoritesFolderMenuState.dismiss()
        userViewModel.updateCollectSearchText("")
        keyboardController?.hide()
    }

    // Logging in while already settled on Favorites must also trigger one eligible auto sync.
    var previousIsLogin by rememberSaveable { mutableStateOf(isAuthenticated) }
    var initialCollectSyncRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isAuthenticated) {
        if (shouldTriggerFavoriteSyncAfterLogin(
                previousIsLogin = previousIsLogin,
                isLogin = isAuthenticated,
                settledPage = pagerState.settledPage,
                favoritesPage = MainTab.Collect.index,
            )
        ) {
            initialCollectSyncRequested = true
            userViewModel.requestFavoriteAutoSync()
        }
        previousIsLogin = isAuthenticated
    }

    // Booting directly onto the Favorites route while authenticated gets one initial sync.
    LaunchedEffect(Unit) {
        if (shouldRequestInitialFavoriteSync(
                initialPage = initialTab.index,
                favoritesPage = MainTab.Collect.index,
                isLogin = isAuthenticated,
                alreadyRequested = initialCollectSyncRequested,
            )
        ) {
            initialCollectSyncRequested = true
            userViewModel.requestFavoriteAutoSync()
        }
    }

    BoxWithConstraints {
        val useNavigationRail = maxWidth >= 700.dp
        val anchoredMenuMaxHeight = maxHeight * 0.56f
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
            statusBarInset + AppGlassTopBarDefaults.ContentHeight + 12.dp
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
            statusBarInset + AppGlassTopBarDefaults.ContentHeight
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
                        pullDownState = homePullDownState,
                        onPullDownSearch = onHomeSearch,
                    )
                    MainTab.Collect -> if (canShowAuthenticatedUi) {
                        UserCollectComicScreen(
                            useScaffold = false,
                            uiController = favoritesController,
                            pullDownState = favoritesPullDownState,
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
                                        statusBarInset = statusBarInset,
                                        categoryMenuState = homeCategoryMenuState,
                                        moreMenuState = homeMoreMenuState,
                                        modifier = Modifier.align(Alignment.TopCenter),
                                        onSearch = onHomeSearch,
                                        onDownload = onHomeDownload,
                                    )
                                    PullDownSearchIndicator(
                                        state = homePullDownState,
                                        surfaceId = "home-pull-search-indicator",
                                        topOffset = statusBarInset + AppGlassTopBarDefaults.ContentHeight,
                                        modifier = Modifier.align(Alignment.TopCenter),
                                    )
                                } else if (selectedTab == MainTab.Collect && canShowAuthenticatedUi) {
                                    FavoritesVariableGlassTopBar(
                                        statusBarInset = statusBarInset,
                                        controller = favoritesController,
                                        folderMenuState = favoritesFolderMenuState,
                                        userViewModel = userViewModel,
                                        modifier = Modifier.align(Alignment.TopCenter),
                                    )
                                    PullDownSearchIndicator(
                                        state = favoritesPullDownState,
                                        surfaceId = "favorites-pull-search-indicator",
                                        topOffset = statusBarInset + FavoritesToolbarDefaults.toolbarHeight,
                                        modifier = Modifier.align(Alignment.TopCenter),
                                    )
                                } else if (selectedTab == MainTab.Settings) {
                                    AppGlassTopBar(
                                        surfaceId = "primary-settings-top-bar",
                                        statusBarInset = statusBarInset,
                                        title = {
                                            Text(
                                                modifier = Modifier.padding(start = 4.dp),
                                                text = "设置",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        },
                                    )
                                }
                                BottomNavigationBarComponent(
                                    selectedTab = selectedTab,
                                    onTabSelected = ::selectTab,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.BottomCenter),
                                    navigationBarInset = navigationBarInset,
                                )
                                if (selectedTab == MainTab.Home) {
                                    GlassAnchoredMenu(
                                        state = homeCategoryMenuState,
                                        surfaceId = "home-category-glass-menu",
                                        alignment = GlassMenuAlignment.START,
                                        width = 260.dp,
                                        menuMaxHeight = anchoredMenuMaxHeight,
                                    ) {
                                        homeState.categories.forEach { category ->
                                            GlassMenuItem(
                                                text = category.title,
                                                selected = category.id == homeState.selectedCategoryId,
                                                onClick = {
                                                    homeCategoryMenuState.dismiss()
                                                    comicViewModel.selectHomeCategory(category.id)
                                                },
                                            )
                                        }
                                    }
                                    GlassAnchoredMenu(
                                        state = homeMoreMenuState,
                                        surfaceId = "home-more-glass-menu",
                                        alignment = GlassMenuAlignment.END,
                                        width = 196.dp,
                                        menuMaxHeight = 210.dp,
                                    ) {
                                        GlassMenuItem(
                                            text = "每周",
                                            leadingIcon = Icons.Default.Star,
                                            onClick = {
                                                homeMoreMenuState.dismiss()
                                                onHomeWeekly()
                                            },
                                        )
                                        GlassMenuItem(
                                            text = "提取",
                                            leadingIcon = Icons.Default.Password,
                                            onClick = {
                                                homeMoreMenuState.dismiss()
                                                onHomeExtract()
                                            },
                                        )
                                        GlassMenuItem(
                                            text = "签到",
                                            leadingIcon = Icons.Default.CalendarMonth,
                                            onClick = {
                                                homeMoreMenuState.dismiss()
                                                onHomeSign()
                                            },
                                        )
                                    }
                                } else if (selectedTab == MainTab.Collect && canShowAuthenticatedUi) {
                                    GlassAnchoredMenu(
                                        state = favoritesFolderMenuState,
                                        surfaceId = "favorites-folder-glass-menu",
                                        alignment = GlassMenuAlignment.CENTER,
                                        width = 260.dp,
                                        menuMaxHeight = anchoredMenuMaxHeight,
                                    ) {
                                        GlassMenuItem(
                                            text = "全部收藏",
                                            leadingIcon = Icons.Rounded.Bookmarks,
                                            selected = selectedFavoriteFolderId == 0,
                                            onClick = {
                                                favoritesFolderMenuState.dismiss()
                                                userViewModel.clearCollectSelection()
                                                favoritesController.exitSearch()
                                                userViewModel.updateCollectSearchText("")
                                                userViewModel.changeFolder(0)
                                            },
                                        )
                                        favoriteFolderList.entries
                                            .filter { it.key != "0" }
                                            .forEach { (folderId, folderName) ->
                                                val numericFolderId = folderId.toIntOrNull()
                                                    ?: return@forEach
                                                GlassMenuItem(
                                                    text = folderName,
                                                    leadingIcon = Icons.Rounded.Folder,
                                                    selected = selectedFavoriteFolderId == numericFolderId,
                                                    onClick = {
                                                        favoritesFolderMenuState.dismiss()
                                                        userViewModel.clearCollectSelection()
                                                        favoritesController.exitSearch()
                                                        userViewModel.updateCollectSearchText("")
                                                        userViewModel.changeFolder(numericFolderId)
                                                    },
                                                )
                                            }
                                        GlassMenuDivider()
                                        GlassMenuItem(
                                            text = "管理收藏夹",
                                            leadingIcon = Icons.Rounded.Folder,
                                            onClick = {
                                                favoritesFolderMenuState.dismiss()
                                                favoritesController.showFolderManagement()
                                            },
                                        )
                                    }
                                }
                            }
                            GlassConfirmDialog(
                                visible = selectedTab == MainTab.Collect &&
                                    canShowAuthenticatedUi &&
                                    favoritesController.deleteDialogVisible,
                                title = "取消收藏",
                                message = "确定取消收藏 ${favoritesController.selectedComics().size} 部漫画吗？",
                                confirmText = "取消收藏",
                                destructive = true,
                                surfaceId = "favorites-uncollect-glass-confirm",
                                onConfirm = {
                                    userViewModel.deleteCollectedComics(
                                        favoritesController.selectedComics(),
                                    )
                                    favoritesController.dismissDeleteDialog()
                                },
                                onDismiss = favoritesController::dismissDeleteDialog,
                            )
                        },
                    )
                }
            }
        }
    }
}

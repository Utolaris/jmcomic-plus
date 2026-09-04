package com.par9uet.jm.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.par9uet.jm.ui.components.Comic
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.components.adaptiveComicGridCells
import com.par9uet.jm.ui.interaction.pullDownToAction
import com.par9uet.jm.ui.interaction.PullDownActionState
import com.par9uet.jm.ui.interaction.rememberPullDownActionState
import com.par9uet.jm.ui.screens.tabScreen.FavoritesMaterialTopBar
import com.par9uet.jm.favorites.model.FavoritesIntent
import com.par9uet.jm.favorites.presentation.FavoritesViewModel
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.utils.log
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
internal fun UserCollectComicScreen(
    favoritesViewModel: FavoritesViewModel = koinActivityViewModel(),
    useScaffold: Boolean = true,
    localSettingManager: LocalSettingManager = getKoin().get(),
    pullDownState: PullDownActionState = rememberPullDownActionState(),
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
) {
    val navController = LocalMainNavController.current
    val favoritesState by favoritesViewModel.uiState.collectAsState()
    val collectComicLazyPagingItems = favoritesViewModel.collectComicPager.collectAsLazyPagingItems()
    val selectedFolderId = favoritesState.selectedFolderId
    val collectEditState = favoritesState.selection
    val miscSettings by localSettingManager.misc.collectAsState()
    var hasLoggedFirstLocalContent by remember { mutableStateOf(false) }
    val favoritesOpenedAt = remember { SystemClock.elapsedRealtime() }

    LaunchedEffect(collectComicLazyPagingItems.itemCount, selectedFolderId) {
        if (!hasLoggedFirstLocalContent && collectComicLazyPagingItems.itemCount > 0) {
            log(
                "FavoritesUI",
                "first local content visible folder=$selectedFolderId " +
                    "count=${collectComicLazyPagingItems.itemCount} " +
                    "duration=${SystemClock.elapsedRealtime() - favoritesOpenedAt}ms",
            )
            hasLoggedFirstLocalContent = true
        }
    }

    // Restore the saved viewport from the FIRST frame; no visible jump back to top.
    val savedViewport = favoritesState.viewport
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = savedViewport.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = savedViewport.firstVisibleItemScrollOffset,
    )
    val favoriteItemCount = collectComicLazyPagingItems.itemCount
    val favoriteAppendComplete = collectComicLazyPagingItems.loadState.append.let {
        it is LoadState.NotLoading && it.endOfPaginationReached
    }
    val initialResetGeneration = remember { savedViewport.resetGeneration }
    var initialViewportRestorePending by remember { mutableStateOf(true) }
    var suppressViewportPersistence by remember { mutableStateOf(false) }

    // An empty first Paging snapshot temporarily clamps LazyGridState to (0, 0). Do not treat
    // that as the restored position; wait until the saved item is loaded or Paging confirms the
    // list has ended, then apply the target once and release persistence.
    LaunchedEffect(
        savedViewport.resetGeneration,
        favoriteItemCount,
        favoriteAppendComplete,
    ) {
        if (!initialViewportRestorePending || favoriteItemCount <= 0) return@LaunchedEffect
        val savedIndex = savedViewport.firstVisibleItemIndex
        if (!favoriteAppendComplete && favoriteItemCount <= savedIndex) return@LaunchedEffect

        val targetIndex = savedIndex.coerceAtMost(favoriteItemCount - 1)
        if (gridState.firstVisibleItemIndex != targetIndex ||
            gridState.firstVisibleItemScrollOffset != savedViewport.firstVisibleItemScrollOffset
        ) {
            gridState.scrollToItem(targetIndex, savedViewport.firstVisibleItemScrollOffset)
        }
        androidx.compose.runtime.withFrameNanos { }
        initialViewportRestorePending = false
    }

    // A reset changes the existing state as well as the saved value. The generation makes this
    // effect distinct from the initial restore, and the short suppression window prevents the
    // old grid position from racing back into the ViewModel while scrollToItem is settling.
    LaunchedEffect(savedViewport.resetGeneration) {
        if (savedViewport.resetGeneration == initialResetGeneration) return@LaunchedEffect
        suppressViewportPersistence = true
        try {
            gridState.scrollToItem(0, 0)
            androidx.compose.runtime.withFrameNanos { }
        } finally {
            suppressViewportPersistence = false
        }
    }

    // Persist the viewport on every settled scroll change; distinctUntilChanged keeps this cheap.
    // Re-keying on the generation also cancels a collector that still carries the old token.
    LaunchedEffect(gridState, savedViewport.resetGeneration, initialViewportRestorePending) {
        val resetGeneration = savedViewport.resetGeneration
        snapshotFlow {
            Triple(
                collectComicLazyPagingItems.itemCount,
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
            )
        }.distinctUntilChanged().collect { (itemCount, index, offset) ->
            if (itemCount > 0 && !initialViewportRestorePending && !suppressViewportPersistence) {
                favoritesViewModel.onIntent(
                    FavoritesIntent.ViewportSaved(index, offset, resetGeneration)
                )
            }
        }
    }
    val pullRevealPadding = 36.dp * pullDownState.progress
    val gridModifier = Modifier
        .fillMaxSize()
        .pullDownToAction(
            state = pullDownState,
            enabled = !collectEditState.editing && !favoritesState.searchActive,
            isAtTop = { !gridState.canScrollBackward },
            onTrigger = {
                favoritesViewModel.onIntent(FavoritesIntent.SearchEntered)
            },
        )

    val mainContent: @Composable () -> Unit = {
        PullRefreshAndLoadMoreGrid(
            modifier = gridModifier,
            lazyPagingItems = collectComicLazyPagingItems,
            key = { it.id },
            columns = adaptiveComicGridCells(miscSettings.gridColumns.collect),
            gridState = gridState,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Top),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(
                start = 14.dp,
                top = topContentPadding + pullRevealPadding + 14.dp,
                end = 14.dp,
                bottom = 14.dp + bottomContentPadding,
            ),
            enablePullRefresh = false,
        ) { comic ->
            Comic(
                comic = comic,
                editing = collectEditState.editing,
                selected = comic.id in collectEditState.selectedComicIds,
                onLongClick = {
                    favoritesViewModel.onIntent(FavoritesIntent.ComicLongPressed(comic.id))
                },
                onToggleSelected = {
                    favoritesViewModel.onIntent(FavoritesIntent.ComicSelectionToggled(comic.id))
                },
            )
        }
    }

    if (useScaffold) {
        Scaffold(
            topBar = {
                FavoritesMaterialTopBar(
                    favoritesViewModel = favoritesViewModel,
                    onNavigateBack = navController::popBackStack,
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                mainContent()
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) { mainContent() }
    }

}

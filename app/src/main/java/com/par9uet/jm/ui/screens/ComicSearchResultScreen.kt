package com.par9uet.jm.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.ui.components.Comic
import com.par9uet.jm.ui.components.ComicSkeleton
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.components.adaptiveComicGridCells
import com.par9uet.jm.ui.glass.GlassAnchoredMenu
import com.par9uet.jm.ui.glass.GlassMenuAlignment
import com.par9uet.jm.ui.glass.GlassMenuItem
import com.par9uet.jm.ui.glass.glassMenuAnchor
import com.par9uet.jm.ui.glass.rememberGlassAnchoredMenuState
import com.par9uet.jm.ui.viewModel.ComicDetailViewModel
import com.par9uet.jm.ui.viewModel.ComicViewModel
import com.par9uet.jm.contentfilter.serializeExcludedTags
import com.par9uet.jm.store.LocalSettingManager
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

internal enum class SearchResultBackTarget {
    PREVIOUS_SCREEN,
    SEARCH_EDITOR,
}

internal fun searchResultBackTarget(previousRoute: String?): SearchResultBackTarget =
    if (previousRoute.isNullOrBlank()) {
        SearchResultBackTarget.SEARCH_EDITOR
    } else {
        SearchResultBackTarget.PREVIOUS_SCREEN
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComicSearchResultSkeleton(
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(10.dp),
        maxItemsInEachRow = 3,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top)
    ) {
        for (i in 0 until 18) {
            ComicSkeleton(modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicSearchResultScreen(
    comicViewModel: ComicViewModel = koinActivityViewModel(),
    comicDetailViewModel: ComicDetailViewModel = koinActivityViewModel(),
    localSettingManager: LocalSettingManager = getKoin().get(),
) {
    val mainNavController = LocalMainNavController.current
    val miscSettings by localSettingManager.misc.collectAsState()
    val comicSearchLazyPagingItems = comicViewModel.searchComicPager.collectAsLazyPagingItems()
    val comicSearchFilterState by comicViewModel.searchComicFilterState.collectAsState()
    val searchComicIdState by comicViewModel.searchComicIdState.collectAsState()
    val savedViewport by comicViewModel.searchViewportState.collectAsState()
    val sortMenuState = rememberGlassAnchoredMenuState()
    val sortMenuMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.56f
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = savedViewport.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = savedViewport.firstVisibleItemScrollOffset,
    )
    val searchItemCount = comicSearchLazyPagingItems.itemCount
    val searchAppendComplete = comicSearchLazyPagingItems.loadState.append.let {
        it is LoadState.NotLoading && it.endOfPaginationReached
    }
    val initialResetGeneration = remember { savedViewport.resetGeneration }
    var initialViewportRestorePending by remember { mutableStateOf(true) }
    var suppressViewportPersistence by remember { mutableStateOf(false) }

    LaunchedEffect(savedViewport.resetGeneration, searchItemCount, searchAppendComplete) {
        if (!initialViewportRestorePending || searchItemCount <= 0) return@LaunchedEffect
        val savedIndex = savedViewport.firstVisibleItemIndex
        if (!searchAppendComplete && searchItemCount <= savedIndex) return@LaunchedEffect

        val targetIndex = savedIndex.coerceAtMost(searchItemCount - 1)
        if (gridState.firstVisibleItemIndex != targetIndex ||
            gridState.firstVisibleItemScrollOffset != savedViewport.firstVisibleItemScrollOffset
        ) {
            gridState.scrollToItem(targetIndex, savedViewport.firstVisibleItemScrollOffset)
        }
        androidx.compose.runtime.withFrameNanos { }
        initialViewportRestorePending = false
    }

    LaunchedEffect(savedViewport.resetGeneration) {
        if (savedViewport.resetGeneration == initialResetGeneration) return@LaunchedEffect
        suppressViewportPersistence = true
        try {
            gridState.scrollToItem(0, 0)
            androidx.compose.runtime.withFrameNanos { }
            initialViewportRestorePending = false
        } finally {
            suppressViewportPersistence = false
        }
    }

    LaunchedEffect(gridState, savedViewport.resetGeneration, initialViewportRestorePending) {
        val resetGeneration = savedViewport.resetGeneration
        snapshotFlow {
            Triple(
                comicSearchLazyPagingItems.itemCount,
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
            )
        }.distinctUntilChanged().collect { (itemCount, index, offset) ->
            if (itemCount > 0 && !initialViewportRestorePending && !suppressViewportPersistence) {
                comicViewModel.saveSearchViewport(index, offset, resetGeneration)
            }
        }
    }

    fun editRoute(): String {
        val encodedSearchContent = Uri.encode(comicSearchFilterState.searchContent)
        val encodedExcludedTags = Uri.encode(serializeExcludedTags(comicSearchFilterState.excludedTags))
        return "comicSearch?searchContent=$encodedSearchContent&excludedTags=$encodedExcludedTags"
    }

    fun navigateToSearchEditor() {
        val previousRoute = mainNavController.previousBackStackEntry?.destination?.route.orEmpty()
        val previousIsSearchEditor = previousRoute == "comicSearch" || previousRoute.startsWith("comicSearch?")
        if (previousIsSearchEditor && mainNavController.popBackStack()) return

        mainNavController.popBackStack()
        mainNavController.navigate(editRoute()) {
            launchSingleTop = true
        }
    }

    fun navigateBackToOrigin() {
        val previousRoute = mainNavController.previousBackStackEntry?.destination?.route
        if (
            searchResultBackTarget(previousRoute) == SearchResultBackTarget.PREVIOUS_SCREEN &&
            mainNavController.popBackStack()
        ) {
            return
        }
        navigateToSearchEditor()
    }

    BackHandler {
        navigateBackToOrigin()
    }

    LaunchedEffect(searchComicIdState) {
        val comicId = searchComicIdState ?: return@LaunchedEffect
        comicDetailViewModel.reset(comicId)
        comicViewModel.consumeSearchComicId()
        mainNavController.navigate("comicDetail/$comicId") {
            launchSingleTop = true
        }
    }

    val isLoading = comicSearchLazyPagingItems.loadState.refresh is LoadState.Loading
    val hasError = comicSearchLazyPagingItems.loadState.refresh is LoadState.Error

    CommonScaffold(
        title = comicSearchFilterState.searchContent.ifBlank { "搜索" },
        onNavigateBack = { navigateBackToOrigin() },
        titleContent = {
            val title = comicSearchFilterState.searchContent.ifBlank { "搜索" }
            Text(
                text = title,
                modifier = Modifier.clickable { navigateToSearchEditor() },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        actions = {
            IconButton(
                onClick = { sortMenuState.open() },
                modifier = Modifier.glassMenuAnchor(sortMenuState),
            ) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "排序")
            }
        },
        overlayContent = {
            GlassAnchoredMenu(
                state = sortMenuState,
                surfaceId = "search-sort-glass-menu",
                alignment = GlassMenuAlignment.END,
                width = 190.dp,
                menuMaxHeight = sortMenuMaxHeight,
            ) {
                ComicSearchOrderFilter.entries.forEach { order ->
                    GlassMenuItem(
                        text = order.label,
                        selected = order.value == comicSearchFilterState.order.value,
                        onClick = {
                            sortMenuState.dismiss()
                            comicViewModel.changeSearchComicOrderFilter(order)
                        },
                    )
                }
            }
        },
    ) { topContentPadding, bottomContentPadding ->
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
            }
            if (isLoading && comicSearchLazyPagingItems.itemCount == 0) {
                ComicSearchResultSkeleton(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = topContentPadding + 8.dp),
                )
                return@Column
            }
            if (hasError && comicSearchLazyPagingItems.itemCount == 0) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = topContentPadding, bottom = bottomContentPadding)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (comicSearchLazyPagingItems.loadState.refresh as? LoadState.Error)?.error?.message
                            ?: "加载失败，请重试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }
            PullRefreshAndLoadMoreGrid(
                modifier = Modifier.weight(1f),
                lazyPagingItems = comicSearchLazyPagingItems,
                key = { it.id },
                columns = adaptiveComicGridCells(miscSettings.gridColumns.search),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = topContentPadding + 10.dp,
                    bottom = bottomContentPadding + 10.dp,
                ),
                gridState = gridState,
            ) {
                Comic(it)
            }
        }
    }
}

package com.par9uet.jm.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.ui.components.Comic
import com.par9uet.jm.ui.components.ComicSkeleton
import com.par9uet.jm.ui.components.adaptiveComicGridCells
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle
import com.par9uet.jm.ui.viewModel.ComicViewModel
import com.par9uet.jm.utils.filterBlockedTags
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel
import kotlin.math.abs

private const val TEXT_SEARCH = "\u641c\u7d22"
private const val TEXT_WEEKLY = "\u6bcf\u5468"
private const val TEXT_DOWNLOAD = "\u4e0b\u8f7d"
private const val TEXT_SIGN = "\u7b7e\u5230"
private const val TEXT_EXTRACT = "\u63d0\u53d6"
private const val CATEGORY_LOADING_SKELETON_COUNT = 18

internal object HomeGlassTopBarDefaults {
    val toolbarHeight = 60.dp
}

internal fun resolveHomeCategoryTitle(
    categories: List<ComicViewModel.HomeCategoryInfo>,
    selectedCategoryId: String?,
): String {
    return categories.firstOrNull { it.id == selectedCategoryId }?.title ?: "首页"
}

@Composable
private fun HomeSkeleton(
    gridColumns: Int,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
) {
    LazyVerticalGrid(
        modifier = Modifier
            .fillMaxSize(),
        columns = adaptiveComicGridCells(gridColumns),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = topContentPadding,
            bottom = 16.dp + bottomContentPadding,
        ),
    ) {
        items(CATEGORY_LOADING_SKELETON_COUNT) {
            ComicSkeleton()
        }
    }
}

@Composable
fun HomeScreen(
    comicViewModel: ComicViewModel = koinActivityViewModel(),
    localSettingManager: LocalSettingManager = getKoin().get(),
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
) {
    val homeState by comicViewModel.homeState.collectAsState()
    val localSetting by localSettingManager.localSettingState.collectAsState()

    LaunchedEffect(localSetting.comicApiSource, localSetting.preferenceRecommendEnabled) {
        comicViewModel.refreshHome()
    }

    val selectedCategoryId = homeState.selectedCategoryId
    val selectedState = selectedCategoryId?.let { homeState.states[it] }
    // Full-page skeleton is only for bootstrap, before the real category structure exists.
    val showSkeleton = homeState.categories.isEmpty() || selectedCategoryId == null
    if (showSkeleton) {
        HomeSkeleton(
            gridColumns = localSetting.homeGridColumns,
            topContentPadding = topContentPadding,
            bottomContentPadding = bottomContentPadding,
        )
        return
    }

    val selectedIndex = homeState.categories
        .indexOfFirst { it.id == selectedCategoryId }
        .coerceAtLeast(0)
    val onTabClick: (index: Int) -> Unit = {
        homeState.categories.getOrNull(it)?.let { category ->
            comicViewModel.selectHomeCategory(category.id)
        }
    }

    val currentContent = selectedState?.content.orEmpty()
    val allExcludedTags = remember(localSetting.blockedTagList, localSetting.homeExcludedTags) {
        (localSetting.blockedTagList + localSetting.homeExcludedTags).distinct()
    }
    val comicList = remember(currentContent, allExcludedTags) {
        currentContent.map { it.toComic() }.filterBlockedTags(allExcludedTags)
    }
    PullToRefreshBox(
        modifier = Modifier.fillMaxSize(),
        isRefreshing = selectedState?.isLoading == true && currentContent.isNotEmpty(),
        onRefresh = { comicViewModel.refreshSelectedHomeCategory() }
    ) {
        LazyVerticalGrid(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(selectedIndex, homeState.categories.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalX = 0f
                        var totalY = 0f
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) break
                            totalX += change.position.x - change.previousPosition.x
                            totalY += change.position.y - change.previousPosition.y
                        } while (event.changes.any { it.pressed })

                        if (
                            abs(totalX) > 72.dp.toPx() &&
                            abs(totalX) > abs(totalY) * 1.2f
                        ) {
                            if (totalX < 0) {
                                onTabClick(selectedIndex + 1)
                            } else {
                                onTabClick(selectedIndex - 1)
                            }
                        }
                    }
                },
            columns = adaptiveComicGridCells(localSetting.homeGridColumns),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = topContentPadding,
                bottom = 16.dp + bottomContentPadding,
            )
        ) {
            if (selectedState?.isLoading == true && currentContent.isEmpty()) {
                items(CATEGORY_LOADING_SKELETON_COUNT) {
                    ComicSkeleton()
                }
            } else if (selectedState != null && selectedState.isError && comicList.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            text = selectedState.errorMsg ?: "加载失败",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { comicViewModel.refreshSelectedHomeCategory() }) {
                            Text("重试")
                        }
                    }
                }
            }
            items(items = comicList, key = { it.id }) {
                Comic(it)
            }
            if (comicList.isEmpty() && !(selectedState?.isLoading == true) &&
                !(selectedState?.isError == true)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            text = if (allExcludedTags.isNotEmpty()) "当前分类的漫画均被标签排除过滤" else "暂无漫画",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (allExcludedTags.isNotEmpty()) {
                            Text(
                                text = "可在 设置 → 标签排除 中调整",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeTopBarActions(
    onSearch: () -> Unit,
    onDownload: () -> Unit,
    onWeekly: () -> Unit,
    onExtract: () -> Unit,
    onSign: () -> Unit,
) {
    IconButton(onClick = onSearch) {
        Icon(Icons.Rounded.Search, contentDescription = TEXT_SEARCH)
    }
    IconButton(onClick = onDownload) {
        Icon(Icons.Rounded.Download, contentDescription = TEXT_DOWNLOAD)
    }
    Box {
        var menuExpanded by remember { mutableStateOf(false) }
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(TEXT_WEEKLY) },
                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onWeekly()
                },
            )
            DropdownMenuItem(
                text = { Text(TEXT_EXTRACT) },
                leadingIcon = { Icon(Icons.Default.Password, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onExtract()
                },
            )
            DropdownMenuItem(
                text = { Text(TEXT_SIGN) },
                leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onSign()
                },
            )
        }
    }
}

@Composable
internal fun HomeGlassTopBar(
    title: String,
    statusBarInset: Dp,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit,
    onDownload: () -> Unit,
    onWeekly: () -> Unit,
    onExtract: () -> Unit,
    onSign: () -> Unit,
) {
    GlassSurface(
        surfaceId = "primary-home-top-bar",
        modifier = modifier
            .fillMaxWidth()
            .height(statusBarInset + HomeGlassTopBarDefaults.toolbarHeight),
        style = GlassSurfaceStyle(cornerRadius = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarInset, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HomeTopBarActions(
                onSearch = onSearch,
                onDownload = onDownload,
                onWeekly = onWeekly,
                onExtract = onExtract,
                onSign = onSign,
            )
        }
    }
}

package com.par9uet.jm.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.par9uet.jm.ui.components.BackIconButton
import com.par9uet.jm.ui.components.Comic
import com.par9uet.jm.ui.components.ComicSkeleton
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.components.adaptiveComicGridCells
import com.par9uet.jm.ui.glass.AppGlassTopBar
import com.par9uet.jm.ui.glass.ChromeMode
import com.par9uet.jm.ui.glass.GlassConfirmDialog
import com.par9uet.jm.ui.glass.GlassTopBarModeTransition
import com.par9uet.jm.store.LocalSettingManager
import org.koin.compose.getKoin
import com.par9uet.jm.ui.viewModel.UserViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
private fun UserHistoryComicSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            maxItemsInEachRow = 3,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top)
        ) {
            for (i in 0 until 18) {
                key(i) {
                    ComicSkeleton(
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserHistoryComicScreen(
    userViewModel: UserViewModel = koinActivityViewModel(),
    localSettingManager: LocalSettingManager = getKoin().get(),
) {
    val historyComicLazyPagingItems = userViewModel.historyComicPager.collectAsLazyPagingItems()
    val historyEditState by userViewModel.historyEditState.collectAsState()
    val miscSettings by localSettingManager.misc.collectAsState()
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val selectedComics: List<com.par9uet.jm.data.models.Comic> = remember(historyComicLazyPagingItems.itemSnapshotList, historyEditState.selectedComicIds) {
        historyComicLazyPagingItems.itemSnapshotList.filterNotNull().filter { it.id in historyEditState.selectedComicIds }
    }
    BackHandler(enabled = historyEditState.editing) {
        userViewModel.clearHistorySelection()
    }

    // Exactly one refresh per genuine destination resume: presented items stay visible while the
    // new generation loads, and recomposition never re-triggers this lifecycle effect.
    LifecycleResumeEffect(Unit) {
        historyComicLazyPagingItems.refresh()
        onPauseOrDispose { }
    }

    CommonScaffold(
        title = "历史浏览",
        variableTopBar = { statusBarInset ->
            HistoryVariableTopBar(
                statusBarInset = statusBarInset,
                editing = historyEditState.editing,
                selectedCount = historyEditState.selectedComicIds.size,
                onExitSelection = userViewModel::clearHistorySelection,
                onDownload = { userViewModel.cacheHistoryComics(selectedComics) },
                onDelete = { showDeleteConfirmDialog = true },
            )
        },
        overlayContent = {
            HistoryDeleteGlassConfirm(
                visible = showDeleteConfirmDialog,
                selectedCount = historyEditState.selectedComicIds.size,
                onConfirm = {
                    userViewModel.deleteHistoryComics(selectedComics)
                    showDeleteConfirmDialog = false
                },
                onDismiss = { showDeleteConfirmDialog = false },
            )
        },
    ) { topContentPadding, bottomContentPadding ->
        if (historyComicLazyPagingItems.loadState.refresh is LoadState.Loading && historyComicLazyPagingItems.itemCount == 0) {
            Box(modifier = Modifier.padding(top = topContentPadding)) {
                UserHistoryComicSkeleton()
            }
        } else {
            PullRefreshAndLoadMoreGrid(
                modifier = Modifier.fillMaxSize(),
                lazyPagingItems = historyComicLazyPagingItems,
                key = { it.id },
                columns = adaptiveComicGridCells(miscSettings.gridColumns.history),
                contentPadding = PaddingValues(
                    top = topContentPadding,
                    bottom = bottomContentPadding,
                ),
            ) { comic ->
                Comic(
                    comic = comic,
                    editing = historyEditState.editing,
                    selected = comic.id in historyEditState.selectedComicIds,
                    onLongClick = {
                        if (historyEditState.editing) {
                            userViewModel.toggleHistorySelected(comic.id)
                        } else {
                            userViewModel.enterHistoryEdit(comic.id)
                        }
                    },
                    onToggleSelected = {
                        userViewModel.toggleHistorySelected(comic.id)
                    },
                )
            }
        }
    }

}

/** Reference implementation for the shared glass confirmation design. */
@Composable
private fun HistoryDeleteGlassConfirm(
    visible: Boolean,
    selectedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassConfirmDialog(
        visible = visible,
        title = "删除历史记录",
        message = "确定要删除选中的 $selectedCount 条历史记录吗？",
        confirmText = "删除",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        surfaceId = "history-delete-glass-confirm",
    )
}

@Composable
private fun HistoryVariableTopBar(
    statusBarInset: Dp,
    editing: Boolean,
    selectedCount: Int,
    onExitSelection: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val mainNavController = LocalMainNavController.current
    val mode = if (editing) ChromeMode.SELECTION else ChromeMode.NORMAL
    GlassTopBarModeTransition(
        targetState = mode,
        statusBarInset = statusBarInset,
        modifier = Modifier.fillMaxWidth(),
    ) { targetMode, surfaceAlpha ->
        when (targetMode) {
            ChromeMode.NORMAL -> AppGlassTopBar(
                surfaceId = "history-top-bar-normal",
                statusBarInset = statusBarInset,
                surfaceAlpha = surfaceAlpha,
                navigationIcon = {
                    BackIconButton()
                },
                title = {
                    Text(
                        text = "历史浏览",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
            )

            ChromeMode.SELECTION -> AppGlassTopBar(
                surfaceId = "history-top-bar-selection",
                statusBarInset = statusBarInset,
                surfaceAlpha = surfaceAlpha,
                navigationIcon = {
                    IconButton(onClick = onExitSelection) {
                        Icon(Icons.Rounded.Close, contentDescription = "退出编辑")
                    }
                },
                title = {
                    Text(
                        text = "已选择 $selectedCount 项",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Rounded.Download, contentDescription = "缓存")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
    }
}

package com.par9uet.jm.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.par9uet.jm.ui.components.Comic
import com.par9uet.jm.ui.components.ComicSkeleton
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.components.adaptiveComicGridCells
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
    val localSetting by localSettingManager.localSettingState.collectAsState()
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val selectedComics: List<com.par9uet.jm.data.models.Comic> = remember(historyComicLazyPagingItems.itemSnapshotList, historyEditState.selectedComicIds) {
        historyComicLazyPagingItems.itemSnapshotList.filterNotNull().filter { it.id in historyEditState.selectedComicIds }
    }
    BackHandler(enabled = historyEditState.editing) {
        userViewModel.clearHistorySelection()
    }

    CommonScaffold(
        title = "历史浏览",
        titleContent = if (historyEditState.editing) {
            { Text("已选择 ${historyEditState.selectedComicIds.size} 项") }
        } else {
            null
        },
        navigationContent = if (historyEditState.editing) {
            {
                IconButton(onClick = userViewModel::clearHistorySelection) {
                    Icon(Icons.Rounded.Close, contentDescription = "退出编辑")
                }
            }
        } else {
            null
        },
        actions = {
            if (historyEditState.editing) {
                IconButton(onClick = { userViewModel.cacheHistoryComics(selectedComics) }) {
                    Icon(Icons.Rounded.Download, contentDescription = "缓存")
                }
                IconButton(onClick = { showDeleteConfirmDialog = true }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "删除")
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            if (historyComicLazyPagingItems.loadState.refresh is LoadState.Loading && historyComicLazyPagingItems.itemCount == 0) {
                UserHistoryComicSkeleton()
            } else {
                PullRefreshAndLoadMoreGrid(
                    modifier = Modifier.fillMaxSize(),
                    lazyPagingItems = historyComicLazyPagingItems,
                    key = { it.id },
                    columns = adaptiveComicGridCells(localSetting.historyGridColumns),
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
                        }
                    )
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("删除历史记录") },
            text = { Text("确定删除 ${historyEditState.selectedComicIds.size} 条历史记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    userViewModel.deleteHistoryComics(selectedComics)
                    showDeleteConfirmDialog = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("取消") }
            }
        )
    }
}

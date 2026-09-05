package com.par9uet.jm.ui.screens.downloadScreen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.components.BackIconButton
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.glass.AppGlassTopBar
import com.par9uet.jm.ui.glass.ChromeMode
import com.par9uet.jm.ui.glass.GlassConfirmDialog
import com.par9uet.jm.ui.glass.GlassTopBarModeTransition
import com.par9uet.jm.ui.screens.LocalMainNavController
import com.par9uet.jm.ui.viewModel.DownloadComicGroup
import com.par9uet.jm.ui.viewModel.DownloadViewModel
import com.par9uet.jm.store.LocalSettingManager
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun DownloadScreen(
    downloadViewModel: DownloadViewModel = koinActivityViewModel(),
    localSettingManager: LocalSettingManager = getKoin().get()
) {
    val mainNavController = LocalMainNavController.current
    val completeGroups by downloadViewModel.completeGroups.collectAsState()
    val activeGroups by downloadViewModel.activeGroups.collectAsState()
    val errorGroups by downloadViewModel.errorGroups.collectAsState()
    val editState by downloadViewModel.editState.collectAsState()
    val miscSettings by localSettingManager.misc.collectAsState()
    var completeExpanded by rememberSaveable { mutableStateOf(true) }
    var activeExpanded by rememberSaveable { mutableStateOf(true) }
    var errorExpanded by rememberSaveable { mutableStateOf(true) }

    // 仅当选中项中存在"正在缓存"分组时才显示暂停/继续按钮
    val activeItemIds = remember(activeGroups) {
        activeGroups.flatMap { it.itemIds }.toSet()
    }
    val showPauseResume = remember(editState.selectedIds, activeItemIds) {
        editState.selectedIds.any { it in activeItemIds }
    }

    BackHandler(enabled = editState.editing) {
        downloadViewModel.clearSelection()
    }
    // Pending deletion request waiting for user confirmation (single dialog for both paths).
    var pendingDeleteIds by remember { mutableStateOf<Set<Int>?>(null) }

    CommonScaffold(
        title = "下载",
        variableTopBar = { statusBarInset ->
            DownloadVariableTopBar(
                statusBarInset = statusBarInset,
                editing = editState.editing,
                selectedCount = editState.selectedIds.size,
                showPauseResume = showPauseResume,
                onExitSelection = downloadViewModel::clearSelection,
                onPause = downloadViewModel::pauseSelected,
                onStart = downloadViewModel::startSelected,
                onRedownload = downloadViewModel::redownloadSelected,
                onDelete = { pendingDeleteIds = editState.selectedIds },
            )
        },
        overlayContent = {
            GlassConfirmDialog(
                visible = pendingDeleteIds != null,
                title = "删除缓存",
                message = "确定删除选中的缓存内容吗？删除后需要重新下载。",
                confirmText = "删除",
                destructive = true,
                surfaceId = "download-delete-glass-confirm",
                onConfirm = {
                    pendingDeleteIds?.let { ids -> downloadViewModel.deleteMany(ids) }
                    pendingDeleteIds = null
                },
                onDismiss = { pendingDeleteIds = null },
            )
        },
    ) { topContentPadding, bottomContentPadding ->
        Column(modifier = Modifier.fillMaxSize()) {
            val activeCount = activeGroups.size
            val errorCount = errorGroups.size
            val completeCount = completeGroups.size
            val totalCount = activeCount + errorCount + completeCount

            if (totalCount == 0) {
                DownloadEmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = topContentPadding + 8.dp,
                        bottom = bottomContentPadding + 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (activeCount > 0) {
                        item(key = "active_header") {
                            DownloadSectionHeader(
                                title = "正在缓存",
                                count = activeCount,
                                expanded = activeExpanded,
                                onClick = { activeExpanded = !activeExpanded },
                                accentColor = MaterialTheme.colorScheme.primary,
                                icon = Icons.Rounded.Schedule
                            )
                        }
                        item(key = "active_content") {
                            AnimatedVisibility(
                                visible = activeExpanded,
                                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                                exit = shrinkVertically(tween(160)) + fadeOut(tween(160)),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    activeGroups.forEach { group ->
                                        key(group.id) {
                                            DownloadRowItem(
                                                modifier = Modifier.fillMaxWidth(),
                                                group = group,
                                                editing = editState.editing,
                                                selected = editState.selectedIds.containsAll(group.itemIds),
                                                onClick = {
                                                    if (editState.editing) {
                                                        downloadViewModel.toggleSelected(group.itemIds)
                                                    } else {
                                                        mainNavController.navigate("downloadComicDetail/${group.id}")
                                                    }
                                                },
                                                onLongClick = { downloadViewModel.enterEdit(group.itemIds) },
                                                onCancel = { downloadViewModel.deleteMany(group.itemIds) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (completeCount > 0) {
                        item(key = "complete_header") {
                            DownloadSectionHeader(
                                title = "缓存完成",
                                count = completeCount,
                                expanded = completeExpanded,
                                onClick = { completeExpanded = !completeExpanded },
                                accentColor = MaterialTheme.colorScheme.tertiary,
                                icon = Icons.Rounded.DownloadDone
                            )
                        }
                        item(key = "complete_content") {
                            AnimatedVisibility(
                                visible = completeExpanded,
                                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                                exit = shrinkVertically(tween(160)) + fadeOut(tween(160)),
                            ) {
                                CompletedGrid(
                                    groups = completeGroups,
                                    editing = editState.editing,
                                    selectedIds = editState.selectedIds,
                                    gridColumns = miscSettings.gridColumns.download,
                                    onClick = { group ->
                                        if (editState.editing) {
                                            downloadViewModel.toggleSelected(group.itemIds)
                                        } else {
                                            mainNavController.navigate("downloadComicDetail/${group.id}")
                                        }
                                    },
                                    onLongClick = { group ->
                                        downloadViewModel.enterEdit(group.itemIds)
                                    }
                                )
                            }
                        }
                    }

                    if (errorCount > 0) {
                        item(key = "error_header") {
                            DownloadSectionHeader(
                                title = "缓存失败",
                                count = errorCount,
                                expanded = errorExpanded,
                                onClick = { errorExpanded = !errorExpanded },
                                accentColor = MaterialTheme.colorScheme.error,
                                icon = Icons.Rounded.ErrorOutline
                            )
                        }
                        item(key = "error_content") {
                            AnimatedVisibility(
                                visible = errorExpanded,
                                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                                exit = shrinkVertically(tween(160)) + fadeOut(tween(160)),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    errorGroups.forEach { group ->
                                        key(group.id) {
                                            DownloadRowItem(
                                                modifier = Modifier.fillMaxWidth(),
                                                group = group,
                                                editing = editState.editing,
                                                selected = editState.selectedIds.containsAll(group.itemIds),
                                                onClick = {
                                                    if (editState.editing) {
                                                        downloadViewModel.toggleSelected(group.itemIds)
                                                    } else {
                                                        mainNavController.navigate("downloadComicDetail/${group.id}")
                                                    }
                                                },
                                                onLongClick = { downloadViewModel.enterEdit(group.itemIds) },
                                                onCancel = { downloadViewModel.deleteMany(group.itemIds) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadVariableTopBar(
    statusBarInset: Dp,
    editing: Boolean,
    selectedCount: Int,
    showPauseResume: Boolean,
    onExitSelection: () -> Unit,
    onPause: () -> Unit,
    onStart: () -> Unit,
    onRedownload: () -> Unit,
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
                surfaceId = "download-top-bar-normal",
                statusBarInset = statusBarInset,
                surfaceAlpha = surfaceAlpha,
                navigationIcon = {
                    BackIconButton()
                },
                title = {
                    Text(
                        text = "下载",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
            )

            ChromeMode.SELECTION -> AppGlassTopBar(
                surfaceId = "download-top-bar-selection",
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
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (showPauseResume) {
                        IconButton(onClick = onStart, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "继续")
                        }
                        IconButton(onClick = onPause, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Rounded.Pause, contentDescription = "暂停")
                        }
                    }
                    IconButton(onClick = onRedownload, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重下")
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
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

@Composable
private fun DownloadEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.DownloadDone,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outlineVariant
        )
        Text(
            text = "暂无缓存任务",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "从漫画详情页点击缓存即可在此查看",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun DownloadSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    accentColor: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "download-section-icon-rotation",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = accentColor.copy(alpha = 0.12f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                modifier = Modifier.weight(1f),
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            BadgedBox(
                badge = {
                    if (count > 0) {
                        Badge(containerColor = accentColor.copy(alpha = 0.2f)) {
                            Text(
                                text = count.toString(),
                                color = accentColor,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            ) {}
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompletedGrid(
    groups: List<DownloadComicGroup>,
    editing: Boolean,
    selectedIds: Set<Int>,
    gridColumns: Int,
    onClick: (DownloadComicGroup) -> Unit,
    onLongClick: (DownloadComicGroup) -> Unit
) {
    val configuration = LocalConfiguration.current
    val columns = if (gridColumns > 0) {
        gridColumns
    } else {
        when {
            configuration.screenWidthDp >= 600 -> 4
            configuration.screenWidthDp >= 400 -> 3
            else -> 2
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { group ->
                    DownloadCoverGridItem(
                        modifier = Modifier.weight(1f),
                        group = group,
                        editing = editing,
                        selected = selectedIds.containsAll(group.itemIds),
                        onClick = { onClick(group) },
                        onLongClick = { onLongClick(group) }
                    )
                }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

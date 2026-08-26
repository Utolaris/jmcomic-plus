package com.par9uet.jm.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.ui.components.Comic
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.glass.GlassConfirmDialog
import com.par9uet.jm.ui.glass.GlassModal
import com.par9uet.jm.ui.components.adaptiveComicGridCells
import com.par9uet.jm.ui.interaction.pullDownToAction
import com.par9uet.jm.ui.interaction.PullDownActionState
import com.par9uet.jm.ui.interaction.rememberPullDownActionState
import com.par9uet.jm.ui.screens.tabScreen.FavoritesMaterialTopBar
import com.par9uet.jm.ui.screens.tabScreen.FavoritesUiController
import com.par9uet.jm.ui.screens.tabScreen.rememberFavoritesUiController
import com.par9uet.jm.ui.viewModel.UserViewModel
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.utils.log
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserCollectComicScreen(
    userViewModel: UserViewModel = koinActivityViewModel(),
    useScaffold: Boolean = true,
    localSettingManager: LocalSettingManager = getKoin().get(),
    uiController: FavoritesUiController? = null,
    pullDownState: PullDownActionState = rememberPullDownActionState(),
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
) {
    val controller = uiController ?: rememberFavoritesUiController()
    val navController = LocalMainNavController.current
    val collectComicLazyPagingItems = userViewModel.collectComicPager.collectAsLazyPagingItems()
    val collectComicFilter by userViewModel.collectComicFilter.collectAsState()
    val tagCountMap by userViewModel.collectTagCounts.collectAsState()
    val authorCountMap by userViewModel.collectAuthorCounts.collectAsState()
    val selectedFolderId by userViewModel.selectedFolderId.collectAsState()
    val folderList by userViewModel.folderList.collectAsState()
    val collectEditState by userViewModel.collectEditState.collectAsState()
    val localSetting by localSettingManager.localSettingState.collectAsState()
    var draftSelectedTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draftSelectedAuthors by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draftTagLogic by remember { mutableStateOf(TagFilterLogic.AND) }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var actionFolderId by remember { mutableStateOf<String?>(null) }
    var actionFolderName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameFolderName by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
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

    val folders = remember(folderList) {
        val result = linkedMapOf<String, String>()
        result["0"] = folderList["0"] ?: "全部"
        folderList.filterKeys { it != "0" }.forEach { (id, name) -> result[id] = name }
        result
    }

    val selectedComics: List<com.par9uet.jm.data.models.Comic> = remember(collectComicLazyPagingItems.itemSnapshotList, collectEditState.selectedComicIds) {
        collectComicLazyPagingItems.itemSnapshotList.filterNotNull().filter { it.id in collectEditState.selectedComicIds }
    }
    val currentSelectedComics = rememberUpdatedState(selectedComics)
    val selectedComicsProvider = remember { { currentSelectedComics.value } }
    DisposableEffect(controller, selectedComicsProvider) {
        controller.bindSelectedComics(selectedComicsProvider)
        onDispose { controller.unbindSelectedComics(selectedComicsProvider) }
    }

    // Restore the saved viewport from the FIRST frame; no visible jump back to top.
    val savedViewport by userViewModel.favoriteViewport.collectAsState()
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = savedViewport.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = savedViewport.firstVisibleItemScrollOffset,
    )
    val initialResetGeneration = remember { savedViewport.resetGeneration }
    var suppressViewportPersistence by remember { mutableStateOf(false) }

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
    LaunchedEffect(gridState, savedViewport.resetGeneration) {
        val resetGeneration = savedViewport.resetGeneration
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }.distinctUntilChanged().collect { (index, offset) ->
            if (!suppressViewportPersistence) {
                userViewModel.saveFavoriteViewport(index, offset, resetGeneration)
            }
        }
    }
    val pullRevealPadding = 36.dp * pullDownState.progress
    val gridModifier = Modifier
        .fillMaxSize()
        .pullDownToAction(
            state = pullDownState,
            enabled = !collectEditState.editing && !controller.searchActive,
            isAtTop = { !gridState.canScrollBackward },
            onTrigger = controller::enterSearch,
        )

    LaunchedEffect(controller.filterDialogVisible) {
        if (controller.filterDialogVisible) {
            draftSelectedTags = collectComicFilter.selectedTags
            draftSelectedAuthors = collectComicFilter.selectedAuthors
            draftTagLogic = collectComicFilter.tagLogic
        }
    }

    // The source composition owns only the paging grid and transient dialogs. All persistent
    // phone controls live in the GlassCaptureHost overlay.
    val mainContent: @Composable () -> Unit = {
        PullRefreshAndLoadMoreGrid(
            modifier = gridModifier,
            lazyPagingItems = collectComicLazyPagingItems,
            key = { it.id },
            columns = adaptiveComicGridCells(localSetting.collectGridColumns),
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
                    if (controller.searchActive) {
                        controller.exitSearch()
                        userViewModel.updateCollectSearchText("")
                    }
                    if (collectEditState.editing) {
                        userViewModel.toggleCollectSelected(comic.id)
                    } else {
                        userViewModel.enterCollectEdit(comic.id)
                    }
                },
                onToggleSelected = {
                    userViewModel.toggleCollectSelected(comic.id)
                },
            )
        }
    }

    if (useScaffold) {
        Scaffold(
            topBar = {
                FavoritesMaterialTopBar(
                    controller = controller,
                    userViewModel = userViewModel,
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

    if (controller.filterDialogVisible) {
        FilterDialog(
            tagCountMap = tagCountMap,
            authorCountMap = authorCountMap,
            draftSelectedTags = draftSelectedTags,
            draftSelectedAuthors = draftSelectedAuthors,
            draftTagLogic = draftTagLogic,
            onTagToggle = { tag ->
                draftSelectedTags = if (tag in draftSelectedTags) {
                    draftSelectedTags - tag
                } else {
                    draftSelectedTags + tag
                }
            },
            onAuthorToggle = { author ->
                draftSelectedAuthors = if (author in draftSelectedAuthors) {
                    draftSelectedAuthors - author
                } else {
                    draftSelectedAuthors + author
                }
            },
            onTagLogicChange = { draftTagLogic = it },
            onConfirm = {
                userViewModel.updateCollectSelectedTags(draftSelectedTags)
                userViewModel.updateCollectSelectedAuthors(draftSelectedAuthors)
                userViewModel.updateCollectTagLogic(draftTagLogic)
                controller.dismissFilterDialog()
            },
            onClear = {
                draftSelectedTags = emptySet()
                draftSelectedAuthors = emptySet()
                draftTagLogic = TagFilterLogic.AND
                userViewModel.updateCollectSelectedTags(emptySet())
                userViewModel.updateCollectSelectedAuthors(emptySet())
                userViewModel.updateCollectTagLogic(TagFilterLogic.AND)
                controller.dismissFilterDialog()
            },
            onDismiss = controller::dismissFilterDialog,
        )
    }

    if (useScaffold) {
        GlassModal(
            visible = showCreateFolderDialog,
            onDismissRequest = {
                showCreateFolderDialog = false
                newFolderName = ""
            },
            surfaceId = "favorites-create-folder-glass-modal",
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("新建收藏夹", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    label = { Text("文件夹名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        newFolderName = ""
                        showCreateFolderDialog = false
                    }) { Text("取消") }
                    TextButton(onClick = {
                        if (newFolderName.isNotBlank()) {
                            userViewModel.createFolder(newFolderName.trim())
                            newFolderName = ""
                            showCreateFolderDialog = false
                        }
                    }) { Text("创建") }
                }
            }
        }
    }

    if (controller.folderManagementVisible) {
        val manageSheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = controller::dismissFolderManagement,
            sheetState = manageSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    "管理收藏夹",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "点击文件夹名称可切换当前收藏夹，右侧按钮可重命名或删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(folders.entries.toList(), key = { it.key }) { (folderId, folderName) ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (selectedFolderId == folderId.toIntOrNull())
                                MaterialTheme.colorScheme.secondaryContainer
                            else androidx.compose.ui.graphics.Color.Transparent,
                            onClick = {
                                userViewModel.changeFolder(folderId.toIntOrNull() ?: 0)
                                controller.dismissFolderManagement()
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (folderId == "0") Icons.Rounded.Bookmarks else Icons.Rounded.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    folderName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                if (folderId != "0") {
                                    IconButton(onClick = {
                                        actionFolderId = folderId
                                        actionFolderName = folderName
                                        renameFolderName = folderName
                                        showRenameDialog = true
                                    }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "重命名")
                                    }
                                    IconButton(onClick = {
                                        actionFolderId = folderId
                                        actionFolderName = folderName
                                        showDeleteConfirmDialog = true
                                    }) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        newFolderName = ""
                        showCreateFolderDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("新建收藏夹")
                }
            }
        }
    }

    if (useScaffold) {
        GlassModal(
            visible = showRenameDialog,
            onDismissRequest = { showRenameDialog = false },
            surfaceId = "favorites-rename-folder-glass-modal",
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("重命名收藏夹", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = renameFolderName,
                    onValueChange = { renameFolderName = it },
                    singleLine = true,
                    label = { Text("新名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
                    TextButton(onClick = {
                        val folderId = actionFolderId
                        if (renameFolderName.isNotBlank() && folderId != null) {
                            userViewModel.renameFolder(folderId, renameFolderName.trim())
                            showRenameDialog = false
                        }
                    }) { Text("确定") }
                }
            }
        }
    }

    if (useScaffold) {
        GlassConfirmDialog(
            visible = showDeleteConfirmDialog,
            title = "删除收藏夹",
            message = "确定删除「${actionFolderName}」吗？\n注意：删除收藏夹不会删除其中的漫画，漫画会移至「全部」。",
                confirmText = "删除",
                destructive = true,
                surfaceId = "favorites-delete-folder-glass-confirm",
                onConfirm = {
                    val folderId = actionFolderId
                    if (folderId != null) {
                        userViewModel.deleteFolder(folderId)
                        showDeleteConfirmDialog = false
                    }
                },
                onDismiss = { showDeleteConfirmDialog = false },
            )
    }

    GlassConfirmDialog(
        visible = useScaffold && controller.deleteDialogVisible,
        title = "取消收藏",
        message = "确定取消收藏 ${collectEditState.selectedComicIds.size} 部漫画吗？",
        confirmText = "取消收藏",
        surfaceId = "favorites-uncollect-glass-confirm",
        onConfirm = {
            userViewModel.deleteCollectedComics(selectedComics)
            controller.dismissDeleteDialog()
        },
        onDismiss = controller::dismissDeleteDialog,
    )

    if (controller.moveDialogVisible) {
        MoveFolderSheet(
            folders = folders,
            currentFolderId = selectedFolderId,
            onMove = { folderId ->
                userViewModel.moveCollectedToFolder(selectedComics, folderId)
                controller.dismissMoveDialog()
            },
            onDismiss = controller::dismissMoveDialog,
        )
    }
}

// 筛选弹窗：ModalBottomSheet 支持上划全屏 + 逻辑门选择 + Tab（标签/作者）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDialog(
    tagCountMap: Map<String, Int>,
    authorCountMap: Map<String, Int>,
    draftSelectedTags: Set<String>,
    draftSelectedAuthors: Set<String>,
    draftTagLogic: TagFilterLogic,
    onTagToggle: (String) -> Unit,
    onAuthorToggle: (String) -> Unit,
    onTagLogicChange: (TagFilterLogic) -> Unit,
    onConfirm: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    // 弹窗内的搜索文本，用于过滤当前页的标签或作者
    var filterQuery by remember { mutableStateOf("") }
    // 打开即全屏
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 根据搜索文本过滤当前页内容
    val query = filterQuery.trim()
    val filteredTagCountMap = remember(tagCountMap, query) {
        if (query.isBlank()) tagCountMap
        else tagCountMap.filterKeys { it.contains(query, ignoreCase = true) }
    }
    val filteredAuthorCountMap = remember(authorCountMap, query) {
        if (query.isBlank()) authorCountMap
        else authorCountMap.filterKeys { it.contains(query, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        // The filter sheet is a full-height panel with its own scrollable content; disable
        // sheet dragging so overscroll at the content edges cannot violently move the entire
        // sheet. Dismissal still works via the buttons, back press, and scrim click.
        sheetGesturesEnabled = false,
        // The sheet cannot be dragged anymore; hide the handle so it does not imply it can.
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            // 标题
            Text(
                text = "筛选收藏",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // 逻辑门选择（仅对标签生效），放在筛选页最上面
            Text(
                text = "标签筛选逻辑",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TagFilterLogic.entries.forEach { logic ->
                    FilterChip(
                        selected = draftTagLogic == logic,
                        onClick = { onTagLogicChange(logic) },
                        label = {
                            Text(
                                text = logic.label,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // 搜索框：搜索 tag 或作者
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = filterQuery,
                onValueChange = { filterQuery = it },
                singleLine = true,
                placeholder = {
                    Text(
                        if (selectedTabIndex == 0) "搜索标签" else "搜索作者"
                    )
                },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (filterQuery.isNotEmpty()) {
                        IconButton(onClick = { filterQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清除")
                        }
                    }
                },
                shape = MaterialTheme.shapes.large,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Tab 行
            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = {
                        selectedTabIndex = 0
                        filterQuery = ""
                    },
                    text = { Text("标签 (${tagCountMap.size})") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = {
                        selectedTabIndex = 1
                        filterQuery = ""
                    },
                    text = { Text("作者 (${authorCountMap.size})") }
                )
            }
            // 内容区：可滚动，填满剩余空间
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedTabIndex) {
                    0 -> {
                        if (filteredTagCountMap.isEmpty()) {
                            Text(
                                if (tagCountMap.isEmpty()) "当前已加载收藏中没有可筛选的标签"
                                else "没有匹配「$query」的标签",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                filteredTagCountMap.forEach { (tag, count) ->
                                    FilterChip(
                                        selected = tag in draftSelectedTags,
                                        onClick = { onTagToggle(tag) },
                                        label = { Text("$tag  $count") }
                                    )
                                }
                            }
                        }
                    }
                    1 -> {
                        if (filteredAuthorCountMap.isEmpty()) {
                            Text(
                                if (authorCountMap.isEmpty()) "当前已加载收藏中没有可筛选的作者"
                                else "没有匹配「$query」的作者",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                filteredAuthorCountMap.forEach { (author, count) ->
                                    FilterChip(
                                        selected = author in draftSelectedAuthors,
                                        onClick = { onAuthorToggle(author) },
                                        label = { Text("$author  $count") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 底部操作栏（固定不随内容滚动消失）
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onClear,
                        modifier = Modifier.weight(1f)
                    ) { Text("清空") }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f)
                    ) { Text("确定") }
                }
            }
        }
    }
}

// 移动到收藏夹：ModalBottomSheet + LazyColumn + Radio 选择
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MoveFolderSheet(
    folders: Map<String, String>,
    currentFolderId: Int?,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 排除「全部」与当前所在收藏夹
    val availableFolders = remember(folders, currentFolderId) {
        folders.filterKeys { it != "0" && it.toIntOrNull() != currentFolderId }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "移动到收藏夹",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            if (availableFolders.isEmpty()) {
                Text(
                    text = "暂无其他收藏夹，请先创建",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    items(availableFolders.entries.toList(), key = { it.key }) { (folderId, folderName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { selectedFolderId = folderId })
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedFolderId == folderId,
                                onClick = { selectedFolderId = folderId }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = folderName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
                Button(
                    onClick = { selectedFolderId?.let(onMove) },
                    enabled = selectedFolderId != null,
                    modifier = Modifier.weight(1f)
                ) { Text("移动") }
            }
        }
    }
}

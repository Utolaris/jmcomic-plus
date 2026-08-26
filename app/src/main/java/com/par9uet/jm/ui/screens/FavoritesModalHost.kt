package com.par9uet.jm.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.favorites.model.FavoritesIntent
import com.par9uet.jm.favorites.model.FavoritesModal
import com.par9uet.jm.favorites.presentation.FavoritesViewModel
import com.par9uet.jm.ui.glass.GlassConfirmDialog
import com.par9uet.jm.ui.glass.GlassModal

/** Renders every Favorites modal from the single ViewModel-owned modal state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoritesModalHost(favoritesViewModel: FavoritesViewModel) {
    val favoritesState by favoritesViewModel.uiState.collectAsState()
    val collectComicFilter = favoritesState.filter
    val tagCountMap = favoritesState.tagCounts
    val authorCountMap = favoritesState.authorCounts
    val selectedFolderId = favoritesState.selectedFolderId
    val folders = remember(favoritesState.folders) {
        buildMap {
            put("0", favoritesState.folders["0"] ?: "全部")
            favoritesState.folders
                .filterKeys { it != "0" }
                .forEach { (id, name) -> put(id, name) }
        }
    }
    val collectEditState = favoritesState.selection
    var draftSelectedTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draftSelectedAuthors by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draftTagLogic by remember { mutableStateOf(TagFilterLogic.AND) }
    var newFolderName by remember { mutableStateOf("") }
    var renameFolderName by remember { mutableStateOf("") }

    LaunchedEffect(favoritesState.modal) {
        if (favoritesState.modal is FavoritesModal.Filter) {
            draftSelectedTags = collectComicFilter.selectedTags
            draftSelectedAuthors = collectComicFilter.selectedAuthors
            draftTagLogic = collectComicFilter.tagLogic
        }
    }

    if (favoritesState.modal is FavoritesModal.Filter) {
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
                favoritesViewModel.onIntent(
                    FavoritesIntent.FilterApplied(
                        selectedTags = draftSelectedTags,
                        selectedAuthors = draftSelectedAuthors,
                        tagLogic = draftTagLogic,
                    )
                )
            },
            onClear = {
                draftSelectedTags = emptySet()
                draftSelectedAuthors = emptySet()
                draftTagLogic = TagFilterLogic.AND
                favoritesViewModel.onIntent(FavoritesIntent.FilterCleared)
            },
            onDismiss = {
                favoritesViewModel.onIntent(FavoritesIntent.FilterDismissed)
            },
        )
    }

    val activeModal = favoritesState.modal

    GlassModal(
        visible = activeModal is FavoritesModal.CreateFolder,
        onDismissRequest = {
            newFolderName = ""
            favoritesViewModel.onIntent(FavoritesIntent.FolderActionDismissed)
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
                    favoritesViewModel.onIntent(FavoritesIntent.FolderActionDismissed)
                }) { Text("取消") }
                TextButton(onClick = {
                    if (newFolderName.isNotBlank()) {
                        favoritesViewModel.onIntent(
                            FavoritesIntent.CreateFolderSubmitted(newFolderName)
                        )
                        newFolderName = ""
                    }
                }) { Text("创建") }
            }
        }
    }

    if (activeModal is FavoritesModal.FolderManagement) {
        val manageSheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = {
                favoritesViewModel.onIntent(FavoritesIntent.FolderManagementDismissed)
            },
            sheetState = manageSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    "管理收藏夹",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "点击文件夹名称可切换当前收藏夹，右侧按钮可重命名或删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(folders.entries.toList(), key = { it.key }) { (folderId, folderName) ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (selectedFolderId == folderId.toIntOrNull()) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                            onClick = {
                                favoritesViewModel.onIntent(
                                    FavoritesIntent.FolderSelected(folderId.toIntOrNull() ?: 0)
                                )
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (folderId == "0") {
                                        Icons.Rounded.Bookmarks
                                    } else {
                                        Icons.Rounded.Folder
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    folderName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                if (folderId != "0") {
                                    IconButton(onClick = {
                                        favoritesViewModel.onIntent(
                                            FavoritesIntent.RenameFolderOpened(
                                                folderId = folderId.toIntOrNull() ?: 0,
                                                folderName = folderName,
                                            )
                                        )
                                    }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "重命名")
                                    }
                                    IconButton(onClick = {
                                        favoritesViewModel.onIntent(
                                            FavoritesIntent.DeleteFolderOpened(
                                                folderId = folderId.toIntOrNull() ?: 0,
                                                folderName = folderName,
                                            )
                                        )
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
                        favoritesViewModel.onIntent(FavoritesIntent.CreateFolderOpened)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("新建收藏夹")
                }
            }
        }
    }

    val renameModal = activeModal as? FavoritesModal.RenameFolder
    LaunchedEffect(renameModal) {
        renameFolderName = renameModal?.folderName.orEmpty()
    }
    GlassModal(
        visible = renameModal != null,
        onDismissRequest = {
            favoritesViewModel.onIntent(FavoritesIntent.FolderActionDismissed)
        },
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
                TextButton(onClick = {
                    favoritesViewModel.onIntent(FavoritesIntent.FolderActionDismissed)
                }) { Text("取消") }
                TextButton(onClick = {
                    renameModal?.let { modal ->
                        if (renameFolderName.isNotBlank()) {
                            favoritesViewModel.onIntent(
                                FavoritesIntent.RenameFolderSubmitted(
                                    folderId = modal.folderId,
                                    name = renameFolderName,
                                )
                            )
                        }
                    }
                }) { Text("确定") }
            }
        }
    }

    val deleteModal = activeModal as? FavoritesModal.DeleteFolder
    GlassConfirmDialog(
        visible = deleteModal != null,
        title = "删除收藏夹",
        message = "确定删除「${deleteModal?.folderName.orEmpty()}」吗？\n注意：删除收藏夹不会删除其中的漫画，漫画会移至「全部」。",
        confirmText = "删除",
        destructive = true,
        surfaceId = "favorites-delete-folder-glass-confirm",
        onConfirm = { favoritesViewModel.onIntent(FavoritesIntent.DeleteFolderConfirmed) },
        onDismiss = { favoritesViewModel.onIntent(FavoritesIntent.FolderActionDismissed) },
    )

    GlassConfirmDialog(
        visible = activeModal is FavoritesModal.Uncollect,
        title = "取消收藏",
        message = "确定取消收藏 ${collectEditState.selectedComicIds.size} 部漫画吗？",
        confirmText = "取消收藏",
        surfaceId = "favorites-uncollect-glass-confirm",
        onConfirm = { favoritesViewModel.onIntent(FavoritesIntent.UncollectConfirmed) },
        onDismiss = { favoritesViewModel.onIntent(FavoritesIntent.ModalDismissed) },
    )

    if (activeModal is FavoritesModal.Move) {
        MoveFolderSheet(
            folders = folders,
            currentFolderId = selectedFolderId,
            onMove = { folderId ->
                favoritesViewModel.onIntent(
                    FavoritesIntent.MoveConfirmed(folderId.toIntOrNull() ?: 0)
                )
            },
            onDismiss = { favoritesViewModel.onIntent(FavoritesIntent.ModalDismissed) },
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
    onDismiss: () -> Unit,
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var filterQuery by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = "筛选收藏",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = "标签筛选逻辑",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TagFilterLogic.entries.forEach { logic ->
                    FilterChip(
                        selected = draftTagLogic == logic,
                        onClick = { onTagLogicChange(logic) },
                        label = {
                            Text(
                                text = logic.label,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = filterQuery,
                onValueChange = { filterQuery = it },
                singleLine = true,
                placeholder = { Text(if (selectedTabIndex == 0) "搜索标签" else "搜索作者") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
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
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Spacer(modifier = Modifier.height(12.dp))
            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = {
                        selectedTabIndex = 0
                        filterQuery = ""
                    },
                    text = { Text("标签 (${tagCountMap.size})") },
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = {
                        selectedTabIndex = 1
                        filterQuery = ""
                    },
                    text = { Text("作者 (${authorCountMap.size})") },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (selectedTabIndex) {
                    0 -> {
                        if (filteredTagCountMap.isEmpty()) {
                            Text(
                                if (tagCountMap.isEmpty()) {
                                    "当前已加载收藏中没有可筛选的标签"
                                } else {
                                    "没有匹配「$query」的标签"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                filteredTagCountMap.forEach { (tag, count) ->
                                    FilterChip(
                                        selected = tag in draftSelectedTags,
                                        onClick = { onTagToggle(tag) },
                                        label = { Text("$tag  $count") },
                                    )
                                }
                            }
                        }
                    }
                    1 -> {
                        if (filteredAuthorCountMap.isEmpty()) {
                            Text(
                                if (authorCountMap.isEmpty()) {
                                    "当前已加载收藏中没有可筛选的作者"
                                } else {
                                    "没有匹配「$query」的作者"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                filteredAuthorCountMap.forEach { (author, count) ->
                                    FilterChip(
                                        selected = author in draftSelectedAuthors,
                                        onClick = { onAuthorToggle(author) },
                                        label = { Text("$author  $count") },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onClear,
                        modifier = Modifier.weight(1f),
                    ) { Text("清空") }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
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
    onDismiss: () -> Unit,
) {
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val availableFolders = remember(folders, currentFolderId) {
        folders.filterKeys { it != "0" && it.toIntOrNull() != currentFolderId }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = "移动到收藏夹",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (availableFolders.isEmpty()) {
                Text(
                    text = "暂无其他收藏夹，请先创建",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(availableFolders.entries.toList(), key = { it.key }) { (folderId, folderName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { selectedFolderId = folderId })
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedFolderId == folderId,
                                onClick = { selectedFolderId = folderId },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = folderName,
                                style = MaterialTheme.typography.bodyLarge,
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text("取消") }
                Button(
                    onClick = { selectedFolderId?.let(onMove) },
                    enabled = selectedFolderId != null,
                    modifier = Modifier.weight(1f),
                ) { Text("移动") }
            }
        }
    }
}

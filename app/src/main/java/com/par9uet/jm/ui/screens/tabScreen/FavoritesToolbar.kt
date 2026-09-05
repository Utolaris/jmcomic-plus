package com.par9uet.jm.ui.screens.tabScreen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.components.BackIconButton
import com.par9uet.jm.ui.components.FavoriteSyncIconButton
import com.par9uet.jm.ui.glass.GlassAnchoredMenuState
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle
import com.par9uet.jm.ui.glass.glassMenuAnchor
import com.par9uet.jm.favorites.model.FavoritesIntent
import com.par9uet.jm.favorites.presentation.FavoritesViewModel

internal enum class FavoritesToolbarMode {
    NORMAL,
    SEARCH,
    SELECTION,
}

internal fun resolveFavoritesToolbarMode(
    searchActive: Boolean,
    selectedCount: Int,
): FavoritesToolbarMode = when {
    selectedCount > 0 -> FavoritesToolbarMode.SELECTION
    searchActive -> FavoritesToolbarMode.SEARCH
    else -> FavoritesToolbarMode.NORMAL
}

internal object FavoritesToolbarDefaults {
    val toolbarHeight = 58.dp
    val outerMargin = 8.dp
    val controlHeight = 50.dp
}

internal fun resolveFavoriteFolderTitle(
    selectedFolderId: Int,
    folderList: Map<String, String>,
): String = if (selectedFolderId == 0) {
    "我的收藏"
} else {
    folderList[selectedFolderId.toString()] ?: "我的收藏"
}

@Composable
private fun FavoritesSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    requestInitialFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    if (requestInitialFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.focusRequester(focusRequester),
        singleLine = true,
        maxLines = 1,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = "搜索漫画名 / 作者 / 标签",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = onClear, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "清除")
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoritesMaterialFolderTitle(
    title: String,
    selectedFolderId: Int,
    folderList: Map<String, String>,
    onFolderSelected: (Int) -> Unit,
    onManageFolders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val menuMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.56f
    val folders = remember(folderList) {
        buildList {
            add(0 to "全部收藏")
            folderList.entries
                .filter { it.key != "0" }
                .forEach { (id, name) -> id.toIntOrNull()?.let { add(it to name) } }
        }
    }

    Box(
        modifier = modifier.combinedClickable(
            onClick = {},
            onLongClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                menuExpanded = true
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.heightIn(max = menuMaxHeight),
        ) {
            folders.forEach { (folderId, folderName) ->
                DropdownMenuItem(
                    text = { Text(folderName) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (folderId == 0) Icons.Rounded.Bookmarks else Icons.Rounded.Folder,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = if (folderId == selectedFolderId) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        menuExpanded = false
                        onFolderSelected(folderId)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("管理收藏夹") },
                leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onManageFolders()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoritesGlassFolderTitle(
    title: String,
    menuState: GlassAnchoredMenuState,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .glassMenuAnchor(menuState)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuState.open()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun FavoritesVariableGlassTopBar(
    statusBarInset: Dp,
    favoritesViewModel: FavoritesViewModel,
    folderMenuState: GlassAnchoredMenuState,
    modifier: Modifier = Modifier,
) {
    val state by favoritesViewModel.uiState.collectAsState()
    val selectedFolderId = state.selectedFolderId
    val selectedCount = state.selection.selectedComicIds.size
    val mode = resolveFavoritesToolbarMode(state.searchActive, selectedCount)
    val keyboardController = LocalSoftwareKeyboardController.current
    val title = resolveFavoriteFolderTitle(selectedFolderId, state.folders)
    val activeFilterCount = state.filter.selectedTags.size + state.filter.selectedAuthors.size

    fun exitSearch() {
        favoritesViewModel.onIntent(FavoritesIntent.SearchExited)
        keyboardController?.hide()
    }

    LaunchedEffect(selectedCount) {
        if (selectedCount > 0 && state.searchActive) exitSearch()
    }
    LaunchedEffect(mode) {
        if (mode != FavoritesToolbarMode.SEARCH) keyboardController?.hide()
        if (mode != FavoritesToolbarMode.NORMAL) folderMenuState.dismiss()
    }
    BackHandler(enabled = mode != FavoritesToolbarMode.NORMAL) {
        when (mode) {
            FavoritesToolbarMode.SEARCH -> exitSearch()
            FavoritesToolbarMode.SELECTION -> favoritesViewModel.onIntent(FavoritesIntent.SelectionCleared)
            FavoritesToolbarMode.NORMAL -> Unit
        }
    }

    val modeTransition = updateTransition(targetState = mode, label = "favorites-toolbar-mode")
    val normalSurfaceAlpha by modeTransition.animateFloat(
        transitionSpec = { tween(260) },
        label = "favorites-normal-glass-alpha",
    ) { if (it == FavoritesToolbarMode.NORMAL) 1f else 0f }
    val searchSurfaceAlpha by modeTransition.animateFloat(
        transitionSpec = { tween(260) },
        label = "favorites-search-glass-alpha",
    ) { if (it == FavoritesToolbarMode.SEARCH) 1f else 0f }
    val selectionSurfaceAlpha by modeTransition.animateFloat(
        transitionSpec = { tween(260) },
        label = "favorites-selection-glass-alpha",
    ) { if (it == FavoritesToolbarMode.SELECTION) 1f else 0f }

    modeTransition.AnimatedContent(
        modifier = modifier
            .fillMaxWidth()
            .height(statusBarInset + FavoritesToolbarDefaults.toolbarHeight + FavoritesToolbarDefaults.outerMargin),
        transitionSpec = {
            (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 14 }) togetherWith
                (fadeOut(tween(220)) + slideOutHorizontally(tween(220)) { -it / 14 })
        },
        contentAlignment = Alignment.TopCenter,
    ) { targetMode ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = statusBarInset + FavoritesToolbarDefaults.outerMargin,
                    start = FavoritesToolbarDefaults.outerMargin,
                    end = FavoritesToolbarDefaults.outerMargin,
                ),
        ) {
            when (targetMode) {
                FavoritesToolbarMode.NORMAL -> FavoritesNormalGlassContent(
                    title = title,
                    isSyncing = state.sync.isSyncing,
                    hasSyncError = state.sync.errorMessage != null,
                    activeFilterCount = activeFilterCount,
                    surfaceAlpha = normalSurfaceAlpha,
                    folderMenuState = folderMenuState,
                    onSync = {
                        favoritesViewModel.onIntent(FavoritesIntent.ManualSync)
                    },
                    onSearch = {
                        folderMenuState.dismiss()
                        favoritesViewModel.onIntent(FavoritesIntent.SearchEntered)
                    },
                    onFilter = {
                        folderMenuState.dismiss()
                        favoritesViewModel.onIntent(FavoritesIntent.FilterOpened)
                    },
                )

                FavoritesToolbarMode.SEARCH -> FavoritesSearchGlassContent(
                    value = state.filter.searchText,
                    surfaceAlpha = searchSurfaceAlpha,
                    onValueChange = { query ->
                        favoritesViewModel.onIntent(FavoritesIntent.SearchChanged(query))
                    },
                    onClear = {
                        favoritesViewModel.onIntent(FavoritesIntent.SearchChanged(""))
                    },
                    onBack = ::exitSearch,
                )

                FavoritesToolbarMode.SELECTION -> FavoritesSelectionGlassContent(
                    selectedCount = selectedCount,
                    surfaceAlpha = selectionSurfaceAlpha,
                    onClose = {
                        favoritesViewModel.onIntent(FavoritesIntent.SelectionCleared)
                    },
                    onDownload = {
                        favoritesViewModel.onIntent(FavoritesIntent.DownloadSelected)
                    },
                    onMove = {
                        favoritesViewModel.onIntent(FavoritesIntent.MoveSelected)
                    },
                    onDelete = {
                        favoritesViewModel.onIntent(FavoritesIntent.UncollectSelected)
                    },
                )
            }
        }
    }
}

@Composable
private fun FavoritesNormalGlassContent(
    title: String,
    isSyncing: Boolean,
    hasSyncError: Boolean,
    activeFilterCount: Int,
    surfaceAlpha: Float,
    folderMenuState: GlassAnchoredMenuState,
    onSync: () -> Unit,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val trailingWidth = 104.dp
        val centerWidth = (maxWidth - (trailingWidth + 16.dp) * 2)
            .coerceAtLeast(104.dp)
            .coerceAtMost(184.dp)
        GlassSurface(
            surfaceId = "favorites-normal-leading",
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(FavoritesToolbarDefaults.controlHeight),
            style = GlassSurfaceStyle(cornerRadius = 25.dp),
            surfaceAlpha = surfaceAlpha,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FavoriteSyncIconButton(
                    isSyncing = isSyncing,
                    hasError = hasSyncError,
                    onClick = onSync,
                )
            }
        }
        GlassSurface(
            surfaceId = "favorites-normal-center",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(centerWidth)
                .height(FavoritesToolbarDefaults.controlHeight),
            style = GlassSurfaceStyle(cornerRadius = 25.dp),
            surfaceAlpha = surfaceAlpha,
        ) {
            FavoritesGlassFolderTitle(
                title = title,
                menuState = folderMenuState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            )
        }
        GlassSurface(
            surfaceId = "favorites-normal-trailing",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(trailingWidth)
                .height(FavoritesToolbarDefaults.controlHeight),
            style = GlassSurfaceStyle(cornerRadius = 25.dp),
            surfaceAlpha = surfaceAlpha,
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSearch, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索收藏")
                }
                IconButton(onClick = onFilter, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Rounded.FilterList,
                        contentDescription = "筛选",
                        tint = if (activeFilterCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoritesSearchGlassContent(
    value: String,
    surfaceAlpha: Float,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        GlassSurface(
            surfaceId = "favorites-search-leading",
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(FavoritesToolbarDefaults.controlHeight),
            style = GlassSurfaceStyle(cornerRadius = 25.dp),
            surfaceAlpha = surfaceAlpha,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "退出搜索")
            }
        }
        GlassSurface(
            surfaceId = "favorites-search-field",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width((maxWidth - 62.dp).coerceAtLeast(120.dp))
                .height(FavoritesToolbarDefaults.controlHeight),
            style = GlassSurfaceStyle(cornerRadius = 25.dp),
            surfaceAlpha = surfaceAlpha,
        ) {
            FavoritesSearchField(
                value = value,
                onValueChange = onValueChange,
                onClear = onClear,
                requestInitialFocus = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun FavoritesSelectionGlassContent(
    selectedCount: Int,
    surfaceAlpha: Float,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val trailingWidth = 120.dp
        val centerWidth = (maxWidth - (trailingWidth + 12.dp) * 2)
            .coerceAtLeast(92.dp)
            .coerceAtMost(166.dp)
        GlassSurface(
            surfaceId = "favorites-selection-leading",
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(FavoritesToolbarDefaults.controlHeight),
            style = GlassSurfaceStyle(cornerRadius = 25.dp),
            surfaceAlpha = surfaceAlpha,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Rounded.Close, contentDescription = "退出选择")
            }
        }
        GlassSurface(
            surfaceId = "favorites-selection-center",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(centerWidth)
                .height(FavoritesToolbarDefaults.controlHeight),
            style = GlassSurfaceStyle(cornerRadius = 25.dp),
            surfaceAlpha = surfaceAlpha,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "已选择 $selectedCount 项",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        GlassSurface(
            surfaceId = "favorites-selection-trailing",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(trailingWidth)
                .height(FavoritesToolbarDefaults.controlHeight),
            style = GlassSurfaceStyle(cornerRadius = 25.dp),
            surfaceAlpha = surfaceAlpha,
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDownload, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Download, contentDescription = "下载")
                }
                IconButton(onClick = onMove, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = "移动")
                }
                IconButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "取消收藏",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoritesMaterialTopBar(
    favoritesViewModel: FavoritesViewModel,
    onNavigateBack: (() -> Unit)? = null,
) {
    val state by favoritesViewModel.uiState.collectAsState()
    val selectedFolderId = state.selectedFolderId
    val selectedCount = state.selection.selectedComicIds.size
    val mode = resolveFavoritesToolbarMode(state.searchActive, selectedCount)
    val keyboardController = LocalSoftwareKeyboardController.current
    val activeFilterCount = state.filter.selectedTags.size + state.filter.selectedAuthors.size

    fun exitSearch() {
        favoritesViewModel.onIntent(FavoritesIntent.SearchExited)
        keyboardController?.hide()
    }

    LaunchedEffect(selectedCount) {
        if (selectedCount > 0 && state.searchActive) exitSearch()
    }
    BackHandler(enabled = mode != FavoritesToolbarMode.NORMAL) {
        when (mode) {
            FavoritesToolbarMode.SEARCH -> exitSearch()
            FavoritesToolbarMode.SELECTION -> favoritesViewModel.onIntent(FavoritesIntent.SelectionCleared)
            FavoritesToolbarMode.NORMAL -> Unit
        }
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        navigationIcon = {
            when {
                mode == FavoritesToolbarMode.SEARCH -> IconButton(onClick = ::exitSearch) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "退出搜索")
                }
                mode == FavoritesToolbarMode.SELECTION -> IconButton(onClick = {
                    favoritesViewModel.onIntent(FavoritesIntent.SelectionCleared)
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = "退出选择")
                }
                onNavigateBack != null -> BackIconButton(onClick = onNavigateBack)
            }
        },
        title = {
            when (mode) {
                FavoritesToolbarMode.NORMAL -> FavoritesMaterialFolderTitle(
                    title = resolveFavoriteFolderTitle(selectedFolderId, state.folders),
                    selectedFolderId = selectedFolderId,
                    folderList = state.folders,
                    onFolderSelected = { folderId ->
                        favoritesViewModel.onIntent(FavoritesIntent.SelectionCleared)
                        exitSearch()
                        favoritesViewModel.onIntent(FavoritesIntent.FolderSelected(folderId))
                    },
                    onManageFolders = {
                        favoritesViewModel.onIntent(FavoritesIntent.FolderManagementOpened)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                FavoritesToolbarMode.SEARCH -> FavoritesSearchField(
                    value = state.filter.searchText,
                    onValueChange = { query ->
                        favoritesViewModel.onIntent(FavoritesIntent.SearchChanged(query))
                    },
                    onClear = {
                        favoritesViewModel.onIntent(FavoritesIntent.SearchChanged(""))
                    },
                    requestInitialFocus = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                )
                FavoritesToolbarMode.SELECTION -> Text("已选择 $selectedCount 项")
            }
        },
        actions = {
            when (mode) {
                FavoritesToolbarMode.NORMAL -> {
                    FavoriteSyncIconButton(
                        isSyncing = state.sync.isSyncing,
                        hasError = state.sync.errorMessage != null,
                        onClick = {
                            favoritesViewModel.onIntent(FavoritesIntent.ManualSync)
                        },
                    )
                    IconButton(onClick = {
                        favoritesViewModel.onIntent(FavoritesIntent.SearchEntered)
                    }) {
                        Icon(Icons.Rounded.Search, contentDescription = "搜索收藏")
                    }
                    IconButton(onClick = {
                        favoritesViewModel.onIntent(FavoritesIntent.FilterOpened)
                    }) {
                        Icon(
                            Icons.Rounded.FilterList,
                            contentDescription = "筛选",
                            tint = if (activeFilterCount > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                FavoritesToolbarMode.SEARCH -> Unit
                FavoritesToolbarMode.SELECTION -> {
                    IconButton(onClick = {
                        favoritesViewModel.onIntent(FavoritesIntent.DownloadSelected)
                    }) {
                        Icon(Icons.Rounded.Download, contentDescription = "下载")
                    }
                    IconButton(onClick = {
                        favoritesViewModel.onIntent(FavoritesIntent.MoveSelected)
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = "移动")
                    }
                    IconButton(onClick = {
                        favoritesViewModel.onIntent(FavoritesIntent.UncollectSelected)
                    }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "取消收藏",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
    )
}

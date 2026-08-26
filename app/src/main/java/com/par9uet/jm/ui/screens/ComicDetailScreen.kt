package com.par9uet.jm.ui.screens

import androidx.activity.compose.BackHandler
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.Comment
import com.par9uet.jm.storage.ComicReadHistory
import com.par9uet.jm.store.DownloadManager
import com.par9uet.jm.store.ReadHistoryManager
import com.par9uet.jm.store.SessionReadiness
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.ui.components.ChapterMultiSelectDialog
import com.par9uet.jm.ui.components.ComicContentTag
import com.par9uet.jm.ui.components.ComicCoverImage
import com.par9uet.jm.ui.components.ComicRoleTag
import com.par9uet.jm.ui.components.ComicWorkTag
import com.par9uet.jm.ui.glass.AppGlassTopBar
import com.par9uet.jm.ui.glass.AppGlassTopBarDefaults
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.glass.GlassModal
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle
import com.par9uet.jm.ui.viewModel.ComicDetailViewModel
import com.par9uet.jm.utils.shimmer
import androidx.paging.compose.collectAsLazyPagingItems
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

internal val ComicDetailHorizontalPadding = 10.dp

@Composable
private fun ComicInfoListItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AssistChip(
            border = null,
            modifier = Modifier
                .width(50.dp)
                .height(50.dp),
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            onClick = {},
            label = {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ComicDetailSkeleton(topContentPadding: Dp) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(top = topContentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .shimmer()
        )
        Column(
            modifier = Modifier.padding(horizontal = ComicDetailHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(36.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .shimmer()
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(34.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .shimmer()
            )
        }
    }
}

@Composable
private fun ComicDetailErrorPage(
    errorMessage: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "加载失败",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = errorMessage ?: "加载失败，请稍后重试",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRetry) {
                Text("重试")
            }
            TextButton(onClick = onBack) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun ComicMetadataContent(
    comic: Comic,
    onTagSearch: (String) -> Unit,
) {
    Text(
        modifier = Modifier.padding(top = 10.dp),
        text = comic.name,
        style = MaterialTheme.typography.titleLarge,
        lineHeight = 1.5.em,
        fontWeight = FontWeight.Bold,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        comic.authorList.forEach {
            key(it) {
                Text(
                    modifier = Modifier.clickable(onClick = { onTagSearch(it) }),
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ComicInfoListItem(
            modifier = Modifier.weight(.5f),
            icon = Icons.Default.Favorite,
            label = "\u559c\u6b22",
            value = comic.likeCount.toString()
        )
        ComicInfoListItem(
            modifier = Modifier.weight(.5f),
            icon = Icons.Default.RemoveRedEye,
            label = "\u6d4f\u89c8",
            value = comic.readCount.toString()
        )
    }
    if (comic.tagList.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            comic.tagList.forEach {
                key(it) {
                    ComicContentTag(it)
                }
            }
        }
    }
    if (comic.roleList.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            comic.roleList.forEach {
                key(it) {
                    ComicRoleTag(it)
                }
            }
        }
    }
    if (comic.workList.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            comic.workList.forEach {
                key(it) {
                    ComicWorkTag(it)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicDetailScreen(
    id: Int,
    comicDetailViewModel: ComicDetailViewModel = koinActivityViewModel(),
    readHistoryManager: ReadHistoryManager = getKoin().get(),
    downloadManager: DownloadManager = getKoin().get(),
    userManager: UserManager = getKoin().get()
) {
    val mainNavController = LocalMainNavController.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scrollState = rememberScrollState()
    val comicDetailState by comicDetailViewModel.comicDetailState.collectAsState()
    // The activity-scoped ViewModel can still hold another comic for one composition frame while
    // a direct route change is starting. Never render a seed or toolbar title for that old id.
    val requestedComic = comicDetailState.data?.takeIf { it.id == id }
    val readHistory by readHistoryManager.readHistoryState.collectAsState()
    val authState by userManager.authState.collectAsState()
    val commentLazyPagingItems = comicDetailViewModel.commentPager.collectAsLazyPagingItems()
    val commentInputFocusRequester = remember { FocusRequester() }
    var showDownloadChapterDialog by remember { mutableStateOf(false) }
    var selectedChapterIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var replyComment by remember(id) { mutableStateOf<Comment?>(null) }
    var detailBottomState by remember(id) { mutableStateOf(DetailBottomModeState()) }
    var commentFocusRequestTick by remember(id) { mutableIntStateOf(0) }

    fun enterCommentMode() {
        replyComment = null
        detailBottomState = detailBottomState.enterComment()
        commentFocusRequestTick++
    }

    fun enterReplyMode(comment: Comment) {
        replyComment = comment
        detailBottomState = detailBottomState.enterReply(comment.id)
        commentFocusRequestTick++
    }

    fun exitCommentMode() {
        replyComment = null
        detailBottomState = detailBottomState.cancel()
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun requireLogin(action: () -> Unit) {
        if (authState == SessionReadiness.Unauthenticated) {
            mainNavController.navigate("login")
        } else {
            action()
        }
    }

    fun searchTag(tag: String) {
        mainNavController.navigate("comicSearchResult/${Uri.encode(tag)}")
    }

    LaunchedEffect(id) {
        comicDetailViewModel.changeCommentComicId(id)
        if (comicDetailState.data?.id != id) {
            comicDetailViewModel.getComicDetail(id)
        }
    }
    val navigationBarInset = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    val statusBarInset = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp()
    }
    val detailTopContentPadding = statusBarInset + AppGlassTopBarDefaults.ContentHeight
    val detailBarHeight = 64.dp
    val detailBarBottomPadding = 8.dp + navigationBarInset
    val detailContentBottomPadding = detailBarHeight + detailBarBottomPadding

    // COMMENT mode consumes Back so it never pops ComicDetail; ACTIONS falls through to
    // normal navigation Back behavior.
    BackHandler(enabled = detailBottomState.mode == DetailBottomMode.COMMENT) {
        exitCommentMode()
    }
    LaunchedEffect(detailBottomState.mode, commentFocusRequestTick) {
        if (detailBottomState.mode == DetailBottomMode.COMMENT) {
            keyboardController?.show()
            commentInputFocusRequester.requestFocus()
        }
    }

    GlassCaptureHost(
        modifier = Modifier.fillMaxSize(),
        sourceContent = {
            when {
                comicDetailState.isError && comicDetailState.data == null -> {
                    ComicDetailErrorPage(
                        errorMessage = comicDetailState.errorMsg,
                        onRetry = { comicDetailViewModel.getComicDetail(id) },
                        onBack = { mainNavController.popBackStack() },
                        modifier = Modifier.padding(top = detailTopContentPadding),
                    )
                }
                requestedComic == null -> {
                    ComicDetailSkeleton(topContentPadding = detailTopContentPadding)
                }
                else -> {
                    val comic = requestedComic
                        if (showDownloadChapterDialog) {
                            ChapterMultiSelectDialog(
                                title = "\u9009\u62e9\u7f13\u5b58\u7ae0\u8282",
                                chapters = comic.comicChapterList,
                                selectedChapterIds = selectedChapterIds,
                                onSelectedChange = { selectedChapterIds = it },
                                onDismiss = { showDownloadChapterDialog = false },
                                confirmText = "\u5f00\u59cb\u7f13\u5b58",
                                onConfirm = {
                                    val selectedChapters = comic.comicChapterList.filter {
                                        it.id in selectedChapterIds
                                    }
                                    downloadManager.downloadChapters(comic, selectedChapters)
                                    showDownloadChapterDialog = false
                                },
                            )
                        }

                        PullToRefreshBox(
                            isRefreshing = comicDetailState.isLoading,
                            state = rememberPullToRefreshState(),
                            onRefresh = { comicDetailViewModel.getComicDetail(id) },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val isTabletLayout = maxWidth >= 700.dp
                                val viewportHeight = maxHeight
                                if (isTabletLayout) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                start = 16.dp,
                                                top = detailTopContentPadding + 16.dp,
                                                end = 16.dp,
                                                bottom = 16.dp,
                                            ),
                                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                                    ) {
                                        ComicCoverImage(
                                            comic = comic,
                                            modifier = Modifier
                                                .widthIn(max = 320.dp)
                                                .weight(0.42f),
                                            showIdChip = true,
                                        )
                                        Column(
                                            modifier = Modifier
                                                .weight(0.58f)
                                                .verticalScroll(scrollState)
                                                .padding(horizontal = ComicDetailHorizontalPadding)
                                                .padding(bottom = detailContentBottomPadding),
                                            verticalArrangement = Arrangement.spacedBy(16.dp),
                                        ) {
                                            ComicMetadataContent(comic, ::searchTag)
                                            ComicCommentContent(
                                                commentLazyPagingItems = commentLazyPagingItems,
                                                authState = authState,
                                                onLogin = { mainNavController.navigate("login") },
                                                onReply = ::enterReplyMode,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(viewportHeight),
                                                listBottomPadding = detailBarHeight + detailBarBottomPadding + 100.dp,
                                            )
                                        }
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(scrollState)
                                            .padding(
                                                top = detailTopContentPadding,
                                                bottom = detailContentBottomPadding,
                                            ),
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        ComicCoverImage(comic = comic, showIdChip = true)
                                        Column(
                                            modifier = Modifier.padding(horizontal = ComicDetailHorizontalPadding),
                                            verticalArrangement = Arrangement.spacedBy(16.dp),
                                        ) {
                                            ComicMetadataContent(comic, ::searchTag)
                                            ComicCommentContent(
                                                commentLazyPagingItems = commentLazyPagingItems,
                                                authState = authState,
                                                onLogin = { mainNavController.navigate("login") },
                                                onReply = ::enterReplyMode,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(viewportHeight),
                                                listBottomPadding = detailBarHeight + detailBarBottomPadding + 100.dp,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val showFolderPicker by comicDetailViewModel.showFolderPicker.collectAsState()
                        val folderList by comicDetailViewModel.folderList.collectAsState()
                        if (showFolderPicker) {
                            FolderPickerSheet(
                                comicId = comic.id,
                                folderList = folderList,
                                onSelect = { folderId ->
                                    comicDetailViewModel.collectWithFolder(comic.id, folderId)
                                },
                                onDismiss = comicDetailViewModel::hideFolderPicker,
                            )
                        }
                }
            }
        },
        overlayContent = {
            val comic = requestedComic
            val showFolderPicker by comicDetailViewModel.showFolderPicker.collectAsState()
            val folderList by comicDetailViewModel.folderList.collectAsState()
            Box(modifier = Modifier.fillMaxSize()) {
                AppGlassTopBar(
                    surfaceId = "comic-detail-top-bar",
                    statusBarInset = statusBarInset,
                    modifier = Modifier.align(Alignment.TopCenter),
                    navigationIcon = {
                        IconButton(onClick = { mainNavController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "\u8fd4\u56de",
                            )
                        }
                    },
                    title = {
                        Text(
                            text = comic?.name.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                when (authState) {
                                    SessionReadiness.Authenticated -> enterCommentMode()
                                    SessionReadiness.Unauthenticated ->
                                        mainNavController.navigate("login")
                                    SessionReadiness.Unknown,
                                    SessionReadiness.Restoring -> Unit
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.ChatBubbleOutline,
                                contentDescription = "评论",
                            )
                        }
                    },
                )
                if (comic != null) {
                    AnimatedContent(
                        targetState = detailBottomState.mode,
                        transitionSpec = {
                            (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 14 }) togetherWith
                                (fadeOut(tween(220)) + slideOutHorizontally(tween(220)) { -it / 14 })
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "comic-detail-bottom-mode",
                    ) { targetMode ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = detailBarBottomPadding),
                        ) {
                            when (targetMode) {
                                DetailBottomMode.ACTIONS -> ComicDetailBottomBar(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .widthIn(max = 600.dp)
                                        .height(detailBarHeight),
                                    comic = comic,
                                    readHistoryManager = readHistoryManager,
                                    readHistory = readHistory,
                                    onCollect = {
                                        requireLogin {
                                            if (comic.isCollect) {
                                                comicDetailViewModel.unCollect(comic.id)
                                            } else {
                                                comicDetailViewModel.refreshFolderList()
                                                comicDetailViewModel.showFolderPicker()
                                            }
                                        }
                                    },
                                    onRelated = { mainNavController.navigate("comicRelate") },
                                    onDownload = {
                                        if (comic.comicChapterList.isEmpty()) {
                                            downloadManager.downloadComic(comic)
                                        } else {
                                            selectedChapterIds = comic.comicChapterList.map { it.id }.toSet()
                                            showDownloadChapterDialog = true
                                        }
                                    },
                                    onRead = { targetId -> mainNavController.navigate("comicRead/$targetId") },
                                    onChapters = {
                                        val currentChapterId =
                                            readHistoryManager.lastReadChapterId(comic, readHistory) ?: -1
                                        mainNavController.navigate(
                                            "comicChapter?currentChapterId=$currentChapterId"
                                        )
                                    },
                                )

                                DetailBottomMode.COMMENT -> CommentComposer(
                                    comicId = comic.id,
                                    authState = authState,
                                    replyComment = replyComment,
                                    onCancel = ::exitCommentMode,
                                    commentLazyPagingItems = commentLazyPagingItems,
                                    commentInputFocusRequester = commentInputFocusRequester,
                                    comicDetailViewModel = comicDetailViewModel,
                                    onLogin = { mainNavController.navigate("login") },
                                    onSuccess = ::exitCommentMode,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .widthIn(max = 600.dp)
                                        .padding(horizontal = ComicDetailHorizontalPadding),
                                    surfaceIdPrefix = "comic-detail-comment",
                                )
                            }
                    }
                }
            }
            GlassModal(
                visible = showFolderPicker,
                onDismissRequest = comicDetailViewModel::hideFolderPicker,
                    surfaceId = "comic-detail-folder-picker-glass-modal",
                    modifier = Modifier.widthIn(max = 480.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "选择收藏夹",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                        HorizontalDivider()
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        ) {
                            val sortedFolders = linkedMapOf<String, String>().apply {
                                folderList["0"]?.let { put("0", it) }
                                folderList.filterKeys { it != "0" }.forEach { (fid, fname) -> put(fid, fname) }
                                if (containsKey("0").not() && folderList.isNotEmpty()) {
                                    put("0", "全部")
                                }
                            }
                            items(sortedFolders.size) { index ->
                                val entry = sortedFolders.entries.elementAt(index)
                                ListItem(
                                    headlineContent = { Text(entry.value) },
                                    modifier = Modifier.clickable {
                                        val currentComicId = comic?.id ?: -1
                                        if (currentComicId > 0) {
                                            comicDetailViewModel.collectWithFolder(currentComicId, entry.key)
                                            comicDetailViewModel.hideFolderPicker()
                                        }
                                        comicDetailViewModel.hideFolderPicker()
                                    },
                                )
                                if (index < sortedFolders.size - 1) {
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPickerSheet(
    comicId: Int,
    folderList: Map<String, String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = "\u9009\u62e9\u6536\u85cf\u5939",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f)
        ) {
            // "0"（全部）排第一，其余按原序
            val sortedFolders = linkedMapOf<String, String>().apply {
                folderList["0"]?.let { put("0", it) }
                folderList.filterKeys { it != "0" }.forEach { (id, name) -> put(id, name) }
                if (containsKey("0").not() && folderList.isNotEmpty()) {
                    put("0", "\u5168\u90e8")
                }
            }
            items(sortedFolders.size) { index ->
                val entry = sortedFolders.entries.elementAt(index)
                ListItem(
                    headlineContent = { Text(entry.value) },
                    modifier = Modifier.clickable { onSelect(entry.key) }
                )
                if (index < sortedFolders.size - 1) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ComicDetailBottomBar(
    modifier: Modifier = Modifier,
    comic: Comic,
    readHistoryManager: ReadHistoryManager,
    readHistory: Map<Int, ComicReadHistory>,
    onCollect: () -> Unit,
    onRelated: () -> Unit,
    onDownload: () -> Unit,
    onRead: (Int) -> Unit,
    onChapters: () -> Unit,
) {
    val lastReadChapterId = readHistoryManager.lastReadChapterId(comic, readHistory)
    val hasChapters = comic.comicChapterList.isNotEmpty()
    val readTargetId = lastReadChapterId
        ?: comic.comicChapterList.firstOrNull()?.id
        ?: comic.id

    GlassSurface(
        surfaceId = "comic-detail-actions",
        modifier = modifier,
        style = GlassSurfaceStyle(cornerRadius = 32.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val iconCellSize = 48.dp
            val readButtonWidth = (
                maxWidth - 16.dp - iconCellSize * 4
            ).coerceAtLeast(100.dp)

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    modifier = Modifier
                        .width(readButtonWidth)
                        .height(48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    onClick = { onRead(readTargetId) },
                    shape = CircleShape,
                ) {
                    Text(if (lastReadChapterId != null) "\u7ee7\u7eed\u9605\u8bfb" else "\u9605\u8bfb")
                }

                DetailIconAction(
                    icon = if (comic.isCollect) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (comic.isCollect) "\u5df2\u6536\u85cf" else "\u6536\u85cf",
                    tint = if (comic.isCollect) MaterialTheme.colorScheme.tertiary else null,
                    size = iconCellSize,
                    onClick = onCollect,
                )
                DetailIconAction(
                    icon = Icons.Default.AutoAwesome,
                    contentDescription = "\u76f8\u5173",
                    size = iconCellSize,
                    onClick = onRelated,
                )
                DetailIconAction(
                    icon = Icons.Default.Download,
                    contentDescription = "\u7f13\u5b58",
                    size = iconCellSize,
                    onClick = onDownload,
                )
                DetailIconAction(
                    icon = Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = "\u7ae0\u8282",
                    enabled = hasChapters,
                    size = iconCellSize,
                    onClick = onChapters,
                )
            }
        }
    }
}

@Composable
private fun DetailIconAction(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color? = null,
    size: Dp,
    onClick: () -> Unit,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        tint != null -> tint
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        modifier = Modifier.size(size),
        enabled = enabled,
        onClick = onClick,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
        )
    }
}

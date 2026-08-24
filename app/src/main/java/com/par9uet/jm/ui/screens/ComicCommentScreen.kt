package com.par9uet.jm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.par9uet.jm.data.models.Comment
import com.par9uet.jm.store.SessionReadiness
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.ui.components.Comment
import com.par9uet.jm.ui.components.CommentSkeleton
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.glass.GlassMaterialStyle
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle
import com.par9uet.jm.ui.viewModel.ComicDetailViewModel
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
private fun CommentListSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(6) {
            key(it) { CommentSkeleton() }
        }
    }
}

@Composable
private fun ReplyComment(
    comment: Comment,
    onReply: () -> Unit,
) {
    val annotatedString = buildAnnotatedString {
        withStyle(
            SpanStyle(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        ) {
            append(comment.username)
        }
        append(": ")
        append(AnnotatedString.fromHtml(htmlString = comment.content).trim())
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = annotatedString,
            softWrap = true,
            fontSize = 12.sp,
        )
        TextButton(
            modifier = Modifier.height(28.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            onClick = onReply,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Reply,
                contentDescription = "回复 ${comment.username}",
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text("回复", fontSize = 11.sp)
        }
    }
}

@Composable
private fun CommentWithAction(
    comment: Comment,
    onReply: (Comment) -> Unit,
) {
    var repliesExpanded by remember { mutableStateOf(false) }
    val replyCount = comment.replyCommentList.size

    Comment(comment) {
        Column {
            TextButton(
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(0.dp),
                onClick = { onReply(comment) },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Reply,
                    contentDescription = "回复",
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("回复", fontSize = 12.sp)
            }
            if (replyCount > 0) {
                TextButton(
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(0.dp),
                    onClick = { repliesExpanded = !repliesExpanded },
                ) {
                    Icon(
                        imageVector = if (repliesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (repliesExpanded) "收起回复" else "展开 $replyCount 条回复",
                        fontSize = 12.sp,
                    )
                }
                if (repliesExpanded) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            comment.replyCommentList.forEachIndexed { index, reply ->
                                key(reply.id) {
                                    ReplyComment(reply) { onReply(reply) }
                                    if (index < comment.replyCommentList.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 4.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant,
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

@Composable
internal fun ComicCommentContent(
    commentLazyPagingItems: LazyPagingItems<Comment>,
    authState: SessionReadiness,
    onLogin: () -> Unit,
    onReply: (Comment) -> Unit,
    modifier: Modifier = Modifier,
    listBottomPadding: Dp = 10.dp,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "评论",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        HorizontalDivider()
        CommentList(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            commentLazyPagingItems = commentLazyPagingItems,
            authState = authState,
            onLogin = onLogin,
            onReply = onReply,
            bottomContentPadding = listBottomPadding,
        )
    }
}

@Composable
private fun CommentList(
    modifier: Modifier,
    commentLazyPagingItems: LazyPagingItems<Comment>,
    authState: SessionReadiness,
    onLogin: () -> Unit,
    onReply: (Comment) -> Unit,
    bottomContentPadding: Dp,
) {
    when (val refreshState = commentLazyPagingItems.loadState.refresh) {
        is LoadState.Loading -> {
            if (commentLazyPagingItems.itemCount == 0) {
                CommentListSkeleton(modifier)
                return
            }
        }

        is LoadState.Error -> {
            if (commentLazyPagingItems.itemCount == 0) {
                Column(
                    modifier = modifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = refreshState.error.message ?: "评论加载失败",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = commentLazyPagingItems::retry) { Text("重试") }
                }
                return
            }
        }

        is LoadState.NotLoading -> Unit
    }

    PullRefreshAndLoadMoreGrid(
        modifier = modifier,
        lazyPagingItems = commentLazyPagingItems,
        key = { it.id },
        columns = GridCells.Fixed(1),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomContentPadding),
        enablePullRefresh = false,
    ) { comment ->
        CommentWithAction(comment) { target ->
            when (authState) {
                SessionReadiness.Authenticated -> onReply(target)
                SessionReadiness.Unauthenticated -> onLogin()
                SessionReadiness.Unknown,
                SessionReadiness.Restoring -> Unit
            }
        }
    }
}

@Composable
internal fun CommentComposer(
    comicId: Int,
    authState: SessionReadiness,
    replyComment: Comment?,
    onReplyCancel: () -> Unit,
    commentLazyPagingItems: LazyPagingItems<Comment>,
    commentInputFocusRequester: FocusRequester,
    comicDetailViewModel: ComicDetailViewModel,
    onLogin: () -> Unit,
    onSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    glassSurfaceId: String? = null,
    onFocusedChange: (Boolean) -> Unit = {},
) {
    val composerContent: @Composable () -> Unit = {
        when (authState) {
            SessionReadiness.Unknown,
            SessionReadiness.Restoring -> RestoringCommentComposer()

            SessionReadiness.Unauthenticated -> LoggedOutCommentComposer(onLogin)
            SessionReadiness.Authenticated -> AuthenticatedCommentComposer(
                comicId = comicId,
                replyComment = replyComment,
                onReplyCancel = onReplyCancel,
                commentLazyPagingItems = commentLazyPagingItems,
                commentInputFocusRequester = commentInputFocusRequester,
                comicDetailViewModel = comicDetailViewModel,
                onSuccess = onSuccess,
                onFocusedChange = onFocusedChange,
            )
        }
    }

    Box(modifier = modifier.imePadding()) {
        if (glassSurfaceId != null) {
            GlassSurface(
                surfaceId = glassSurfaceId,
                modifier = Modifier.fillMaxWidth(),
                style = GlassSurfaceStyle(
                    cornerRadius = 28.dp,
                    material = GlassMaterialStyle.Default,
                ),
            ) {
                composerContent()
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                composerContent()
            }
        }
    }
}

@Composable
private fun RestoringCommentComposer() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            "正在恢复登录状态",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoggedOutCommentComposer(onLogin: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "登录后发表评论",
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onLogin) { Text("登录") }
    }
}

@Composable
private fun AuthenticatedCommentComposer(
    comicId: Int,
    replyComment: Comment?,
    onReplyCancel: () -> Unit,
    commentLazyPagingItems: LazyPagingItems<Comment>,
    commentInputFocusRequester: FocusRequester,
    comicDetailViewModel: ComicDetailViewModel,
    onSuccess: () -> Unit,
    onFocusedChange: (Boolean) -> Unit,
) {
    val textFieldState = rememberTextFieldState()
    val commentComicState by comicDetailViewModel.commentComicState.collectAsState()
    LaunchedEffect(replyComment?.id) {
        if (replyComment != null) commentInputFocusRequester.requestFocus()
    }

    fun submit() {
        val content = textFieldState.text.toString().trim()
        if (content.isBlank() || commentComicState.isLoading) return
        comicDetailViewModel.comment(content, comicId, replyComment?.id) {
            textFieldState.edit { replace(0, length, "") }
            commentLazyPagingItems.refresh()
            onSuccess()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 80.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(commentInputFocusRequester)
                    .onFocusChanged { onFocusedChange(it.isFocused) },
                state = textFieldState,
                placeholder = {
                    Text(if (replyComment == null) "发表评论" else "回复 ${replyComment.username}")
                },
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                onKeyboardAction = { submit() },
            )
            if (replyComment != null) {
                IconButton(onClick = onReplyCancel) {
                    Icon(Icons.Default.Close, contentDescription = "取消回复")
                }
            }
            IconButton(enabled = !commentComicState.isLoading, onClick = ::submit) {
                if (commentComicState.isLoading) {
                    CircularProgressIndicator(
                        color = ButtonDefaults.buttonColors().disabledContainerColor,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送")
                }
            }
        }
        val errorMessage = commentComicState.errorMsg.orEmpty()
        if (commentComicState.isError && errorMessage.isNotBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
fun ComicCommentScreen(
    comicId: Int,
    comicDetailViewModel: ComicDetailViewModel = koinActivityViewModel(),
    userManager: UserManager = getKoin().get(),
) {
    val focusManager = LocalFocusManager.current
    val mainNavController = LocalMainNavController.current
    val authState by userManager.authState.collectAsState()
    val commentInputFocusRequester = remember { FocusRequester() }
    val commentLazyPagingItems = comicDetailViewModel.commentPager.collectAsLazyPagingItems()
    var replyComment by remember(comicId) { mutableStateOf<Comment?>(null) }
    val comicDetailState by comicDetailViewModel.comicDetailState.collectAsState()

    LaunchedEffect(comicId) {
        comicDetailViewModel.changeCommentComicId(comicId)
        if (comicDetailState.data?.id != comicId) {
            comicDetailViewModel.getComicDetail(comicId)
        }
    }
    LaunchedEffect(authState) {
        if (authState == SessionReadiness.Unauthenticated) {
            mainNavController.navigate("login")
        }
    }

    val comicTitle = comicDetailState.data?.let { "${it.name} · JM${it.id}" } ?: "评论"
    CommonScaffold(
        title = comicTitle,
        bottomBar = {
            CommentComposer(
                comicId = comicId,
                authState = authState,
                replyComment = replyComment,
                onReplyCancel = { replyComment = null },
                commentLazyPagingItems = commentLazyPagingItems,
                commentInputFocusRequester = commentInputFocusRequester,
                comicDetailViewModel = comicDetailViewModel,
                onLogin = { mainNavController.navigate("login") },
                onSuccess = {
                    replyComment = null
                    focusManager.clearFocus()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        ComicCommentContent(
            commentLazyPagingItems = commentLazyPagingItems,
            authState = authState,
            onLogin = { mainNavController.navigate("login") },
            onReply = {
                replyComment = it
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ComicDetailHorizontalPadding),
        )
    }
}

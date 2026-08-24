package com.par9uet.jm.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
    val expandRotation by animateFloatAsState(
        targetValue = if (repliesExpanded) 180f else 0f,
        animationSpec = tween(200),
        label = "comment-replies-expand-icon",
    )

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
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(expandRotation),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (repliesExpanded) "收起回复" else "展开 $replyCount 条回复",
                        fontSize = 12.sp,
                    )
                }
                AnimatedVisibility(
                    visible = repliesExpanded,
                    enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(160)) + fadeOut(tween(160)),
                ) {
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
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
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
            bottomContentPadding = listBottomPadding + bottomContentPadding,
            topContentPadding = topContentPadding,
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
    topContentPadding: Dp = 0.dp,
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
        contentPadding = PaddingValues(
            top = topContentPadding + 10.dp,
            bottom = bottomContentPadding,
        ),
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

internal val CommentComposerControlHeight = 50.dp
internal val CommentComposerControlSpacing = 8.dp

/**
 * Shared comment composer primitives: an input capsule plus independent Cancel / Send circles,
 * all with the same fixed control height and one visual centerline. When [surfaceIdPrefix] is
 * provided the controls render as real glass surfaces (page owns a GlassCaptureHost); otherwise
 * the same geometry falls back to themed Material surfaces.
 */
@Composable
internal fun CommentComposer(
    comicId: Int,
    authState: SessionReadiness,
    replyComment: Comment?,
    onCancel: () -> Unit,
    commentLazyPagingItems: LazyPagingItems<Comment>,
    commentInputFocusRequester: FocusRequester,
    comicDetailViewModel: ComicDetailViewModel,
    onLogin: () -> Unit,
    onSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceIdPrefix: String? = null,
) {
    Box(modifier = modifier.imePadding()) {
        when (authState) {
            SessionReadiness.Unknown,
            SessionReadiness.Restoring -> CommentStatusCapsule(surfaceIdPrefix) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "正在恢复登录状态",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SessionReadiness.Unauthenticated -> CommentStatusCapsule(surfaceIdPrefix) {
                Text(
                    text = "登录后发表评论",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onLogin) { Text("登录") }
            }

            SessionReadiness.Authenticated -> AuthenticatedCommentComposer(
                comicId = comicId,
                replyComment = replyComment,
                onCancel = onCancel,
                commentLazyPagingItems = commentLazyPagingItems,
                commentInputFocusRequester = commentInputFocusRequester,
                comicDetailViewModel = comicDetailViewModel,
                onSuccess = onSuccess,
                surfaceIdPrefix = surfaceIdPrefix,
            )
        }
    }
}

@Composable
private fun CommentStatusCapsule(
    surfaceIdPrefix: String?,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
    if (surfaceIdPrefix != null) {
        GlassSurface(
            surfaceId = "${surfaceIdPrefix}-status",
            modifier = Modifier
                .fillMaxWidth()
                .height(CommentComposerControlHeight),
            style = GlassSurfaceStyle(cornerRadius = 25.dp),
            content = { rowContent() },
        )
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(CommentComposerControlHeight),
            shape = RoundedCornerShape(25.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            content = rowContent,
        )
    }
}

@Composable
private fun AuthenticatedCommentComposer(
    comicId: Int,
    replyComment: Comment?,
    onCancel: () -> Unit,
    commentLazyPagingItems: LazyPagingItems<Comment>,
    commentInputFocusRequester: FocusRequester,
    comicDetailViewModel: ComicDetailViewModel,
    onSuccess: () -> Unit,
    surfaceIdPrefix: String?,
) {
    val textFieldState = rememberTextFieldState()
    val commentComicState by comicDetailViewModel.commentComicState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(replyComment?.id) {
        if (replyComment != null) {
            commentInputFocusRequester.requestFocus()
            keyboardController?.show()
        }
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CommentComposerControlSpacing),
    ) {
        CommentInputCapsule(
            state = textFieldState,
            replyComment = replyComment,
            focusRequester = commentInputFocusRequester,
            onSend = ::submit,
            surfaceIdPrefix = surfaceIdPrefix,
            modifier = Modifier
                .weight(1f)
                .height(CommentComposerControlHeight),
        )
        CommentActionCircle(
            surfaceIdPrefix = surfaceIdPrefix,
            surfaceName = "cancel",
            contentDescription = "取消",
            enabled = !commentComicState.isLoading,
            onClick = onCancel,
            modifier = Modifier.size(CommentComposerControlHeight),
        ) {
            Icon(Icons.Default.Close, contentDescription = null)
        }
        CommentActionCircle(
            surfaceIdPrefix = surfaceIdPrefix,
            surfaceName = "send",
            contentDescription = "发送",
            enabled = !commentComicState.isLoading,
            onClick = ::submit,
            modifier = Modifier.size(CommentComposerControlHeight),
        ) {
            if (commentComicState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                )
            } else {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
            }
        }
    }
}

@Composable
private fun CommentInputCapsule(
    state: TextFieldState,
    replyComment: Comment?,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
    surfaceIdPrefix: String?,
    modifier: Modifier = Modifier,
) {
    val inputContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                state = state,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    // Wrap content height so the single line centers inside the capsule;
                    // the raw foundation text field draws from its own top otherwise.
                    .height(IntrinsicSize.Min)
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                onKeyboardAction = { onSend() },
                lineLimits = TextFieldLineLimits.SingleLine,
            )
            if (state.text.isEmpty()) {
                Text(
                    text = if (replyComment == null) "发表评论" else "回复 ${replyComment.username}",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (surfaceIdPrefix != null) {
        GlassSurface(
            surfaceId = "${surfaceIdPrefix}-input",
            modifier = modifier,
            style = GlassSurfaceStyle(cornerRadius = 25.dp),
            content = { inputContent() },
        )
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(25.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            content = inputContent,
        )
    }
}

@Composable
private fun CommentActionCircle(
    surfaceIdPrefix: String?,
    surfaceName: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val circleContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.38f)
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
    if (surfaceIdPrefix != null) {
        GlassSurface(
            surfaceId = "${surfaceIdPrefix}-${surfaceName}",
            modifier = modifier,
            style = GlassSurfaceStyle(cornerRadius = 25.dp),
            content = { circleContent() },
        )
    } else {
        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            content = circleContent,
        )
    }
}

@Composable
fun ComicCommentScreen(
    comicId: Int,
    comicDetailViewModel: ComicDetailViewModel = koinActivityViewModel(),
    userManager: UserManager = getKoin().get(),
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
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
        titleTopPadding = 8.dp,
        bottomBar = {
            CommentComposer(
                comicId = comicId,
                authState = authState,
                replyComment = replyComment,
                onCancel = {
                    replyComment = null
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
                commentLazyPagingItems = commentLazyPagingItems,
                commentInputFocusRequester = commentInputFocusRequester,
                comicDetailViewModel = comicDetailViewModel,
                onLogin = { mainNavController.navigate("login") },
                onSuccess = {
                    replyComment = null
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ComicDetailHorizontalPadding, vertical = 8.dp),
            )
        },
    ) { topContentPadding, bottomContentPadding ->
        ComicCommentContent(
            commentLazyPagingItems = commentLazyPagingItems,
            authState = authState,
            onLogin = { mainNavController.navigate("login") },
            onReply = {
                replyComment = it
            },
            topContentPadding = topContentPadding,
            bottomContentPadding = bottomContentPadding,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ComicDetailHorizontalPadding),
        )
    }
}

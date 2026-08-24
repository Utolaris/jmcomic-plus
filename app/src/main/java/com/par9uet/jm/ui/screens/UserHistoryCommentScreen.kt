package com.par9uet.jm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.par9uet.jm.ui.components.Comment
import com.par9uet.jm.ui.components.CommentSkeleton
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.viewModel.UserViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
private fun UserHistoryCommentSkeleton() {
    Column(
        modifier = Modifier.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (i in 0 until 10) {
            key(i) {
                CommentSkeleton()
            }
        }
    }
}

@Composable
fun UserHistoryCommentScreen(
    userViewModel: UserViewModel = koinActivityViewModel()
) {
    val historyCommentLazyPagingItems = userViewModel.historyCommentPager.collectAsLazyPagingItems()
    val navController = LocalMainNavController.current

    CommonScaffold(title = "我的评论") { topContentPadding, bottomContentPadding ->
        if (historyCommentLazyPagingItems.loadState.refresh is LoadState.Loading && historyCommentLazyPagingItems.itemCount == 0) {
            Column(modifier = Modifier.padding(top = topContentPadding)) {
                UserHistoryCommentSkeleton()
            }
        } else {
            PullRefreshAndLoadMoreGrid(
                lazyPagingItems = historyCommentLazyPagingItems,
                key = { "${it.comicId}:${it.sourceChapterId}:${it.id}:${it.time}:${it.content.hashCode()}" },
                columns = GridCells.Fixed(1),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = topContentPadding,
                    bottom = bottomContentPadding,
                ),
            ) {
                Comment(
                    comment = it,
                    showSource = true,
                    onClick = if (it.comicId > 0) {
                        { navController.navigate("comicDetail/${it.comicId}") }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

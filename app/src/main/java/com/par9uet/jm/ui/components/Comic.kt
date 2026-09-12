package com.par9uet.jm.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.ui.models.LocalComicDetailOpener
import com.par9uet.jm.ui.navigation.LocalMainNavController

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Comic(
    comic: Comic,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    selected: Boolean = false,
    isScrolling: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onToggleSelected: (() -> Unit)? = null,
) {
    val mainNavController = LocalMainNavController.current
    val detailOpener = LocalComicDetailOpener.current

    Card(
        modifier = modifier.combinedClickable(
            onClick = {
                if (editing && onToggleSelected != null) {
                    onToggleSelected()
                } else {
                    // 预置详情状态由组合根负责：调用方（App）先 seed 再导航，
                    // 组件本身不认识 ViewModel。未提供时退化为直接导航。
                    val opener = detailOpener
                    if (opener != null) {
                        opener.open(comic)
                    } else {
                        mainNavController.navigate("comicDetail/${comic.id}")
                    }
                }
            },
            onLongClick = {
                onLongClick?.invoke()
            }
        ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (editing && selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box {
                ComicCoverImage(comic = comic, isScrolling = isScrolling)
                if (editing && selected) {
                    Checkbox(
                        checked = true,
                        onCheckedChange = null,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                    )
                }
            }
            Text(
                modifier = Modifier
                    .padding(horizontal = 8.dp),
                text = comic.name,
                color = MaterialTheme.colorScheme.onSurface,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                fontSize = 13.sp,
                lineHeight = 16.sp,
            )
            Text(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 8.dp),
                text = comic.authorList.joinToString(",").ifBlank { "暂无作者" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

package com.par9uet.jm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.JmCoverImage
import com.par9uet.jm.ui.models.LocalRemoteImageHost
import com.par9uet.jm.ui.navigation.LocalMainNavController
import com.par9uet.jm.ui.viewModel.ExtractCodeViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.getKoin

/**
 * 提取编码页面
 *
 * 用户粘贴包含数字的文字（如分享文案），自动提取所有数字拼接为漫画编码，
 * 拉取漫画详情后弹窗展示封面/标题/作者/标签，确认后跳转详情页。
 *
 * 本 Screen 只负责输入/剪贴板与渲染；提取与详情拉取由 [ExtractCodeViewModel] 协调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractCodeScreen(
    viewModel: ExtractCodeViewModel = koinViewModel(),
    toastManager: ToastManager = getKoin().get(),
    imageLoader: ImageLoader = getKoin().get(),
) {
    val mainNavController = LocalMainNavController.current
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val remoteImageHost = LocalRemoteImageHost.current

    val uiState by viewModel.uiState.collectAsState()
    val extractedCode = uiState.extractedCode
    val previewComic = uiState.previewComic
    val loading = uiState.loading

    var inputText by remember { mutableStateOf("") }

    CommonScaffold(title = "提取编码") { topContentPadding, bottomContentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    top = topContentPadding + 16.dp,
                    end = 16.dp,
                    bottom = bottomContentPadding + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "粘贴包含数字的文字，自动提取所有数字拼成漫画编码",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                placeholder = { Text("例：加里奥在40岁的时候一拳撩到了8个闯入家中的恐怖分子获得了882万的悬赏金") },
                supportingText = {
                    Text("提取的数字：${extractedCode ?: "—"}")
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        clipboardScope.launch {
                            val clipEntry = clipboard.getClipEntry()
                            val clipText = clipEntry?.clipData?.getItemAt(0)?.text?.toString() ?: ""
                            if (clipText.isNotBlank()) {
                                inputText = clipText
                                viewModel.extractAndFetch(clipText)
                            } else {
                                toastManager.showAsync("剪切板为空")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null)
                    Text("粘贴", modifier = Modifier.padding(start = 4.dp))
                }
                Button(
                    onClick = { viewModel.extractAndFetch(inputText) },
                    enabled = inputText.isNotBlank() && !loading,
                    modifier = Modifier.weight(1f)
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                    }
                    Text("提取", modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            "正在获取漫画详情...",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    // 详情预览弹窗（左侧封面小窗口 + 右侧信息，适配平板）
    val comic = previewComic
    if (comic != null) {
        AlertDialog(
            onDismissRequest = {
                viewModel.dismissPreview()
            },
            title = { Text("找到漫画", fontWeight = FontWeight.Bold) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 左侧封面小窗口
                    JmCoverImage(
                        comicId = comic.id,
                        remoteHost = remoteImageHost,
                        imageLoader = imageLoader,
                        contentDescription = "${comic.name}的封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(96.dp)
                            .height(128.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    // 右侧信息
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // JM 编码
                        AssistChip(
                            onClick = {},
                            label = { Text("JM${comic.id}") }
                        )
                        // 标题
                        Text(
                            text = comic.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        // 作者
                        if (comic.authorList.isNotEmpty()) {
                            Text(
                                text = "作者：${comic.authorList.joinToString("、")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        // 标签
                        if (comic.tagList.isNotEmpty()) {
                            Text(
                                text = "标签：${comic.tagList.take(10).joinToString("、")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissPreview()
                    inputText = ""
                    mainNavController.navigate("comicDetail/${comic.id}")
                }) { Text("跳转详情") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissPreview()
                }) { Text("取消") }
            }
        )
    }
}

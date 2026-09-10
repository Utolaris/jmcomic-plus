package com.par9uet.jm.ui.screens.downloadScreen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.par9uet.jm.store.RemoteConfigPreferences
import com.par9uet.jm.ui.components.ChapterMultiSelectDialog
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.ChapterSingleSelectDialog
import com.par9uet.jm.ui.components.ComicContentTag
import com.par9uet.jm.ui.components.JmCoverImage
import com.par9uet.jm.ui.screens.LocalMainNavController
import com.par9uet.jm.ui.viewModel.DownloadExportViewModel
import com.par9uet.jm.download.export.PdfExportMode
import com.par9uet.jm.ui.viewModel.DownloadComicDetailViewModel
import com.par9uet.jm.utils.formatBytes
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.getKoin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadComicDetailScreen(
    id: Int,
    viewModel: DownloadComicDetailViewModel = koinViewModel(),
    imageLoader: ImageLoader = getKoin().get(),
    remoteConfigPreferences: RemoteConfigPreferences = getKoin().get(),
    exportViewModel: DownloadExportViewModel = koinViewModel(),
) {
    val mainNavController = LocalMainNavController.current
    val detailState by viewModel.detailState.collectAsState()
    val remoteImageHost by remoteConfigPreferences.remoteImageHost.collectAsState()
    val scrollState = rememberScrollState()
    val exportState by exportViewModel.state.collectAsState()
    val cachedInfo = exportState.summary
    val exporting = exportState.exporting
    val selectedExportChapterIds = exportState.selectedChapterIds
    var activeDialog by remember { mutableStateOf<DownloadDetailDialog?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        exportViewModel.exportTo(uri?.toString())
    }

    LaunchedEffect(id) {
        viewModel.load(id)
    }

    LaunchedEffect(detailState.completeItems, detailState.cachePath) {
        exportViewModel.inspect(detailState.completeItems, detailState.cachePath)
    }

    when (activeDialog) {
        DownloadDetailDialog.ReadChapter -> {
            ChapterSingleSelectDialog(
                title = "选择缓存章节",
                chapters = detailState.readableChapters,
                currentChapterId = null,
                onDismiss = { activeDialog = null },
                onSelect = { chapter ->
                    activeDialog = null
                    mainNavController.navigate("localComicRead/${chapter.id}")
                }
            )
        }

        DownloadDetailDialog.ExportChapter -> {
            ChapterMultiSelectDialog(
                title = "选择导出章节",
                chapters = detailState.readableChapters,
                selectedChapterIds = selectedExportChapterIds,
                onSelectedChange = exportViewModel::selectChapters,
                onDismiss = { activeDialog = null },
                confirmText = "合并导出",
                onConfirm = {
                    activeDialog = null
                    if (exportViewModel.prepareExport(detailState.completeItems, PdfExportMode.Merge)) exportLauncher.launch(null)
                },
                secondaryConfirmText = "分章导出",
                onSecondaryConfirm = {
                    activeDialog = null
                    if (exportViewModel.prepareExport(detailState.completeItems, PdfExportMode.SplitByChapter)) exportLauncher.launch(null)
                }
            )
        }

        null -> Unit
    }

    CommonScaffold(
        title = detailState.title.ifBlank { "缓存详情" },
        bottomBar = {
            if (detailState.found && detailState.canRead) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    DownloadDetailBottomActions(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        isMultiChapter = detailState.isMultiChapter,
                        exporting = exporting,
                        onExport = {
                            exportViewModel.selectChapters(detailState.completeItems.map { it.id }.toSet())
                            activeDialog = DownloadDetailDialog.ExportChapter
                        },
                        onSelectChapter = {
                            activeDialog = DownloadDetailDialog.ReadChapter
                        },
                        onRead = {
                            mainNavController.navigate("localComicRead/${detailState.completeItems.first().id}")
                        }
                    )
                }
            }
        }
    ) { topContentPadding, bottomContentPadding ->
        if (!detailState.found) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                if (!detailState.loading) {
                    Text(
                        modifier = Modifier.align(Alignment.Center),
                        text = "未找到缓存详情",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@CommonScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = topContentPadding, bottom = bottomContentPadding)
        ) {
            LocalCover(
                title = detailState.title,
                coverPath = detailState.coverPath,
                remoteHost = remoteImageHost,
                comicId = detailState.remoteCoverComicId,
                imageLoader = imageLoader
            )
            Column(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    modifier = Modifier.padding(top = 10.dp),
                    text = detailState.title,
                    fontSize = 18.sp,
                    lineHeight = 1.5.em,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    detailState.authorList.forEach {
                        key(it) {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 18.sp,
                                lineHeight = 27.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                if (detailState.tagList.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        detailState.tagList.forEach {
                            key(it) {
                                ComicContentTag(it)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CachedInfoItem(
                        modifier = Modifier.weight(0.5f),
                        icon = Icons.Default.DownloadDone,
                        label = "缓存状态",
                        value = detailState.statusSummary
                    )
                    CachedInfoItem(
                        modifier = Modifier.weight(0.5f),
                        icon = Icons.Default.RemoveRedEye,
                        label = "本地阅读",
                        value = if (detailState.canRead) "可用" else "未完成"
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CachedInfoItem(
                        modifier = Modifier.weight(0.5f),
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        label = "缓存章节",
                        value = "${detailState.completeChapterCount} / ${detailState.totalChapterCount}"
                    )
                    CachedInfoItem(
                        modifier = Modifier.weight(0.5f),
                        icon = Icons.Default.Storage,
                        label = "图片数量",
                        value = "${cachedInfo?.imageCount ?: 0} 张"
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CachedInfoItem(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.FolderZip,
                        label = "占用空间",
                        value = formatBytes(cachedInfo?.totalBytes ?: 0L)
                    )
                }
                if (detailState.isDownloading) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = detailState.groupProgress,
                        label = "downloadProgress"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CachedInfoItem(
                                modifier = Modifier.weight(0.5f),
                                icon = Icons.Default.Speed,
                                label = "下载速度",
                                value = formatSpeed(detailState.downloadSpeed)
                            )
                            Text(
                                text = "${(detailState.groupProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (detailState.hasError) {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.retryDownload() },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text("重试下载")
                    }
                }
                Text(
                    text = "缓存时间：${formatTime(detailState.createTime)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "封面：${detailState.coverPath.ifBlank { "无" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "缓存路径：${detailState.cachePath.ifBlank { detailState.zipPath.ifBlank { "无" } }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "阅读和导出都只显示已缓存完成的章节；重复选择已缓存章节时不会重复下载。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private enum class DownloadDetailDialog {
    ReadChapter,
    ExportChapter
}

@Composable
private fun DownloadDetailBottomActions(
    modifier: Modifier,
    isMultiChapter: Boolean,
    exporting: Boolean,
    onExport: () -> Unit,
    onSelectChapter: () -> Unit,
    onRead: () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(
            modifier = Modifier.weight(1f),
            enabled = !exporting,
            contentPadding = PaddingValues(horizontal = 8.dp),
            onClick = onExport
        ) {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(if (exporting) "导出中" else if (isMultiChapter) "导出" else "导出 PDF")
        }
        if (isMultiChapter) {
            Button(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
                onClick = onSelectChapter
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text("章节")
            }
        }
        Button(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp),
            onClick = onRead
        ) {
            Text(if (isMultiChapter) "阅读" else "阅读缓存")
        }
    }
}

@Composable
private fun LocalCover(
    title: String,
    coverPath: String,
    remoteHost: String,
    comicId: Int,
    imageLoader: ImageLoader
) {
    if (coverPath.isNotBlank()) {
        AsyncImage(
            model = coverPath,
            imageLoader = imageLoader,
            contentDescription = "${title}的封面",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
        )
    } else {
        JmCoverImage(
            comicId = comicId,
            remoteHost = remoteHost,
            imageLoader = imageLoader,
            contentDescription = "${title}的封面",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f),
        )
    }
}

@Composable
private fun CachedInfoItem(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary
        )
        Column {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatTime(value: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value))
}

private fun formatSpeed(bytesPerSec: Float): String {
    return when {
        bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576)
        bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024)
        else -> "${bytesPerSec.toInt()} B/s"
    }
}

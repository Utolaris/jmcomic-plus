package com.par9uet.jm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.par9uet.jm.cache.getCommonCacheDir
import com.par9uet.jm.cache.getCommonPicDecodeCacheDir
import com.par9uet.jm.cache.getDownloadDir
import com.par9uet.jm.reader.ReaderImagePipeline
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.glass.GlassConfirmDialog
import com.par9uet.jm.utils.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin
import java.io.File

private data class CacheItem(
    val id: String,
    val icon: ImageVector,
    val title: String,
    val description: String,
    val sizeBytes: Long,
    val dir: File?,
)

@Composable
fun CacheCleanupScreen(
    readerImagePipeline: ReaderImagePipeline = getKoin().get(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var loading by remember { mutableStateOf(true) }
    var cleaning by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var cleanResult by remember { mutableStateOf<String?>(null) }
    val checkedMap = remember { mutableStateMapOf<String, Boolean>() }

    var cacheItems by remember { mutableStateOf<List<CacheItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val items = mutableListOf<CacheItem>()

            val commonCacheDir = getCommonCacheDir(context)
            val commonSize = dirSize(commonCacheDir)
            items.add(
                CacheItem(
                    id = "common",
                    icon = Icons.Default.Cached,
                    title = "图片缓存",
                    description = "Coil 图片加载缓存，清理后图片需重新下载",
                    sizeBytes = commonSize,
                    dir = commonCacheDir
                )
            )

            val downloadDir = getDownloadDir(context)
            val downloadSize = dirSize(downloadDir)
            items.add(
                CacheItem(
                    id = "download",
                    icon = Icons.Default.Folder,
                    title = "漫画缓存",
                    description = "已下载的漫画图片，清理后需重新下载",
                    sizeBytes = downloadSize,
                    dir = downloadDir
                )
            )

            val picDecodeDir = File(context.cacheDir, "pic_decode")
            val picDecodeSize = dirSize(picDecodeDir)
            items.add(
                CacheItem(
                    id = "pic_decode",
                    icon = Icons.Default.BrokenImage,
                    title = "解码缓存",
                    description = "图片解密临时文件，可安全清理",
                    sizeBytes = picDecodeSize,
                    dir = picDecodeDir
                )
            )

            val readerPagesDir = File(context.cacheDir, "reader_pages")
            val readerPagesSize = dirSize(readerPagesDir)
            items.add(
                CacheItem(
                    id = "reader_pages",
                    icon = Icons.Default.Cached,
                    title = "阅读器图片缓存",
                    description = "阅读页的原图与解码缓存，清理后会重新加载",
                    sizeBytes = readerPagesSize,
                    dir = readerPagesDir
                )
            )

            val pdfDir = File(context.cacheDir, "pdf_export")
            val pdfSize = dirSize(pdfDir)
            items.add(
                CacheItem(
                    id = "pdf",
                    icon = Icons.Default.PictureAsPdf,
                    title = "PDF 导出缓存",
                    description = "PDF 导出临时文件，可安全清理",
                    sizeBytes = pdfSize,
                    dir = pdfDir
                )
            )

            val totalAppCache = context.cacheDir
            val totalSize = dirSize(totalAppCache)
            items.add(
                CacheItem(
                    id = "total",
                    icon = Icons.Default.DeleteSweep,
                    title = "全部应用缓存",
                    description = "包含以上所有缓存和其他临时文件",
                    sizeBytes = totalSize,
                    dir = totalAppCache
                )
            )

            cacheItems = items
            loading = false
        }
    }

    val totalSelected = cacheItems.filter { checkedMap[it.id] == true }.sumOf { it.sizeBytes }

    val selectedItems = cacheItems.filter { checkedMap[it.id] == true }

    fun performCacheCleanup(onDone: () -> Unit) {
        scope.launch {
            var freedBytes = 0L
            val effectiveItems = if (selectedItems.any { it.id == "total" }) {
                selectedItems.filter { it.id == "total" }
            } else {
                selectedItems
            }
            withContext(Dispatchers.IO) {
                effectiveItems.forEach { item ->
                    val dir = item.dir
                    freedBytes += dir?.let(::dirSize) ?: 0L
                    when (item.id) {
                        "reader_pages" -> readerImagePipeline.clearDiskCache()
                        "total" -> {
                            readerImagePipeline.clearDiskCache()
                            val readerPagesDir = File(context.cacheDir, "reader_pages")
                            dir?.listFiles().orEmpty()
                                .filterNot { it == readerPagesDir }
                                .forEach { it.deleteRecursively() }
                            readerPagesDir.mkdirs()
                        }
                        else -> dir?.deleteRecursively()
                    }
                }
            }
            selectedItems.forEach { checkedMap[it.id] = false }
            cacheItems = withContext(Dispatchers.IO) {
                cacheItems.map { item ->
                    item.copy(sizeBytes = item.dir?.let(::dirSize) ?: 0L)
                }
            }
            cleaning = false
            cleanResult = "已清理 ${formatBytes(freedBytes)}"
        }
        onDone()
    }

    CommonScaffold(
        title = "缓存清理",
        overlayContent = {
            GlassConfirmDialog(
                visible = showConfirmDialog,
                title = "确认清理",
                message = "将清理 ${selectedItems.size} 项缓存，共 ${formatBytes(totalSelected)}。",
                confirmText = "清理",
                dismissText = "取消",
                destructive = true,
                surfaceId = "cache-cleanup-glass-confirm",
                onDismiss = { showConfirmDialog = false },
                onConfirm = {
                    showConfirmDialog = false
                    cleaning = true
                    performCacheCleanup {}
                },
            )
        },
    ) { topContentPadding, bottomContentPadding ->
        if (loading) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        start = 16.dp,
                        top = topContentPadding + 16.dp,
                        end = 16.dp,
                        bottom = bottomContentPadding + 16.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                cleanResult?.let {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                cacheItems.forEach { item ->
                    val checked = checkedMap[item.id] == true
                    val isTotal = item.id == "total"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isTotal)
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            else
                                MaterialTheme.colorScheme.surfaceContainer
                        ),
                        onClick = { checkedMap[item.id] = !checked }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { checkedMap[item.id] = it }
                            )
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatBytes(item.sizeBytes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                if (totalSelected > 0) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        enabled = !cleaning
                    ) {
                        if (cleaning) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        Text("清理选中项 (${formatBytes(totalSelected)})")
                    }
                }
            }
        }
    }
}

private fun dirSize(dir: File): Long {
    if (!dir.exists()) return 0L
    return dir.walkBottomUp()
        .filter { it.isFile }
        .sumOf { it.length() }
}

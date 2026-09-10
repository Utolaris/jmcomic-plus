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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.glass.GlassConfirmDialog
import com.par9uet.jm.cache.CacheArea
import com.par9uet.jm.ui.viewModel.CacheCleanupViewModel
import org.koin.androidx.compose.koinViewModel
import com.par9uet.jm.utils.formatBytes

@Composable
fun CacheCleanupScreen(
    viewModel: CacheCleanupViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()
    var showConfirmDialog by remember { mutableStateOf(false) }
    val loading = state.loading
    val cleaning = state.cleaning
    val cleanResult = state.result
    val cacheItems = state.items
    val totalSelected = state.selectedBytes
    val selectedItems = state.effectiveSelection

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
                    viewModel.clean()
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
                    val checked = item.area in state.selected
                    val isTotal = item.area == CacheArea.ALL
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isTotal)
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            else
                                MaterialTheme.colorScheme.surfaceContainer
                        ),
                        onClick = { viewModel.select(item.area, !checked) }
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
                                onCheckedChange = { viewModel.select(item.area, it) }
                            )
                            Icon(
                                imageVector = item.area.icon(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.area.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = item.area.description,
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

private fun CacheArea.icon(): ImageVector = when (this) {
    CacheArea.COMMON, CacheArea.READER -> Icons.Default.Cached
    CacheArea.DOWNLOAD -> Icons.Default.Folder
    CacheArea.DECODE -> Icons.Default.BrokenImage
    CacheArea.PDF -> Icons.Default.PictureAsPdf
    CacheArea.ALL -> Icons.Default.DeleteSweep
}

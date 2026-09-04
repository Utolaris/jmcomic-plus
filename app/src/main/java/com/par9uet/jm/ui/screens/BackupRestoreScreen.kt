package com.par9uet.jm.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PATTERN
import com.par9uet.jm.store.BACKUP_PROTECTION_BOTH
import com.par9uet.jm.store.BACKUP_PROTECTION_NONE
import com.par9uet.jm.store.BACKUP_PROTECTION_PASSWORD
import com.par9uet.jm.store.BACKUP_PROTECTION_PATTERN
import com.par9uet.jm.store.BackupContentOptions
import com.par9uet.jm.store.BackupFile
import com.par9uet.jm.store.ComicGroupBackup
import com.par9uet.jm.store.RemoteConfigPreferences
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.JmCoverImage
import com.par9uet.jm.ui.components.SelectDialog
import com.par9uet.jm.ui.components.SelectOption
import com.par9uet.jm.ui.glass.GlassModal
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

import com.par9uet.jm.ui.viewModel.BackupRestoreViewModel
import com.par9uet.jm.ui.viewModel.BackupStep
import com.par9uet.jm.ui.viewModel.RestoreStep
import org.koin.androidx.compose.koinViewModel

private val protectionOptionList = listOf(
    SelectOption("无保护", BACKUP_PROTECTION_NONE),
    SelectOption("仅密码", BACKUP_PROTECTION_PASSWORD),
    SelectOption("仅图案", BACKUP_PROTECTION_PATTERN),
    SelectOption("密码 + 图案", BACKUP_PROTECTION_BOTH),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupRestoreScreen(
    viewModel: BackupRestoreViewModel = koinViewModel(),
    remoteConfigPreferences: RemoteConfigPreferences = getKoin().get(),
) {
    val state by viewModel.state.collectAsState()
    val remoteImageHost by remoteConfigPreferences.remoteImageHost.collectAsState()
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { viewModel.writeDocument(it?.toString()) },
    )
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { viewModel.readDocument(it?.toString()) },
    )
    LaunchedEffect(state.createDocumentName) {
        state.createDocumentName?.let { name ->
            viewModel.documentPickerLaunched()
            createDocumentLauncher.launch(name)
        }
    }
    CommonScaffold(
        title = "数据备份与恢复",
        overlayContent = {
            BackupContentPickerDialog(
                visible = state.backupStep == BackupStep.SelectContent,
                options = state.contentOptions,
                onChange = viewModel::changeContent,
                onConfirm = viewModel::confirmContent,
                onDismiss = viewModel::cancelBackup,
            )
            SelectDialog(
                visible = state.backupStep == BackupStep.SelectProtection,
                title = "选择保护方式",
                value = null,
                modifier = Modifier.widthIn(max = 420.dp),
                selectOptionList = protectionOptionList,
                onSelect = viewModel::selectProtection,
                onDismissRequest = viewModel::cancelBackup,
            )
            SetAppLockPasswordDialog(
                visible = state.backupStep == BackupStep.SetPassword,
                lockType = APP_LOCK_TYPE_PASSWORD,
                passwordLength = 4,
                onConfirm = viewModel::setPassword,
                onDismiss = viewModel::cancelBackup,
            )
            SetAppLockPasswordDialog(
                visible = state.backupStep == BackupStep.SetPattern,
                lockType = APP_LOCK_TYPE_PATTERN,
                onConfirm = viewModel::setPattern,
                onDismiss = viewModel::cancelBackup,
            )
            state.restoreBackup?.let { backup ->
                VerifyPasswordDialog(
                    visible = state.restoreStep == RestoreStep.VerifyPassword,
                    passwordLength = 4,
                    onVerify = viewModel::verifyPassword,
                    onDismiss = viewModel::cancelRestore,
                )
                VerifyPatternDialog(
                    visible = state.restoreStep == RestoreStep.VerifyPattern,
                    onVerify = viewModel::verifyPattern,
                    onDismiss = viewModel::cancelRestore,
                )
                RestoreContentPickerDialog(
                    visible = state.restoreStep == RestoreStep.SelectContent,
                    backup = backup,
                    onConfirm = viewModel::selectRestoreContent,
                    onDismiss = viewModel::cancelRestore,
                )
                ComicCacheRestoreDialog(
                    visible = state.restoreStep == RestoreStep.SelectComicCache,
                    groups = state.restoreGroups,
                    imgHost = remoteImageHost,
                    onConfirm = viewModel::restoreSelected,
                    onSkip = viewModel::skipComicCache,
                    onDismiss = viewModel::cancelRestore,
                )
            }
        },
    ) { topContentPadding, bottomContentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = topContentPadding + 16.dp,
                bottom = bottomContentPadding + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { InfoCard() }
            item {
                ActionCard(
                    icon = Icons.Rounded.CloudUpload,
                    title = "备份数据",
                    description = "选择需要备份的内容（本地设置 / 缓存目录），再选择是否设置密码/图案保护",
                    onClick = viewModel::beginBackup,
                )
            }
            item {
                ActionCard(
                    icon = Icons.Rounded.CloudDownload,
                    title = "恢复数据",
                    description = "从备份文件恢复，可选择需要恢复的内容（不会覆盖当前设备的应用锁状态）",
                    onClick = {
                        if (viewModel.beginRestore()) openDocumentLauncher.launch(arrayOf("application/json"))
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupContentPickerDialog(
    visible: Boolean,
    options: BackupContentOptions,
    onChange: (BackupContentOptions) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        surfaceId = "backup-content-picker-glass-modal",
        modifier = Modifier.widthIn(max = 440.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                Text(
                    text = "选择备份内容",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                ContentToggleRow(
                    icon = Icons.Rounded.Info,
                    title = "本地设置",
                    subtitle = "标签排除、配色方案、推荐方式、网格列数、阅读设置等",
                    checked = options.includeLocalSetting,
                    onCheckedChange = { onChange(options.copy(includeLocalSetting = it)) }
                )
                ContentToggleRow(
                    icon = Icons.Rounded.Book,
                    title = "缓存目录",
                    subtitle = "只备份漫画编号与章节信息，不备份图片文件",
                    checked = options.includeComicCache,
                    onCheckedChange = { onChange(options.copy(includeComicCache = it)) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(modifier = Modifier.size(8.dp))
                    TextButton(onClick = onConfirm) { Text("下一步", fontWeight = FontWeight.Bold) }
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreContentPickerDialog(
    visible: Boolean,
    backup: BackupFile,
    onConfirm: (BackupContentOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var localSettingOn by remember { mutableStateOf(backup.meta.includeLocalSetting) }
    var comicCacheOn by remember { mutableStateOf(backup.meta.includeComicCache) }

    LaunchedEffect(visible) {
        if (visible) {
            localSettingOn = backup.meta.includeLocalSetting
            comicCacheOn = backup.meta.includeComicCache
        }
    }

    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        surfaceId = "restore-content-picker-glass-modal",
        modifier = Modifier.widthIn(max = 440.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                Text(
                    text = "选择恢复内容",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (backup.meta.includeLocalSetting) {
                    ContentToggleRow(
                        icon = Icons.Rounded.Info,
                        title = "本地设置",
                        subtitle = "会覆盖当前本地设置",
                        checked = localSettingOn,
                        onCheckedChange = { localSettingOn = it }
                    )
                }
                if (backup.meta.includeComicCache) {
                    ContentToggleRow(
                        icon = Icons.Rounded.Book,
                        title = "缓存目录",
                        subtitle = "共 ${backup.meta.comicCacheCount} 部漫画，恢复时可选择具体内容",
                        checked = comicCacheOn,
                        onCheckedChange = { comicCacheOn = it }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(modifier = Modifier.size(8.dp))
                    TextButton(onClick = {
                        onConfirm(
                            BackupContentOptions(
                                includeLocalSetting = localSettingOn,
                                includeComicCache = comicCacheOn,
                            )
                        )
                    }) { Text("下一步", fontWeight = FontWeight.Bold) }
                }
        }
    }
}

@Composable
private fun ContentToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainer
    val contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        shape = RoundedCornerShape(16.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.Circle,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}



@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = "备份内容说明",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "备份时可选择：本地设置、缓存目录。\n" +
                        "缓存目录只备份漫画编号与章节信息，不备份图片文件；恢复时可选择具体要重新缓存的漫画。\n" +
                        "为安全考虑，备份不会保存应用锁的密码与图案明文，且恢复时不会覆盖当前设备的应用锁状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyPasswordDialog(
    visible: Boolean,
    passwordLength: Int,
    onVerify: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(visible) {
        if (visible) errorMessage = null
    }

    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        surfaceId = "backup-verify-password-glass-modal",
        modifier = Modifier.widthIn(max = 400.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
                Text(
                    text = "请输入备份密码",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                PasswordLockInput(
                    title = "",
                    correctPassword = null,
                    onUnlock = {},
                    passwordLength = passwordLength,
                    onInputComplete = { pwd ->
                        if (!onVerify(pwd)) {
                            errorMessage = "密码错误，请重试"
                        } else {
                            errorMessage = null
                        }
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyPatternDialog(
    visible: Boolean,
    onVerify: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(visible) {
        if (visible) errorMessage = null
    }

    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        surfaceId = "backup-verify-pattern-glass-modal",
        modifier = Modifier.widthIn(max = 400.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
                Text(
                    text = "请绘制备份图案",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                PatternLockInput(
                    title = "",
                    correctPassword = null,
                    onUnlock = {},
                    onInputComplete = { pattern ->
                        if (!onVerify(pattern)) {
                            errorMessage = "图案错误，请重试"
                        } else {
                            errorMessage = null
                        }
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicCacheRestoreDialog(
    visible: Boolean,
    groups: List<ComicGroupBackup>,
    imgHost: String,
    onConfirm: (List<ComicGroupBackup>) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 默认全部勾选
    val selectedIds = remember(groups) { mutableStateOf(groups.map { it.id }.toSet()) }

    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        surfaceId = "backup-cache-restore-glass-modal",
        modifier = Modifier.widthIn(max = 520.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
                Text(
                    text = "恢复缓存目录",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "共 ${groups.size} 部漫画。勾选需要重新缓存的漫画，未勾选的不会恢复。恢复时会按编号重新创建缓存任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已选 ${selectedIds.value.size} / ${groups.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        val allIds = groups.map { it.id }.toSet()
                        selectedIds.value = if (selectedIds.value.size == allIds.size) emptySet() else allIds
                    }) {
                        Text(if (selectedIds.value.size == groups.size) "取消全选" else "全选")
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(groups, key = { it.id }) { group ->
                        val checked = group.id in selectedIds.value
                        ComicRestoreRow(
                            group = group,
                            checked = checked,
                            imgHost = imgHost,
                            onToggle = {
                                selectedIds.value = if (checked) {
                                    selectedIds.value - group.id
                                } else {
                                    selectedIds.value + group.id
                                }
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(modifier = Modifier.size(4.dp))
                    TextButton(onClick = onSkip) { Text("跳过缓存恢复") }
                    Spacer(modifier = Modifier.size(4.dp))
                    TextButton(onClick = {
                        val selected = groups.filter { it.id in selectedIds.value }
                        onConfirm(selected)
                    }) { Text("恢复", fontWeight = FontWeight.Bold) }
                }
        }
    }
}

@Composable
private fun ComicRestoreRow(
    group: ComicGroupBackup,
    checked: Boolean,
    imgHost: String,
    onToggle: () -> Unit,
) {
    val containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainer
    val contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp, 70.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                JmCoverImage(
                    comicId = group.id,
                    remoteHost = imgHost,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name.ifBlank { "未命名漫画" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "共 ${group.chapterCount} 章" +
                        if (group.authors.isNotEmpty()) " · ${group.authors.joinToString("、")}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.Circle,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

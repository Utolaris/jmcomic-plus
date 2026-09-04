package com.par9uet.jm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PATTERN
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_BOTH
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_PATTERN
import com.par9uet.jm.store.AppSecurityEditor
import com.par9uet.jm.store.AppSecurityPreferences
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.SelectDialog
import com.par9uet.jm.ui.components.SelectOption
import org.koin.compose.getKoin

private val unlockModeTextMap = mapOf(
    APP_LOCK_UNLOCK_MODE_PASSWORD to "仅密码",
    APP_LOCK_UNLOCK_MODE_PATTERN to "仅图案",
    APP_LOCK_UNLOCK_MODE_BOTH to "两种都要",
)

@Composable
fun AppLockSettingScreen(
    appSecurityPreferences: AppSecurityPreferences = getKoin().get(),
    appSecurityEditor: AppSecurityEditor = getKoin().get(),
) {
    val appLock by appSecurityPreferences.appLock.collectAsState()

    val hasPassword = appLock.hasPassword
    val hasPattern = appLock.hasPattern
    val hasAnyMethod = appLock.hasCredential

    var showPasswordLengthDialog by remember { mutableStateOf(false) }
    var showSetPasswordDialog by remember { mutableStateOf(false) }
    var showSetPatternDialog by remember { mutableStateOf(false) }
    // 设置密码时的临时长度（仅在选择完长度后弹出输入框时使用）
    var pendingPasswordLength by remember { mutableStateOf(appLock.passwordLength) }

    CommonScaffold(
        title = "应用锁",
        overlayContent = {
            val lengthOptions = remember {
                (4..8).map { SelectOption("$it 位", it.toString()) }
            }

            SelectDialog(
                visible = showPasswordLengthDialog,
                title = "密码长度",
                value = pendingPasswordLength.toString(),
                modifier = Modifier.widthIn(max = 420.dp),
                selectOptionList = lengthOptions,
                onSelect = { value ->
                    pendingPasswordLength = value.toIntOrNull() ?: 4
                    showPasswordLengthDialog = false
                    showSetPasswordDialog = true
                },
                onDismissRequest = { showPasswordLengthDialog = false },
            )

            SetAppLockPasswordDialog(
                visible = showSetPasswordDialog,
                lockType = APP_LOCK_TYPE_PASSWORD,
                passwordLength = pendingPasswordLength,
                onConfirm = { pwd ->
                    // 一次完整状态迁移：密码、长度、解锁模式在同一更新内生效
                    appSecurityEditor.setPassword(pwd, pendingPasswordLength)
                    showSetPasswordDialog = false
                },
                onDismiss = { showSetPasswordDialog = false },
            )

            SetAppLockPasswordDialog(
                visible = showSetPatternDialog,
                lockType = APP_LOCK_TYPE_PATTERN,
                onConfirm = { pattern ->
                    appSecurityEditor.setPattern(pattern)
                    showSetPatternDialog = false
                },
                onDismiss = { showSetPatternDialog = false },
            )
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(title = "设置解锁方式") {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Key,
                        title = "密码",
                        value = hasPassword,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                pendingPasswordLength = appLock.passwordLength
                                showPasswordLengthDialog = true
                            } else {
                                // 移除最后一种凭据时由编辑器关闭应用锁并修正解锁模式
                                appSecurityEditor.removePassword()
                            }
                        }
                    )
                    if (hasPassword) {
                        SettingsRow(
                            icon = Icons.Rounded.Key,
                            title = "密码长度",
                            value = "${appLock.passwordLength} 位"
                        ) {
                            pendingPasswordLength = appLock.passwordLength
                            showPasswordLengthDialog = true
                        }
                    }
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Gesture,
                        title = "图案锁",
                        value = hasPattern,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                showSetPatternDialog = true
                            } else {
                                appSecurityEditor.removePattern()
                            }
                        }
                    )
                }
            }

            // 解锁模式仅在两种凭据都存在时可选；BOTH 与单方式之间的切换由编辑器校验
            if (hasPassword && hasPattern) {
                item {
                    SettingsSection(title = "解锁模式") {
                        unlockModeTextMap.forEach { (mode, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = appLock.unlockMode == mode,
                                        onClick = { appSecurityEditor.selectUnlockMode(mode) }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = appLock.unlockMode == mode,
                                    onClick = { appSecurityEditor.selectUnlockMode(mode) }
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "启用") {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Lock,
                        title = "启用应用锁",
                        value = appLock.enabled,
                        onCheckedChange = { enabled ->
                            // 没有任何解锁方式时编辑器保持关闭，UI 只负责提示
                            appSecurityEditor.setAppLockEnabled(enabled)
                        }
                    )
                    if (!hasAnyMethod) {
                        Text(
                            text = "请先设置至少一种解锁方式",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            modifier = Modifier.padding(horizontal = 4.dp),
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    SettingsBaseRow(
        icon = icon,
        title = title,
        value = value,
        onClick = onClick,
        trailingContent = {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    value: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsBaseRow(
        icon = icon,
        title = title,
        value = if (value) "已设置" else "未设置",
        onClick = { onCheckedChange(!value) },
        trailingContent = {
            Switch(
                checked = value,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

@Composable
private fun SettingsBaseRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
    trailingContent: @Composable () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        modifier = Modifier.size(22.dp),
                        imageVector = icon,
                        contentDescription = null
                    )
                }
            }
        },
        headlineContent = { Text(text = title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                text = value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    )
}

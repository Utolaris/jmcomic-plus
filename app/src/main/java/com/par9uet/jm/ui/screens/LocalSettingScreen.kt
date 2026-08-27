package com.par9uet.jm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Recommend
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.AVAILABLE_APIS
import com.par9uet.jm.data.models.AVAILABLE_THEMES
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.ui.viewModel.SettingsUiState
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.SelectDialog
import com.par9uet.jm.ui.components.SelectOption
import com.par9uet.jm.ui.glass.GlassModal

private sealed class SettingType {
    object Api : SettingType()
    object Theme : SettingType()
    object LauncherDisguise : SettingType()
    object PrefetchCount : SettingType()
    object ReadMode : SettingType()
    object NotificationManagement : SettingType()
    object AllGridColumns : SettingType()
    object ReadDecodeConcurrency : SettingType()
}

private const val NOTIFICATION_ON_WITH_NAME = "on_with_name"
private const val NOTIFICATION_ON_WITHOUT_NAME = "on_without_name"
private const val NOTIFICATION_OFF = "off"

private val themeTextMap = mapOf(
    "auto" to "\u8ddf\u968f\u7cfb\u7edf",
    "light" to "\u65e5\u95f4\u6a21\u5f0f",
    "dark" to "\u591c\u95f4\u6a21\u5f0f",
)

private fun gridColumnsText(columns: Int): String =
    if (columns == 0) "\u81ea\u9002\u5e94" else "$columns \u5217"

@Composable
fun LocalSettingScreen(
    settingsViewModel: com.par9uet.jm.ui.viewModel.SettingsViewModel = org.koin.compose.viewmodel.koinViewModel(),
) {
    val mainNavController = LocalMainNavController.current
    val ui by settingsViewModel.uiState.collectAsState()
    val favoriteSyncState by settingsViewModel.favoriteSyncState.collectAsState()
    var settingType by remember { mutableStateOf<SettingType>(SettingType.Api) }
    var isOpenSettingSelectDialog by remember { mutableStateOf(false) }
    var showHomeExcludedTagsDialog by remember { mutableStateOf(false) }

    fun openSetting(type: SettingType) {
        settingType = type
        isOpenSettingSelectDialog = true
    }

    // 应用锁状态文本由 SettingsUiState 从窄状态推导。
    val appLockStatusText = ui.appLockSummaryText()

    CommonScaffold(
        title = "\u8bbe\u7f6e",
        overlayContent = {
            SettingSelectDialogContent(
                visible = isOpenSettingSelectDialog,
                settingType = settingType,
                ui = ui,
                settingsViewModel = settingsViewModel,
                onDismiss = { isOpenSettingSelectDialog = false }
            )
            HomeExcludedTagsDialog(
                visible = showHomeExcludedTagsDialog,
                tags = ui.homeExcludedTags,
                onConfirm = { tags ->
                    settingsViewModel.updateHomeExcludedTags(tags)
                    showHomeExcludedTagsDialog = false
                },
                onDismiss = { showHomeExcludedTagsDialog = false }
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
                SettingsSection(title = "\u663e\u793a") {
                    SettingsRow(Icons.Rounded.DarkMode, "\u4e3b\u9898", themeTextMap[ui.theme].orEmpty()) {
                        openSetting(SettingType.Theme)
                    }
                    SettingsRow(
                        icon = Icons.Rounded.Palette,
                        title = "\u8c03\u8272\u677f",
                        value = when (ui.colorPalette.presetId) {
                            "custom" -> "\u81ea\u5b9a\u4e49"
                            "monet" -> "\u83ab\u5948\u53d6\u8272"
                            else -> "\u9884\u8bbe\u65b9\u6848"
                        }
                    ) {
                        mainNavController.navigate("colorPalette")
                    }
                    SettingsRow(Icons.Rounded.Image, "\u56fe\u6807\u4f2a\u88c5", LauncherDisguise.fromId(ui.launcherDisguiseId).label) {
                        openSetting(SettingType.LauncherDisguise)
                    }
                    SettingsSwitchRow(
                        icon = Icons.Rounded.ContentPaste,
                        title = "\u526a\u5207\u677f\u81ea\u52a8\u68c0\u6d4b",
                        value = ui.clipboardAutoDetectEnabled,
                        onCheckedChange = settingsViewModel::setClipboardAutoDetectEnabled
                    )
                    SettingsRow(
                        Icons.Rounded.GridView,
                        "\u7f51\u683c\u5217\u6570",
                        "\u9996\u9875 ${gridColumnsText(ui.gridColumns.home)} \u00b7 \u6536\u85cf ${gridColumnsText(ui.gridColumns.collect)} \u00b7 \u7f13\u5b58 ${gridColumnsText(ui.gridColumns.download)} \u00b7 \u5386\u53f2 ${gridColumnsText(ui.gridColumns.history)} \u00b7 \u641c\u7d22 ${gridColumnsText(ui.gridColumns.search)}"
                    ) {
                        openSetting(SettingType.AllGridColumns)
                    }
                }
            }
            item {
                SettingsSection(title = "\u9690\u79c1") {
                    SettingsRow(
                        icon = Icons.Rounded.Lock,
                        title = "\u5e94\u7528\u9501",
                        value = appLockStatusText
                    ) {
                        mainNavController.navigate("appLockSetting")
                    }
                }
            }
            item {
                SettingsSection(title = "\u8fde\u63a5") {
                    SettingsRow(
                        Icons.Rounded.Api,
                        "网络推荐节点",
                        ui.apiEndpoint
                    ) {
                        openSetting(SettingType.Api)
                    }
                    SettingsRow(
                        Icons.Rounded.Dns,
                        "DoH 加密 DNS",
                        if (ui.doh.enabled) {
                            if (ui.doh.enabled && ui.doh.autoStart) {
                                val serverName = com.par9uet.jm.network.resolveDohServer(
                                    ui.doh.serverId,
                                    ui.doh.customServerName,
                                    ui.doh.customServerUrl,
                                ).name
                                "已启用 · $serverName"
                            } else {
                                "已开启（未自启）"
                            }
                        } else {
                            "已关闭"
                        }
                    ) {
                        mainNavController.navigate("dohSetting")
                    }
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Recommend,
                        title = "\u7f51\u7edc\u63a8\u8350",
                        value = ui.recommendationEnabled,
                        onCheckedChange = { settingsViewModel.setPreferenceRecommendEnabled(it) }
                    )
                    if (ui.recommendationEnabled) {
                        Text(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            text = "\u5f00\u542f\u540e\u9996\u9875\u9ed8\u8ba4\u5c55\u793a\u57fa\u4e8e\u767b\u5f55\u8d26\u53f7\u7684\u4e2a\u6027\u5316\u63a8\u8350\uff0c\u53ef\u80fd\u4e0d\u7a33\u5b9a",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SettingsRow(
                        icon = Icons.Rounded.Block,
                        title = "\u9996\u9875\u6807\u7b7e\u6392\u9664",
                        value = if (ui.homeExcludedTags.isEmpty()) "\u672a\u8bbe\u7f6e" else "${ui.homeExcludedTags.size} \u4e2a\u6807\u7b7e"
                    ) {
                        showHomeExcludedTagsDialog = true
                    }
                }
            }
            item {
                SettingsSection(title = "\u9605\u8bfb") {
                    SettingsRow(Icons.Rounded.Tune, "\u56fe\u7247\u9884\u52a0\u8f7d", prefetchText(ui.prefetchCount)) {
                        openSetting(SettingType.PrefetchCount)
                    }
                    SettingsRow(Icons.AutoMirrored.Rounded.MenuBook, "\u9605\u8bfb\u6a21\u5f0f", readModeText(ui.readMode)) {
                        openSetting(SettingType.ReadMode)
                    }
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Memory,
                        title = "\u56fe\u7247\u5185\u5b58\u4f18\u5316",
                        value = ui.memoryOptEnabled,
                        onCheckedChange = { settingsViewModel.setMemoryOptEnabled(it) }
                    )
                    if (ui.memoryOptEnabled) {
                        Text(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            text = "\u5f00\u542f\u540e\u9650\u5236\u5e76\u53d1\u89e3\u7801\u6570\u5e76\u964d\u4f4e\u91c7\u6837\u7387\uff0c\u7f13\u89e3\u4f4e\u7aef\u8bbe\u5907 OOM\uff1b\u63a8\u8350\u503c 2",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SettingsRow(
                            icon = Icons.Rounded.Memory,
                            title = "\u5e76\u53d1\u89e3\u7801\u6570",
                            value = "\u63a8\u8350 ${ui.decodeConcurrency}"
                        ) {
                            openSetting(SettingType.ReadDecodeConcurrency)
                        }
                    }
                }
            }
            item {
                SettingsSection(title = "\u901a\u77e5") {
                    SettingsRow(Icons.Rounded.Notifications, "\u901a\u77e5\u7ba1\u7406", notificationText(ui.notification)) {
                        openSetting(SettingType.NotificationManagement)
                    }
                }
            }
            item {
                SettingsSection(title = "\u5176\u4ed6") {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.EventAvailable,
                        title = "\u81ea\u52a8\u7b7e\u5230",
                        value = ui.autoSignInEnabled,
                        onCheckedChange = { settingsViewModel.setAutoSignInEnabled(it) }
                    )
                    SettingsRow(
                        Icons.Rounded.CloudSync,
                        "强制刷新收藏夹",
                        if (favoriteSyncState.isSyncing && favoriteSyncState.isForceRefresh) {
                            "正在重建${favoriteSyncState.phase.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""} " +
                                "${favoriteSyncState.completed}/${favoriteSyncState.total}"
                        } else {
                            "重新获取收藏及完整元数据"
                        }
                    ) {
                        if (!favoriteSyncState.isSyncing) {
                            // 通过窄的同步请求能力触发；Settings 不再依赖 FavoritesViewModel
                            settingsViewModel.requestFavoriteForceRefresh()
                        }
                    }
                    SettingsRow(Icons.Rounded.BugReport, "\u67e5\u770b\u65e5\u5fd7", "\u8c03\u8bd5\u548c\u9519\u8bef\u4fe1\u606f") {
                        mainNavController.navigate("logViewer")
                    }
                    SettingsRow(Icons.Rounded.CleaningServices, "\u7f13\u5b58\u6e05\u7406", "\u6e05\u7406\u56fe\u7247\u3001\u6f2b\u753b\u7b49\u7f13\u5b58\u6587\u4ef6") {
                        mainNavController.navigate("cacheCleanup")
                    }
                    SettingsRow(Icons.Rounded.CloudSync, "\u6570\u636e\u5907\u4efd", "\u5907\u4efd\u4e0e\u6062\u590d\u5e94\u7528\u8bbe\u7f6e") {
                        mainNavController.navigate("backupRestore")
                    }
                    SettingsRow(Icons.Rounded.SystemUpdate, "\u68c0\u67e5\u66f4\u65b0", "\u67e5\u770b GitHub Release \u6700\u65b0\u7248\u672c") {
                        mainNavController.navigate("checkUpdate")
                    }
                    SettingsRow(Icons.Rounded.Info, "\u5173\u4e8e", "\u5e94\u7528\u7248\u672c\u548c\u4ed3\u5e93") {
                        mainNavController.navigate("about")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSelectDialogContent(
    visible: Boolean,
    settingType: SettingType,
    ui: SettingsUiState,
    settingsViewModel: com.par9uet.jm.ui.viewModel.SettingsViewModel,
    onDismiss: () -> Unit
) {
    // 网格列数使用滑块设置，不走选项列表
    if (settingType is SettingType.AllGridColumns) {
        AllGridColumnSliderDialog(
            visible = visible,
            homeColumns = ui.gridColumns.home,
            collectColumns = ui.gridColumns.collect,
            downloadColumns = ui.gridColumns.download,
            historyColumns = ui.gridColumns.history,
            searchColumns = ui.gridColumns.search,
            onConfirm = { home, collect, download, history, search ->
                settingsViewModel.applyGridColumns(home, collect, download, history, search)
                onDismiss()
            },
            onDismiss = onDismiss
        )
        return
    }
    val apiSelectOptionList by remember(AVAILABLE_APIS) {
        derivedStateOf { AVAILABLE_APIS.map { SelectOption(it.removePrefix("https://"), it) } }
    }
    val themeSelectOptionList by remember(AVAILABLE_THEMES) {
        derivedStateOf { AVAILABLE_THEMES.map { SelectOption(themeTextMap[it].orEmpty(), it) } }
    }
    val launcherDisguiseOptionList by remember {
        derivedStateOf { LauncherDisguise.entries.map { SelectOption(it.label, it.id) } }
    }
    val prefetchCountOptionList by remember {
        derivedStateOf {
            listOf(
                SelectOption("\u5173\u95ed", "0"),
                SelectOption("\u4e00\u5f20", "1"),
                SelectOption("\u4e24\u5f20", "2"),
                SelectOption("\u4e09\u5f20", "3"),
                SelectOption("\u56db\u5f20", "4"),
                SelectOption("\u4e94\u5f20", "5"),
                SelectOption("\u516d\u5f20", "6")
            )
        }
    }
    val readModeOptionList by remember {
        derivedStateOf {
            listOf(
                SelectOption("\u6eda\u52a8", "scroll"),
                SelectOption("\u7ffb\u9875", "page"),
                SelectOption("\u70b9\u51fb", "tap")
            )
        }
    }
    val notificationOptionList by remember {
        derivedStateOf {
            listOf(
                SelectOption("\u5f00\u542f\u5e76\u663e\u793a\u6f2b\u753b\u540d", NOTIFICATION_ON_WITH_NAME),
                SelectOption("\u5f00\u542f\u4f46\u4e0d\u663e\u793a\u6f2b\u753b\u540d", NOTIFICATION_ON_WITHOUT_NAME),
                SelectOption("\u5173\u95ed", NOTIFICATION_OFF)
            )
        }
    }
    val readDecodeConcurrencyOptionList by remember {
        derivedStateOf {
            listOf(
                SelectOption("1", "1"),
                SelectOption("2\uff08\u63a8\u8350\uff09", "2"),
                SelectOption("3", "3"),
                SelectOption("4", "4")
            )
        }
    }
    SelectDialog(
        visible = visible,
        title = settingTitle(settingType),
        value = settingValue(settingType, ui),
        selectOptionList = when (settingType) {
            is SettingType.Api -> apiSelectOptionList
            is SettingType.Theme -> themeSelectOptionList
            is SettingType.LauncherDisguise -> launcherDisguiseOptionList
            is SettingType.PrefetchCount -> prefetchCountOptionList
            is SettingType.ReadMode -> readModeOptionList
            is SettingType.NotificationManagement -> notificationOptionList
            is SettingType.ReadDecodeConcurrency -> readDecodeConcurrencyOptionList
        },
        onSelect = {
            when (settingType) {
                is SettingType.Api -> settingsViewModel.selectApi(it)
                is SettingType.Theme -> settingsViewModel.selectTheme(it)
                is SettingType.LauncherDisguise -> settingsViewModel.selectLauncherDisguise(it)
                is SettingType.PrefetchCount -> settingsViewModel.setPrefetchCount(it.toIntOrNull() ?: 0)
                is SettingType.ReadMode -> settingsViewModel.setReadMode(it)
                is SettingType.NotificationManagement -> {
                    settingsViewModel.applyNotificationSetting(
                        show = it != NOTIFICATION_OFF,
                        showName = it == NOTIFICATION_ON_WITH_NAME
                    )
                }
                is SettingType.ReadDecodeConcurrency -> settingsViewModel.setDecodeConcurrency(it.toIntOrNull() ?: 2)
            }
            onDismiss()
        },
        onDismissRequest = onDismiss
    )
}

@Composable
private fun AllGridColumnSliderDialog(
    visible: Boolean,
    homeColumns: Int,
    collectColumns: Int,
    downloadColumns: Int,
    historyColumns: Int,
    searchColumns: Int,
    onConfirm: (Int, Int, Int, Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var home by remember { mutableStateOf(homeColumns.toFloat()) }
    var collect by remember { mutableStateOf(collectColumns.toFloat()) }
    var download by remember { mutableStateOf(downloadColumns.toFloat()) }
    var history by remember { mutableStateOf(historyColumns.toFloat()) }
    var search by remember { mutableStateOf(searchColumns.toFloat()) }

    LaunchedEffect(visible) {
        if (visible) {
            home = homeColumns.toFloat()
            collect = collectColumns.toFloat()
            download = downloadColumns.toFloat()
            history = historyColumns.toFloat()
            search = searchColumns.toFloat()
        }
    }

    @Composable
    fun SliderRow(
        icon: ImageVector,
        label: String,
        value: Float,
        onChange: (Float) -> Unit,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    text = if (value <= 0f) "自适应" else "${value.toInt()} 列",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = 0f..6f,
                steps = 5,
            )
        }
    }

    val screenHeight = LocalWindowInfo.current.containerSize.height.dp
    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        surfaceId = "settings-grid-columns-glass-modal",
        modifier = Modifier.widthIn(max = 460.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.85f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("网格列数", style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "拖动滑块设置各页面每行显示的漫画数量，0 = 自适应",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SliderRow(Icons.Rounded.Home, "首页", home) { home = it }
                SliderRow(Icons.Rounded.Bookmarks, "收藏夹", collect) { collect = it }
                SliderRow(Icons.Rounded.Download, "缓存", download) { download = it }
                SliderRow(Icons.Rounded.History, "历史记录", history) { history = it }
                SliderRow(Icons.Rounded.Search, "搜索", search) { search = it }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = {
                    onConfirm(home.toInt(), collect.toInt(), download.toInt(), history.toInt(), search.toInt())
                }) { Text("确定") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeExcludedTagsDialog(
    visible: Boolean,
    tags: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var currentTags by remember { mutableStateOf(tags) }

    LaunchedEffect(visible) {
        if (visible) {
            text = ""
            currentTags = tags
        }
    }

    val screenHeight = LocalWindowInfo.current.containerSize.height.dp
    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        surfaceId = "settings-home-excluded-tags-glass-modal",
        modifier = Modifier.widthIn(max = 460.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.85f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("首页标签排除", style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "添加标签后，首页推荐将不再显示包含这些标签的漫画",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("输入标签名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val trimmed = text.trim()
                                if (trimmed.isNotEmpty() && trimmed !in currentTags) {
                                    currentTags = currentTags + trimmed
                                    text = ""
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "添加")
                        }
                    }
                )
                if (currentTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        currentTags.forEach { tag ->
                            InputChip(
                                label = { Text(tag) },
                                selected = false,
                                onClick = {},
                                trailingIcon = {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                currentTags = currentTags - tag
                                            }
                                    )
                                }
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { onConfirm(currentTags) }) { Text("确定") }
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
        value = if (value) "\u5f00\u542f" else "\u5173\u95ed",
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

private fun prefetchText(value: Int): String {
    return when (value) {
        0 -> "\u5173\u95ed"
        1 -> "\u4e00\u5f20"
        2 -> "\u4e24\u5f20"
        3 -> "\u4e09\u5f20"
        else -> "$value \u5f20"
    }
}

private fun readModeText(value: String): String {
    return when (value) {
        "scroll" -> "\u6eda\u52a8"
        "page" -> "\u7ffb\u9875"
        "tap" -> "\u70b9\u51fb"
        else -> "\u6eda\u52a8"
    }
}

private fun notificationText(notification: com.par9uet.jm.store.CacheNotificationSetting): String {
    return when {
        !notification.show -> "\u5173\u95ed"
        notification.showName -> "\u5f00\u542f\u5e76\u663e\u793a\u6f2b\u753b\u540d"
        else -> "\u5f00\u542f\u4f46\u4e0d\u663e\u793a\u6f2b\u753b\u540d"
    }
}

private fun settingTitle(type: SettingType): String {
    return when (type) {
        is SettingType.Api -> "\u7f51\u7edc\u63a8\u8350\u8282\u70b9"
        is SettingType.Theme -> "\u4e3b\u9898"
        is SettingType.LauncherDisguise -> "\u56fe\u6807\u4f2a\u88c5"
        is SettingType.PrefetchCount -> "\u56fe\u7247\u9884\u52a0\u8f7d"
        is SettingType.ReadMode -> "\u9605\u8bfb\u6a21\u5f0f"
        is SettingType.NotificationManagement -> "\u901a\u77e5\u7ba1\u7406"
        is SettingType.AllGridColumns -> "\u7f51\u683c\u5217\u6570"
        is SettingType.ReadDecodeConcurrency -> "\u5e76\u53d1\u89e3\u7801\u6570"
    }
}

private fun settingValue(type: SettingType, ui: SettingsUiState): String {
    return when (type) {
        is SettingType.Api -> ui.apiEndpoint
        is SettingType.Theme -> ui.theme
        is SettingType.LauncherDisguise -> LauncherDisguise.fromId(ui.launcherDisguiseId).id
        is SettingType.PrefetchCount -> "${ui.prefetchCount}"
        is SettingType.ReadMode -> ui.readMode
        is SettingType.NotificationManagement -> when {
            !ui.notification.show -> NOTIFICATION_OFF
            ui.notification.showName -> NOTIFICATION_ON_WITH_NAME
            else -> NOTIFICATION_ON_WITHOUT_NAME
        }
        is SettingType.AllGridColumns -> ""
        is SettingType.ReadDecodeConcurrency -> "${ui.decodeConcurrency}"
    }
}

package com.par9uet.jm.ui.screens.tabScreen

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow
import com.par9uet.jm.ui.components.FavoriteSyncIconButton
import com.par9uet.jm.ui.navigation.MainTab
import com.par9uet.jm.ui.screens.HomeTopBarActions
import com.par9uet.jm.ui.viewModel.UserViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBarComponent(
    title: String,
    onSearch: () -> Unit,
    onDownload: () -> Unit,
    onWeekly: () -> Unit,
    onExtract: () -> Unit,
    onSign: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        title = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {
            HomeTopBarActions(
                onSearch = onSearch,
                onDownload = onDownload,
                onWeekly = onWeekly,
                onExtract = onExtract,
                onSign = onSign,
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectTopBarComponent(
    tab: MainTab,
    userViewModel: UserViewModel = koinActivityViewModel(),
) {
    val favoriteSyncState by userViewModel.favoriteSyncState.collectAsState()
    val selectedFolderId by userViewModel.selectedFolderId.collectAsState()

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        title = {
            Text(
                tab.topBarTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {
            FavoriteSyncIconButton(
                isSyncing = favoriteSyncState.isSyncing,
                hasError = favoriteSyncState.errorMessage != null,
                onClick = { userViewModel.requestFavoriteManualSync(selectedFolderId) },
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBarComponent(tab: MainTab) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        title = {
            Text(
                tab.topBarTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {}
    )
}

@Composable
fun TopBarComponent(
    tab: MainTab,
    homeTitle: String = MainTab.Home.topBarTitle,
    onHomeSearch: () -> Unit = {},
    onHomeDownload: () -> Unit = {},
    onHomeWeekly: () -> Unit = {},
    onHomeExtract: () -> Unit = {},
    onHomeSign: () -> Unit = {},
) {
    when (tab) {
        MainTab.Home -> HomeTopBarComponent(
            title = homeTitle,
            onSearch = onHomeSearch,
            onDownload = onHomeDownload,
            onWeekly = onHomeWeekly,
            onExtract = onHomeExtract,
            onSign = onHomeSign,
        )
        MainTab.Collect -> CollectTopBarComponent(tab)
        MainTab.Settings -> SettingsTopBarComponent(tab)
    }
}

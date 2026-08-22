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
import com.par9uet.jm.ui.viewModel.UserViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBarComponent(tab: MainTab) {
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
fun TopBarComponent(tab: MainTab) {
    when (tab) {
        MainTab.Home -> HomeTopBarComponent(tab)
        MainTab.Collect -> CollectTopBarComponent(tab)
        MainTab.Settings -> SettingsTopBarComponent(tab)
    }
}

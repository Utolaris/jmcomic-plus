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
import androidx.navigation.compose.currentBackStackEntryAsState
import com.par9uet.jm.ui.components.FavoriteSyncIconButton
import com.par9uet.jm.ui.viewModel.UserViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBarComponent() {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        title = {
            Text(
                "禁漫天堂",
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
                "我的收藏",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {
            FavoriteSyncIconButton(
                isSyncing = favoriteSyncState.isSyncing,
                hasError = favoriteSyncState.errorMessage != null,
                onClick = { userViewModel.syncFavorites(selectedFolderId) },
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserTopBarComponent() {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        title = {
            Text(
                "个人中心",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {}
    )
}

@Composable
fun TopBarComponent() {
    val tabNavController = LocalTabNavController.current
    val backStackEntryState by tabNavController.currentBackStackEntryAsState()
    val currentRoute = backStackEntryState?.destination?.route
    when (currentRoute) {
        "home" -> HomeTopBarComponent()
        "collect" -> CollectTopBarComponent()
        "user" -> UserTopBarComponent()
    }
}

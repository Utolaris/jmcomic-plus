package com.par9uet.jm.ui.screens.tabScreen

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import com.par9uet.jm.ui.navigation.MainTab
import com.par9uet.jm.ui.screens.HomeCategoryTitleSelector
import com.par9uet.jm.ui.screens.HomeTopBarActions
import com.par9uet.jm.ui.viewModel.ComicViewModel
import com.par9uet.jm.ui.viewModel.UserViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBarComponent(
    title: String,
    categories: List<ComicViewModel.HomeCategoryInfo>,
    selectedCategoryId: String?,
    onCategorySelected: (String) -> Unit,
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
            HomeCategoryTitleSelector(
                title = title,
                categories = categories,
                selectedCategoryId = selectedCategoryId,
                onCategorySelected = onCategorySelected,
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
    controller: FavoritesUiController,
    userViewModel: UserViewModel = koinActivityViewModel(),
) {
    FavoritesMaterialTopBar(
        controller = controller,
        userViewModel = userViewModel,
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
internal fun TopBarComponent(
    tab: MainTab,
    homeTitle: String = MainTab.Home.topBarTitle,
    homeCategories: List<ComicViewModel.HomeCategoryInfo> = emptyList(),
    selectedHomeCategoryId: String? = null,
    onHomeCategorySelected: (String) -> Unit = {},
    favoritesController: FavoritesUiController? = null,
    onHomeSearch: () -> Unit = {},
    onHomeDownload: () -> Unit = {},
    onHomeWeekly: () -> Unit = {},
    onHomeExtract: () -> Unit = {},
    onHomeSign: () -> Unit = {},
) {
    when (tab) {
        MainTab.Home -> HomeTopBarComponent(
            title = homeTitle,
            categories = homeCategories,
            selectedCategoryId = selectedHomeCategoryId,
            onCategorySelected = onHomeCategorySelected,
            onSearch = onHomeSearch,
            onDownload = onHomeDownload,
            onWeekly = onHomeWeekly,
            onExtract = onHomeExtract,
            onSign = onHomeSign,
        )
        MainTab.Collect -> CollectTopBarComponent(
            controller = favoritesController ?: rememberFavoritesUiController(),
        )
        MainTab.Settings -> SettingsTopBarComponent(tab)
    }
}

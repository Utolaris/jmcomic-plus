package com.par9uet.jm.ui.screens.tabScreen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.par9uet.jm.R
import com.par9uet.jm.ui.navigation.MainTab

@Composable
fun BottomNavigationBarComponent(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
) {
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        MainTab.ordered.forEach { tab ->
            NavigationBarItem(
                colors = itemColors,
                icon = { MainTabIcon(tab) },
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
            )
        }
    }
}

@Composable
fun NavigationRailComponent(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
) {
    val itemColors = NavigationRailItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        MainTab.ordered.forEach { tab ->
            NavigationRailItem(
                colors = itemColors,
                icon = { MainTabIcon(tab) },
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
            )
        }
    }
}

@Composable
private fun MainTabIcon(tab: MainTab) {
    when (tab) {
        MainTab.Home -> Icon(
            painter = painterResource(R.drawable.home_icon),
            contentDescription = tab.navigationLabel,
        )
        MainTab.Collect -> Icon(
            imageVector = Icons.Filled.Bookmark,
            contentDescription = tab.navigationLabel,
        )
        MainTab.Settings -> Icon(
            painter = painterResource(R.drawable.person_icon),
            contentDescription = tab.navigationLabel,
        )
    }
}

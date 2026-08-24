package com.par9uet.jm.ui.screens.tabScreen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.par9uet.jm.R
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle
import com.par9uet.jm.ui.glass.GlassStyle
import com.par9uet.jm.ui.navigation.MainTab

@Composable
fun BottomNavigationBarComponent(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    navigationBarInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    PrimaryGlassBottomBar(
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        modifier = modifier,
        navigationBarInset = navigationBarInset,
    )
}

@Composable
fun PrimaryGlassBottomBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle.Default,
    navigationBarInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    // fillMaxSize here would make this overlay box swallow taps across the whole screen,
    // blocking dialogs rendered behind it; only the bar area may be interactive.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(style.barHeight + style.outerMargin + navigationBarInset)
            .wrapContentHeight(Alignment.Bottom),
    ) {
        val barWidth = minOf(
            (maxWidth - style.outerMargin * 2).coerceAtLeast(0.dp),
            style.maxBarWidth,
        )
        Box(
            modifier = Modifier
                // The bar itself must not own the whole screen: an unqualified fillMaxSize Box
                // in the overlay layer swallows taps meant for dialogs rendered behind it.
                .fillMaxSize()
                .padding(bottom = navigationBarInset + style.outerMargin),
        ) {
            GlassSurface(
                surfaceId = "primary-bottom-navigation",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .width(barWidth)
                    .height(style.barHeight),
                style = GlassSurfaceStyle(
                    cornerRadius = style.cornerRadius,
                    material = style.material,
                ),
            ) {
                val itemWidth = barWidth / MainTab.ordered.size
                val selectedIndex by animateFloatAsState(
                    targetValue = selectedTab.index.toFloat(),
                    animationSpec = tween(
                        durationMillis = 320,
                        easing = FastOutSlowInEasing,
                    ),
                    label = "glass-selected-tab",
                )

                Box(
                    modifier = Modifier
                        .offset(x = itemWidth * selectedIndex)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .padding(4.dp)
                        .clip(RoundedCornerShape(style.cornerRadius))
                        .background(
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = style.selectedIndicatorAlpha,
                            ),
                        ),
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    MainTab.ordered.forEach { tab ->
                        val isSelected = tab == selectedTab
                        val contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(style.cornerRadius))
                                .clickable(
                                    role = Role.Tab,
                                    onClick = { onTabSelected(tab) },
                                )
                                .semantics(mergeDescendants = true) {
                                    contentDescription = tab.navigationLabel
                                    selected = isSelected
                                    role = Role.Tab
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            MainTabIcon(
                                tab = tab,
                                contentDescription = null,
                                tint = contentColor,
                            )
                            Text(
                                text = tab.navigationLabel,
                                color = contentColor,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
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
private fun MainTabIcon(
    tab: MainTab,
    contentDescription: String? = tab.navigationLabel,
    tint: Color? = null,
) {
    val resolvedTint = tint ?: LocalContentColor.current
    when (tab) {
        MainTab.Home -> Icon(
            painter = painterResource(R.drawable.home_icon),
            contentDescription = contentDescription,
            tint = resolvedTint,
        )
        MainTab.Collect -> Icon(
            imageVector = Icons.Filled.Bookmark,
            contentDescription = contentDescription,
            tint = resolvedTint,
        )
        MainTab.Settings -> Icon(
            painter = painterResource(R.drawable.person_icon),
            contentDescription = contentDescription,
            tint = resolvedTint,
        )
    }
}

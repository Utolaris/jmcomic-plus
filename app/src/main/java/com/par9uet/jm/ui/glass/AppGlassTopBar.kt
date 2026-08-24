package com.par9uet.jm.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object AppGlassTopBarDefaults {
    val ContentHeight = 60.dp
}

/** Canonical full-width phone chrome shared by Home, ComicDetail, and Settings. */
@Composable
internal fun AppGlassTopBar(
    surfaceId: String,
    statusBarInset: Dp,
    modifier: Modifier = Modifier,
    surfaceAlpha: Float = 1f,
    navigationIcon: @Composable RowScope.() -> Unit = {},
    title: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    GlassSurface(
        surfaceId = surfaceId,
        modifier = modifier
            .fillMaxWidth()
            .height(statusBarInset + AppGlassTopBarDefaults.ContentHeight),
        style = GlassSurfaceStyle(cornerRadius = 0.dp),
        surfaceAlpha = surfaceAlpha,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarInset, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                title()
            }
            actions()
        }
    }
}

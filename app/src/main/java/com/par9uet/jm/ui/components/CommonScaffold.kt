package com.par9uet.jm.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.par9uet.jm.ui.glass.AppGlassTopBar
import com.par9uet.jm.ui.glass.AppGlassTopBarDefaults
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.screens.LocalMainNavController

/** Shared glass scaffold for hierarchical destinations. */
@Composable
fun CommonScaffold(
    title: String,
    titleContent: @Composable (() -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    navigationContent: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (() -> Unit)? = null,
) {
    val mainNavController = LocalMainNavController.current
    val density = LocalDensity.current
    val statusBarInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navigationBarInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val topContentPadding = statusBarInset + AppGlassTopBarDefaults.ContentHeight

    GlassCaptureHost(
        modifier = Modifier.fillMaxSize(),
        sourceContent = {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(),
                bottomBar = bottomBar,
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = topContentPadding,
                            bottom = maxOf(
                                innerPadding.calculateBottomPadding(),
                                navigationBarInset,
                            ),
                        ),
                ) {
                    content?.invoke()
                }
            }
        },
        overlayContent = {
            Box(modifier = Modifier.fillMaxSize()) {
                AppGlassTopBar(
                    surfaceId = "common-top-bar",
                    statusBarInset = statusBarInset,
                    modifier = Modifier.align(Alignment.TopCenter),
                    navigationIcon = {
                        if (navigationContent != null) {
                            navigationContent()
                        } else {
                            IconButton(
                                onClick = {
                                    onNavigateBack?.invoke() ?: mainNavController.popBackStack()
                                },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回上一页",
                                )
                            }
                        }
                    },
                    title = {
                        if (titleContent != null) {
                            titleContent()
                        } else {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    actions = actions,
                )
            }
        },
    )
}

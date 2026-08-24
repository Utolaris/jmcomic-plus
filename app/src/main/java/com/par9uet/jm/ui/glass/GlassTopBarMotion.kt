package com.par9uet.jm.ui.glass

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
/** Simple two-state chrome language shared by the History / Download selection top bars. */
internal enum class ChromeMode {
    NORMAL,
    SELECTION,
}

/**
 * Animates between two full-width glass top bar modes using the accepted Favorites language:
 * enter fades in over ~260ms with a very short horizontal slide, exit fades out over ~220ms.
 * Each mode renders its own [AppGlassTopBar] (distinct surface id) and synchronizes its glass
 * backdrop alpha with the transition so no ghost backdrop is left during the crossfade.
 */
@Composable
internal fun GlassTopBarModeTransition(
    targetState: ChromeMode,
    statusBarInset: Dp,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedContentScope.(mode: ChromeMode, surfaceAlpha: Float) -> Unit,
) {
    val modeTransition = updateTransition(targetState = targetState, label = "glass-top-bar-mode")
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 14 }) togetherWith
                (fadeOut(tween(220)) + slideOutHorizontally(tween(220)) { -it / 14 })
        },
        contentAlignment = Alignment.TopCenter,
        label = "glass-top-bar-mode",
    ) { mode ->
        val surfaceAlpha by modeTransition.animateFloat(
            transitionSpec = { tween(260) },
            label = "glass-top-bar-alpha",
        ) { if (it == mode) 1f else 0f }
        Box(modifier = Modifier.fillMaxSize()) {
            content(mode, surfaceAlpha)
        }
    }
}

package com.par9uet.jm.ui.interaction

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.glass.GlassSurface
import com.par9uet.jm.ui.glass.GlassSurfaceStyle

@Composable
internal fun PullDownSearchIndicator(
    state: PullDownActionState,
    surfaceId: String,
    topOffset: Dp,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    val progress = when (phase) {
        PullDownActionPhase.ARMED,
        PullDownActionPhase.TRIGGERING -> 1f
        PullDownActionPhase.IDLE,
        PullDownActionPhase.PULLING -> state.progress
    }
    val targetAlpha = when (phase) {
        PullDownActionPhase.IDLE -> 0f
        PullDownActionPhase.PULLING -> (progress * 1.35f).coerceIn(0f, 1f)
        PullDownActionPhase.ARMED,
        PullDownActionPhase.TRIGGERING -> 1f
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(90, easing = FastOutSlowInEasing),
        label = "$surfaceId-alpha",
    )
    val width = 116.dp + 28.dp * progress
    val translation = (-12).dp + 18.dp * progress
    val emphasized = phase == PullDownActionPhase.ARMED ||
        phase == PullDownActionPhase.TRIGGERING
    val label = when (phase) {
        PullDownActionPhase.IDLE,
        PullDownActionPhase.PULLING -> "下拉搜索"
        PullDownActionPhase.ARMED -> "松开搜索"
        PullDownActionPhase.TRIGGERING -> "进入搜索"
    }

    GlassSurface(
        surfaceId = surfaceId,
        modifier = modifier
            .offset(y = topOffset + translation)
            .width(width)
            .height(40.dp),
        style = GlassSurfaceStyle(cornerRadius = 20.dp),
        surfaceAlpha = alpha,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = if (emphasized) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            AnimatedContent(
                targetState = label,
                transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
                label = "$surfaceId-label",
            ) { text ->
                Text(
                    text = text,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (emphasized) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

package com.par9uet.jm.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun FavoriteSyncIconButton(
    isSyncing: Boolean,
    hasError: Boolean,
    onClick: () -> Unit,
) {
    val rotationModifier = if (isSyncing) {
        val transition = rememberInfiniteTransition(label = "favoriteSync")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "favoriteSyncRotation",
        )
        Modifier.graphicsLayer { rotationZ = rotation }
    } else {
        Modifier
    }

    IconButton(
        onClick = { if (!isSyncing) onClick() },
    ) {
        Icon(
            imageVector = Icons.Rounded.Sync,
            contentDescription = when {
                hasError -> "收藏夹同步失败，点击重试"
                isSyncing -> "正在同步收藏夹"
                else -> "同步收藏夹"
            },
            modifier = rotationModifier,
            tint = if (hasError) {
                MaterialTheme.colorScheme.error
            } else {
                LocalContentColor.current
            },
        )
    }
}

package com.par9uet.jm.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Real-glass modal rendered INSIDE the page's existing GlassCaptureHost overlay.
 *
 * The backdrop blurs live page content through the shared registry: the scrim is a plain themed
 * translucent layer and [surface] becomes a normal GlassSurface, so API 31+ gets true Gaussian
 * backdrop blur and older devices get the existing translucent fallback. No extra capture host
 * is created; callers must place this in CommonScaffold overlayContent (or equivalent).
 *
 * Motion: enter fade ~200ms + scale 0.96->1 + small vertical offset; exit reverses. After exit
 * finishes nothing remains composed, so no invisible scrim can intercept input. Outside tap and
 * Back dismissal are individually configurable.
 */
@Composable
fun GlassModal(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceId: String = "glass-modal",
    dismissOnOutsideClick: Boolean = true,
    dismissOnBack: Boolean = true,
    alignment: Alignment = Alignment.Center,
    surface: @Composable () -> Unit,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val surfaceInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
            .then(
                if (dismissOnOutsideClick) {
                    Modifier.clickable(
                        interactionSource = scrimInteraction,
                        indication = null,
                        onClick = onDismissRequest,
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = alignment,
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(200)) +
                scaleIn(initialScale = 0.96f, animationSpec = tween(200)),
            exit = fadeOut(tween(160)) +
                scaleOut(targetScale = 0.96f, animationSpec = tween(160)),
        ) {
            GlassSurface(
                surfaceId = surfaceId,
                modifier = modifier.then(
                    if (dismissOnOutsideClick) {
                        Modifier.clickable(
                            interactionSource = surfaceInteraction,
                            indication = null,
                            onClick = {},
                        )
                    } else {
                        Modifier
                    }
                ),
                style = GlassSurfaceStyle(cornerRadius = 24.dp),
            ) {
                surface()
            }
        }
    }
}

/** Canonical glass confirmation layout used by destructive-action confirmations. */
@Composable
fun GlassConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceId: String = "glass-confirm-dialog",
    dismissText: String = "取消",
    destructive: Boolean = false,
) {
    GlassModal(
        onDismissRequest = onDismiss,
        modifier = modifier.widthIn(max = 420.dp),
        surfaceId = surfaceId,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text(dismissText)
                }
                TextButton(
                    onClick = onConfirm,
                    modifier = Modifier.heightIn(min = 44.dp),
                    colors = if (destructive) {
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) {
                    Text(confirmText)
                }
            }
        }
    }
}

package com.par9uet.jm.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp

/**
 * Real-glass modal rendered INSIDE the page's existing GlassCaptureHost overlay.
 *
 * The backdrop blurs live page content through the shared registry: the full-screen layer is
 * transparent but still handles outside taps, and [surface] becomes a normal GlassSurface, so
 * API 31+ gets true Gaussian backdrop blur and older devices get the existing translucent
 * fallback. No extra capture host is created; callers must place this in CommonScaffold
 * overlayContent (or equivalent).
 *
 * Motion: enter fade ~200ms + scale 0.96->1; exit reverses and the full-screen hit layer is
 * removed once the transition finishes, so no invisible layer can intercept input. Outside tap
 * and Back dismissal are individually configurable.
 *
 * [visible] is the logical visibility CONTROLLED BY THE CALLER (callers keep this composable
 * composed and flip [visible]); [onDismissRequest] fires for outside tap / Back so the caller
 * can set [visible] = false, after which the exit animation actually runs to completion.
 */
@Composable
fun GlassModal(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceId: String = "glass-modal",
    dismissOnOutsideClick: Boolean = true,
    dismissOnBack: Boolean = true,
    alignment: Alignment = Alignment.Center,
    surface: @Composable () -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = visible
    val transition = rememberTransition(
        transitionState = visibleState,
        label = "glass-modal-transition",
    )
    val surfaceAlpha by transition.animateFloat(
        transitionSpec = { tween(200) },
        label = "glass-modal-surface-alpha",
    ) { if (it) 1f else 0f }
    val surfaceScale by transition.animateFloat(
        transitionSpec = { tween(200) },
        label = "glass-modal-surface-scale",
    ) { if (it) 1f else 0.96f }
    val active = transition.currentState || transition.isRunning
    val scrimInteraction = remember { MutableInteractionSource() }
    val surfaceInteraction = remember { MutableInteractionSource() }
    if (dismissOnBack) {
        BackHandler(enabled = visible && active) {
            onDismissRequest()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (active) {
                    Modifier.clickable(
                        interactionSource = scrimInteraction,
                        indication = null,
                        onClick = {
                            if (dismissOnOutsideClick) onDismissRequest()
                        },
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = alignment,
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(160)),
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
                surfaceAlpha = surfaceAlpha,
                surfaceScale = surfaceScale,
            ) {
                surface()
            }
        }
    }
}

/** Canonical glass confirmation layout used by destructive-action confirmations. */
@Composable
fun GlassConfirmDialog(
    visible: Boolean,
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
        visible = visible,
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

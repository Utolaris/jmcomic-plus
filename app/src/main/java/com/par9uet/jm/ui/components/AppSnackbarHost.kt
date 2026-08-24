package com.par9uet.jm.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App-wide floating Snackbar presentation for [SnackbarHostState].
 *
 * Replaces the default strong inverse/black Material Snackbar with a themed floating surface:
 * surfaceContainerHigh background, soft 22dp corners, onSurface text, subtle elevation and a
 * short upward slide + fade (~200ms). The default dismiss X is dropped; an action label is only
 * rendered when a message actually carries one. Queue semantics come from [SnackbarHostState].
 */
@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val currentSnackbarData = hostState.currentSnackbarData

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = currentSnackbarData != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 12 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { it / 12 },
        ) {
            currentSnackbarData?.let { AppSnackbarVisuals(it) }
        }
    }
}

@Composable
private fun AppSnackbarVisuals(
    data: androidx.compose.material3.SnackbarData,
) {
    val visuals = data.visuals
    val actionLabel = visuals.actionLabel
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .widthIn(max = 640.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = visuals.message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionLabel != null) {
                TextButton(onClick = { data.performAction() }) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

package com.par9uet.jm.ui.glass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal enum class GlassMenuAlignment {
    START,
    CENTER,
    END,
}

@Stable
internal class GlassAnchoredMenuState {
    var expanded by mutableStateOf(false)
        private set
    var anchorBounds by mutableStateOf<Rect?>(null)
        private set

    fun open() {
        if (anchorBounds != null) expanded = true
    }

    fun dismiss() {
        expanded = false
    }

    fun updateAnchor(bounds: Rect) {
        anchorBounds = bounds
    }
}

@Composable
internal fun rememberGlassAnchoredMenuState(): GlassAnchoredMenuState =
    remember { GlassAnchoredMenuState() }

internal fun Modifier.glassMenuAnchor(state: GlassAnchoredMenuState): Modifier =
    onGloballyPositioned { state.updateAnchor(it.boundsInRoot()) }

internal fun calculateGlassMenuPosition(
    anchorBounds: Rect,
    rootSize: IntSize,
    menuSize: IntSize,
    marginPx: Int,
    gapPx: Int,
    alignment: GlassMenuAlignment,
): IntOffset {
    val preferredX = when (alignment) {
        GlassMenuAlignment.START -> anchorBounds.left
        GlassMenuAlignment.CENTER -> anchorBounds.center.x - menuSize.width / 2f
        GlassMenuAlignment.END -> anchorBounds.right - menuSize.width
    }.roundToInt()
    val maxX = (rootSize.width - menuSize.width - marginPx).coerceAtLeast(marginPx)
    val x = preferredX.coerceIn(marginPx, maxX)

    val below = anchorBounds.bottom.roundToInt() + gapPx
    val above = anchorBounds.top.roundToInt() - gapPx - menuSize.height
    val maxY = (rootSize.height - menuSize.height - marginPx).coerceAtLeast(marginPx)
    val y = when {
        below + menuSize.height <= rootSize.height - marginPx -> below
        above >= marginPx -> above
        else -> below.coerceIn(marginPx, maxY)
    }
    return IntOffset(x, y)
}

/** A real in-host glass menu; no Popup/window is created. */
@Composable
internal fun GlassAnchoredMenu(
    state: GlassAnchoredMenuState,
    surfaceId: String,
    modifier: Modifier = Modifier,
    alignment: GlassMenuAlignment = GlassMenuAlignment.START,
    width: Dp = 240.dp,
    menuMaxHeight: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!state.expanded) return
    val anchorBounds = state.anchorBounds ?: return
    BackHandler(onBack = state::dismiss)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val rootSize = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        val widthPx = with(density) { width.roundToPx() }
        val maxHeightPx = with(density) { menuMaxHeight.roundToPx() }
        val marginPx = with(density) { 8.dp.roundToPx() }
        val gapPx = with(density) { 6.dp.roundToPx() }
        var measuredMenuSize by remember { mutableStateOf(IntSize.Zero) }
        val position = calculateGlassMenuPosition(
            anchorBounds = anchorBounds,
            rootSize = rootSize,
            menuSize = measuredMenuSize.takeIf { it.width > 0 && it.height > 0 }
                ?: IntSize(widthPx, maxHeightPx),
            marginPx = marginPx,
            gapPx = gapPx,
            alignment = alignment,
        )
        val outsideInteraction = remember { MutableInteractionSource() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = outsideInteraction,
                    indication = null,
                    onClick = state::dismiss,
                ),
        )
        GlassSurface(
            surfaceId = surfaceId,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { position }
                .width(width)
                .heightIn(max = menuMaxHeight)
                .onSizeChanged { measuredMenuSize = it },
            style = GlassSurfaceStyle(cornerRadius = 22.dp),
            surfaceAlpha = if (measuredMenuSize == IntSize.Zero) 0f else 1f,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(7.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun GlassMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    selected: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "已选择",
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun GlassMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

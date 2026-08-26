package com.par9uet.jm.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize

internal data class GlassSurfaceBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal interface GlassSurfaceRegistry {
    fun updateSurface(
        surfaceId: String,
        style: GlassSurfaceStyle,
        alpha: Float,
        scale: Float,
        bounds: GlassSurfaceBounds,
    )

    fun updateSurfaceStyle(surfaceId: String, style: GlassSurfaceStyle, alpha: Float, scale: Float)

    fun removeSurface(surfaceId: String)
}

internal val LocalGlassSurfaceRegistry = staticCompositionLocalOf<GlassSurfaceRegistry?> { null }

/**
 * A Compose foreground container backed by a native glass surface registered with the nearest
 * [GlassCaptureHost]. The caller owns the size and position; only the background surface is
 * rendered by the host, while [content] stays sharp in this composition.
 */
@Composable
fun GlassSurface(
    surfaceId: String,
    modifier: Modifier = Modifier,
    style: GlassSurfaceStyle = GlassSurfaceStyle.Default,
    surfaceAlpha: Float = 1f,
    surfaceScale: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val registry = LocalGlassSurfaceRegistry.current
    val currentStyle = rememberUpdatedState(style)
    val currentAlpha = rememberUpdatedState(surfaceAlpha.coerceIn(0f, 1f))
    val currentScale = rememberUpdatedState(surfaceScale.coerceAtLeast(0.1f))

    DisposableEffect(registry, surfaceId) {
        onDispose {
            registry?.removeSurface(surfaceId)
        }
    }
    SideEffect {
        registry?.let {
            // Alpha, material and scale can animate without changing layout coordinates.
            it.updateSurfaceStyle(surfaceId, currentStyle.value, currentAlpha.value, currentScale.value)
        }
    }

    // GlassModal is also used by the onboarding flow, which intentionally has no page capture
    // host. Keep that fallback visibly surfaced while host-backed callers retain real backdrop
    // blur from the native GlassBackdropView.
    val fallbackModifier = if (registry == null) {
        Modifier.background(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(style.cornerRadius),
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(fallbackModifier)
            .alpha(currentAlpha.value)
            .graphicsLayer {
                scaleX = currentScale.value
                scaleY = currentScale.value
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .onGloballyPositioned { coordinates ->
                registry?.updateSurface(
                    surfaceId = surfaceId,
                    style = currentStyle.value,
                    alpha = currentAlpha.value,
                    scale = currentScale.value,
                    bounds = coordinates.toGlassSurfaceBounds(),
                )
            },
        content = content,
    )
}

internal fun LayoutCoordinates.toGlassSurfaceBounds(): GlassSurfaceBounds {
    val position = positionInWindow()
    val size: IntSize = this.size
    return GlassSurfaceBounds(
        left = position.x,
        top = position.y,
        right = position.x + size.width,
        bottom = position.y + size.height,
    )
}

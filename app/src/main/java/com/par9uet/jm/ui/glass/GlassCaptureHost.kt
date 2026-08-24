package com.par9uet.jm.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.par9uet.jm.ui.screens.LocalMainNavController
import com.par9uet.jm.ui.theme.ExtendedColorScheme
import com.par9uet.jm.ui.theme.ExtendedTheme
import com.par9uet.jm.ui.theme.LocalExtendedColors
import kotlin.math.roundToInt

/**
 * Hosts one source composition and one transparent foreground composition. Any number of
 * [GlassSurface] instances in the foreground share the source display list.
 */
@Composable
fun GlassCaptureHost(
    modifier: Modifier = Modifier,
    sourceContent: @Composable () -> Unit,
    overlayContent: @Composable () -> Unit,
) {
    val sourceContentState = rememberUpdatedState(sourceContent)
    val overlayContentState = rememberUpdatedState(overlayContent)
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    val extendedColors = ExtendedTheme.colors
    val mainNavController = LocalMainNavController.current
    val minimumInteractiveComponentSize = LocalMinimumInteractiveComponentSize.current
    val context = LocalContext.current

    AndroidView(
        modifier = modifier,
        factory = {
            GlassCaptureHostView(context).apply {
                setContents(
                    sourceContent = { sourceContentState.value() },
                    overlayContent = { overlayContentState.value() },
                )
                updateTheme(
                    colorScheme = colorScheme,
                    typography = typography,
                    shapes = shapes,
                    extendedColors = extendedColors,
                    mainNavController = mainNavController,
                    minimumInteractiveComponentSize = minimumInteractiveComponentSize,
                )
            }
        },
        update = { host ->
            host.updateTheme(
                colorScheme = colorScheme,
                typography = typography,
                shapes = shapes,
                extendedColors = extendedColors,
                mainNavController = mainNavController,
                minimumInteractiveComponentSize = minimumInteractiveComponentSize,
            )
        },
        onRelease = GlassCaptureHostView::dispose,
    )
}

internal class GlassCaptureHostView(context: Context) :
    FrameLayout(context),
    GlassSurfaceRegistry {

    private data class ThemeState(
        val colorScheme: ColorScheme,
        val typography: Typography,
        val shapes: Shapes,
        val extendedColors: ExtendedColorScheme,
        val mainNavController: NavHostController,
        val minimumInteractiveComponentSize: androidx.compose.ui.unit.Dp,
    )

    private data class RegisteredSurface(
        val style: GlassSurfaceStyle,
        val alpha: Float,
        val scale: Float = 1f,
        val bounds: GlassSurfaceBounds? = null,
    )

    private val sourceComposeView = ComposeView(context)
    private val overlayComposeView = ComposeView(context)
    private val sourceContentState = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val overlayContentState = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val themeState = mutableStateOf<ThemeState?>(null)
    private val registeredSurfaces = linkedMapOf<String, RegisteredSurface>()
    private val backdropViews = linkedMapOf<String, GlassBackdropView>()
    private var currentColorScheme: ColorScheme? = null
    private var isDarkTheme = false
    private var surfaceSyncPosted = false
    private var isCapturingSource = false
    private val sourceCapture = GlassCaptureSource(
        sourceView = sourceComposeView,
        onCaptureStart = { isCapturingSource = true },
        onCaptureEnd = { isCapturingSource = false },
    )

    init {
        setWillNotDraw(true)
        clipChildren = false

        sourceComposeView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        overlayComposeView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        sourceComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow,
        )
        overlayComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow,
        )
        overlayComposeView.isClickable = false
        overlayComposeView.isFocusable = false

        addView(sourceComposeView)
        addView(overlayComposeView)

        sourceComposeView.setContent {
            val theme = themeState.value
            val content = sourceContentState.value
            if (theme != null && content != null) {
                ThemedContent(theme) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                drawRect(theme.colorScheme.background)
                                drawContent()
                                if (!isCapturingSource) {
                                    onSourceDrawn()
                                }
                            },
                    ) {
                        content()
                    }
                }
            }
        }

        overlayComposeView.setContent {
            val theme = themeState.value
            val content = overlayContentState.value
            if (theme != null && content != null) {
                CompositionLocalProvider(LocalGlassSurfaceRegistry provides this@GlassCaptureHostView) {
                    ThemedContent(theme) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            content()
                        }
                    }
                }
            }
        }
    }

    fun setContents(
        sourceContent: @Composable () -> Unit,
        overlayContent: @Composable () -> Unit,
    ) {
        sourceContentState.value = sourceContent
        overlayContentState.value = overlayContent
        sourceCapture.markDirty()
        invalidate()
    }

    fun updateTheme(
        colorScheme: ColorScheme,
        typography: Typography,
        shapes: Shapes,
        extendedColors: ExtendedColorScheme,
        mainNavController: NavHostController,
        minimumInteractiveComponentSize: androidx.compose.ui.unit.Dp,
    ) {
        val newTheme = ThemeState(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            extendedColors = extendedColors,
            mainNavController = mainNavController,
            minimumInteractiveComponentSize = minimumInteractiveComponentSize,
        )
        val themeChanged = themeState.value != newTheme
        themeState.value = newTheme
        currentColorScheme = colorScheme
        isDarkTheme = colorScheme.background.luminance() < 0.5f
        setBackgroundColor(colorScheme.background.toArgb())
        if (themeChanged) {
            sourceCapture.markDirty()
            backdropViews.forEach { (surfaceId, view) ->
                val style = registeredSurfaces[surfaceId]?.style ?: GlassSurfaceStyle.Default
                view.setColors(colorsFor(style.material))
            }
            invalidate()
        }
    }

    override fun updateSurface(
        surfaceId: String,
        style: GlassSurfaceStyle,
        alpha: Float,
        scale: Float,
        bounds: GlassSurfaceBounds,
    ) {
        val next = RegisteredSurface(
            style = style,
            alpha = alpha.coerceIn(0f, 1f),
            scale = scale.coerceAtLeast(0.1f),
            bounds = bounds,
        )
        if (registeredSurfaces[surfaceId] == next) return
        registeredSurfaces[surfaceId] = next
        backdropViews[surfaceId]?.let { applySurfaceVisuals(it, next) }
        scheduleSurfaceSync()
    }

    override fun updateSurfaceStyle(
        surfaceId: String,
        style: GlassSurfaceStyle,
        alpha: Float,
        scale: Float,
    ) {
        val previous = registeredSurfaces[surfaceId]
        val next = RegisteredSurface(
            style = style,
            alpha = alpha.coerceIn(0f, 1f),
            scale = scale.coerceAtLeast(0.1f),
            bounds = previous?.bounds,
        )
        if (previous == next) return
        registeredSurfaces[surfaceId] = next
        backdropViews[surfaceId]?.let { applySurfaceVisuals(it, next) }
    }

    private fun applySurfaceVisuals(view: GlassBackdropView, registration: RegisteredSurface) {
        view.alpha = registration.alpha
        view.scaleX = registration.scale
        view.scaleY = registration.scale
        view.pivotX = 0f
        view.pivotY = 0f
        view.setSurfaceStyle(registration.style)
        view.setColors(colorsFor(registration.style.material))
    }

    override fun removeSurface(surfaceId: String) {
        registeredSurfaces.remove(surfaceId)
        backdropViews.remove(surfaceId)?.let(::removeView)
        scheduleSurfaceSync()
    }

    fun dispose() {
        sourceContentState.value = null
        overlayContentState.value = null
        registeredSurfaces.clear()
        backdropViews.values.forEach(::removeView)
        backdropViews.clear()
        sourceComposeView.disposeComposition()
        overlayComposeView.disposeComposition()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        sourceCapture.markDirty()
        backdropViews.values.forEach { it.markSurfacePositionChanged() }
        scheduleSurfaceSync()
        invalidate()
    }

    /** Draws source first, records it once, then draws all native glass views before Compose UI. */
    override fun dispatchDraw(canvas: Canvas) {
        val time = drawingTime
        drawChild(canvas, sourceComposeView, time)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sourceCapture.recordIfNeeded()
        }
        backdropViews.values.forEach { view ->
            if (view.visibility != View.GONE) {
                drawChild(canvas, view, time)
            }
        }
        drawChild(canvas, overlayComposeView, time)
    }

    private fun scheduleSurfaceSync() {
        if (surfaceSyncPosted) return
        surfaceSyncPosted = true
        postOnAnimation {
            surfaceSyncPosted = false
            syncSurfaceViews()
        }
    }

    private fun syncSurfaceViews() {
        // A surface can report an invalid/zero-size bound briefly while its Compose parent is
        // being remeasured. Keep the last native backdrop until GlassSurface explicitly removes
        // the surface; otherwise a later layout pass may not re-register an identical bound.
        val activeIds = registeredSurfaces.keys

        backdropViews.keys
            .filter { it !in activeIds }
            .toList()
            .forEach { surfaceId ->
                backdropViews.remove(surfaceId)?.let(::removeView)
            }

        val hostLocation = IntArray(2)
        getLocationInWindow(hostLocation)
        var layoutChanged = false
        registeredSurfaces.forEach { (surfaceId, registration) ->
            val bounds = registration.bounds ?: return@forEach
            if (bounds.width <= 0f || bounds.height <= 0f) return@forEach

            val view = backdropViews.getOrPut(surfaceId) {
                layoutChanged = true
                GlassBackdropView(context, registration.style).also {
                    it.setSource(sourceCapture, sourceComposeView)
                    it.setColors(colorsFor(registration.style.material))
                    addView(it, childCount - 1)
                }
            }
            view.setSurfaceStyle(registration.style)
            view.setColors(colorsFor(registration.style.material))
            view.alpha = registration.alpha
            view.scaleX = registration.scale
            view.scaleY = registration.scale
            view.pivotX = 0f
            view.pivotY = 0f

            val left = (bounds.left - hostLocation[0]).roundToInt()
            val top = (bounds.top - hostLocation[1]).roundToInt()
            val width = maxOf(1, bounds.width.roundToInt())
            val height = maxOf(1, bounds.height.roundToInt())
            val currentParams = view.layoutParams as? LayoutParams
            if (
                currentParams == null ||
                currentParams.leftMargin != left ||
                currentParams.topMargin != top ||
                currentParams.width != width ||
                currentParams.height != height
            ) {
                layoutChanged = true
                view.markSurfacePositionChanged()
                view.layoutParams = LayoutParams(width, height).apply {
                    leftMargin = left
                    topMargin = top
                }
            }
        }
        if (layoutChanged) requestLayout()
        invalidate()
    }

    private fun colorsFor(material: GlassMaterialStyle): GlassSurfaceColors {
        val colorScheme = currentColorScheme ?: return GlassSurfaceColors(
            tint = 0,
            topStroke = 0,
            bottomStroke = 0,
            shadow = 0,
        )
        return GlassSurfaceColors(
            tint = colorScheme.surfaceContainer.copy(alpha = material.tintAlpha).toArgb(),
            topStroke = if (isDarkTheme) 0x06FFFFFF else 0x11000000,
            bottomStroke = if (isDarkTheme) 0x11FFFFFF else 0x20000000,
            shadow = if (isDarkTheme) 0x04FFFFFF else 0x20000000,
        )
    }

    private fun onSourceDrawn() {
        if (!isCapturingSource) {
            sourceCapture.markDirty()
        }
    }

    @Composable
    private fun ThemedContent(theme: ThemeState, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalExtendedColors provides theme.extendedColors,
            LocalMainNavController provides theme.mainNavController,
            LocalMinimumInteractiveComponentSize provides theme.minimumInteractiveComponentSize,
        ) {
            MaterialTheme(
                colorScheme = theme.colorScheme,
                typography = theme.typography,
                shapes = theme.shapes,
                content = content,
            )
        }
    }
}

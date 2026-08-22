package com.par9uet.jm.ui.glass

import android.content.Context
import android.graphics.Canvas
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
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavHostController
import com.par9uet.jm.ui.screens.LocalMainNavController
import com.par9uet.jm.ui.theme.ExtendedColorScheme
import com.par9uet.jm.ui.theme.ExtendedTheme
import com.par9uet.jm.ui.theme.LocalExtendedColors

/**
 * Splits a Compose-first surface into a source composition and a foreground composition. The
 * native backdrop view sits between them and can therefore never capture its own output.
 */
@Composable
fun GlassCaptureHost(
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle.Default,
    navigationBarInset: Dp,
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
                updateStyle(style)
                updateNavigationBarInset(navigationBarInset)
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
            host.updateStyle(style)
            host.updateNavigationBarInset(navigationBarInset)
        },
        onRelease = GlassCaptureHostView::dispose,
    )
}

internal class GlassCaptureHostView(context: Context) : FrameLayout(context) {
    private data class ThemeState(
        val colorScheme: ColorScheme,
        val typography: Typography,
        val shapes: Shapes,
        val extendedColors: ExtendedColorScheme,
        val mainNavController: NavHostController,
        val minimumInteractiveComponentSize: Dp,
    )

    private val sourceComposeView = ComposeView(context)
    private val overlayComposeView = ComposeView(context)
    private val backdropView = GlassBackdropView(context, GlassStyle.Default)
    private val sourceContentState = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val overlayContentState = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val themeState = mutableStateOf<ThemeState?>(null)
    private var style = GlassStyle.Default
    private var navigationBarInsetPx = 0
    private var isCapturingSource = false

    init {
        setWillNotDraw(true)
        clipChildren = false

        sourceComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow,
        )
        overlayComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow,
        )
        overlayComposeView.isClickable = false
        overlayComposeView.isFocusable = false

        addView(sourceComposeView)
        addView(backdropView)
        addView(overlayComposeView)

        backdropView.setSource(sourceComposeView) { canvas ->
            isCapturingSource = true
            try {
                sourceComposeView.draw(canvas)
            } finally {
                isCapturingSource = false
            }
        }

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
                ThemedContent(theme) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
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
        backdropView.markSourceDirty()
    }

    fun updateTheme(
        colorScheme: ColorScheme,
        typography: Typography,
        shapes: Shapes,
        extendedColors: ExtendedColorScheme,
        mainNavController: NavHostController,
        minimumInteractiveComponentSize: Dp,
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
        val dark = colorScheme.background.luminance() < 0.5f
        backdropView.setColors(
            GlassSurfaceColors(
                tint = colorScheme.surfaceContainer.copy(alpha = style.tintAlpha).toArgb(),
                topStroke = if (dark) 0x06FFFFFF else 0x11000000,
                bottomStroke = if (dark) 0x11FFFFFF else 0x20000000,
                shadow = if (dark) 0x04FFFFFF else 0x20000000,
            ),
        )
        if (themeChanged) backdropView.markSourceDirty()
    }

    fun updateStyle(newStyle: GlassStyle) {
        if (style == newStyle) return
        style = newStyle
        backdropView.setStyle(newStyle)
        themeState.value?.let { updateThemeFromState(it) }
        requestLayout()
    }

    fun updateNavigationBarInset(inset: Dp) {
        val insetPx = (inset.value * resources.displayMetrics.density).toInt().coerceAtLeast(0)
        if (navigationBarInsetPx == insetPx) return
        navigationBarInsetPx = insetPx
        requestLayout()
        backdropView.markSourceDirty()
    }

    fun dispose() {
        sourceContentState.value = null
        overlayContentState.value = null
        sourceComposeView.disposeComposition()
        overlayComposeView.disposeComposition()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)

        sourceComposeView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        val surfaceHeight = minOf(
            height,
            (style.barHeight.value * resources.displayMetrics.density).toInt() +
                (style.outerMargin.value * resources.displayMetrics.density).toInt() +
                navigationBarInsetPx,
        )
        val surfaceWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val surfaceHeightSpec = MeasureSpec.makeMeasureSpec(surfaceHeight, MeasureSpec.EXACTLY)
        backdropView.measure(surfaceWidthSpec, surfaceHeightSpec)
        overlayComposeView.measure(surfaceWidthSpec, surfaceHeightSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        sourceComposeView.layout(0, 0, width, height)

        val surfaceHeight = backdropView.measuredHeight
        val surfaceTop = height - surfaceHeight
        backdropView.layout(0, surfaceTop, width, height)
        overlayComposeView.layout(0, surfaceTop, width, height)
        if (changed) backdropView.markSourceDirty()
    }

    private fun onSourceDrawn() {
        if (!isCapturingSource) {
            backdropView.markSourceDirty()
        }
    }

    private fun updateThemeFromState(theme: ThemeState) {
        updateTheme(
            colorScheme = theme.colorScheme,
            typography = theme.typography,
            shapes = theme.shapes,
            extendedColors = theme.extendedColors,
            mainNavController = theme.mainNavController,
            minimumInteractiveComponentSize = theme.minimumInteractiveComponentSize,
        )
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

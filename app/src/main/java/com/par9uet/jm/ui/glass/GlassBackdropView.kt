package com.par9uet.jm.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal data class GlassSurfaceColors(
    val tint: Int,
    val topStroke: Int,
    val bottomStroke: Int,
    val shadow: Int,
)

/**
 * Draws only the native glass surface. The sharp tab foreground is a sibling ComposeView owned by
 * [GlassCaptureHostView], so it is never part of this RenderNode source.
 */
@SuppressLint("ViewConstructor")
internal class GlassBackdropView(
    context: Context,
    private var style: GlassStyle,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val barRect = RectF()
    private val glassPath = Path()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val bottomStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var colors = GlassSurfaceColors(
        tint = Color.TRANSPARENT,
        topStroke = Color.TRANSPARENT,
        bottomStroke = Color.TRANSPARENT,
        shadow = Color.TRANSPARENT,
    )
    private var sourceView: View? = null
    private var sourceRenderer: ((Canvas) -> Unit)? = null
    private var sourceDirty = true
    private var geometryDirty = true
    private var nativeRenderState: NativeGlassRenderState? = createNativeRenderState(style)
    private var nativeRenderFailed = false

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        shadowPaint.style = Paint.Style.FILL
        tintPaint.style = Paint.Style.FILL
        updatePaintMetrics()
    }

    fun setStyle(newStyle: GlassStyle) {
        if (style == newStyle) return
        style = newStyle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            nativeRenderState?.setBlurRadius(style.blurRadiusPx())
        }
        updatePaintMetrics()
        geometryDirty = true
        sourceDirty = true
        requestLayout()
        invalidate()
    }

    fun setColors(newColors: GlassSurfaceColors) {
        if (colors == newColors) return
        colors = newColors
        tintPaint.color = colors.tint
        topStrokePaint.color = colors.topStroke
        bottomStrokePaint.color = colors.bottomStroke
        shadowPaint.color = Color.argb(1, 0, 0, 0)
        shadowPaint.setShadowLayer(
            style.shadowRadiusPx(),
            0f,
            style.shadowDyPx(),
            colors.shadow,
        )
        invalidate()
    }

    private fun updatePaintMetrics() {
        val strokeWidth = style.borderWidth.value * density
        topStrokePaint.strokeWidth = strokeWidth
        bottomStrokePaint.strokeWidth = strokeWidth
        shadowPaint.setShadowLayer(
            style.shadowRadiusPx(),
            0f,
            style.shadowDyPx(),
            colors.shadow,
        )
    }

    fun setSource(source: View, renderer: (Canvas) -> Unit) {
        sourceView = source
        sourceRenderer = renderer
        sourceDirty = true
        invalidate()
    }

    fun markSourceDirty() {
        if (!sourceDirty) {
            sourceDirty = true
            invalidate()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        geometryDirty = true
        sourceDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateGeometryIfNeeded()
        if (barRect.isEmpty) return

        canvas.drawRoundRect(barRect, cornerRadiusPx(), cornerRadiusPx(), shadowPaint)

        if (
            !nativeRenderFailed &&
            nativeRenderState != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            canvas.isHardwareAccelerated &&
            sourceDirty
        ) {
            recordSourceRegion()
        }

        if (
            !nativeRenderFailed &&
            nativeRenderState != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            canvas.isHardwareAccelerated
        ) {
            drawNativeBackdrop(canvas)
        }

        // On API 23-30 this translucent tint is the complete fallback. On API 31+ it is drawn over
        // the blurred RenderNode, keeping the same material geometry on every supported device.
        canvas.drawRoundRect(barRect, cornerRadiusPx(), cornerRadiusPx(), tintPaint)
        drawDirectionalStroke(canvas, topStrokePaint, clipTop = true)
        drawDirectionalStroke(canvas, bottomStrokePaint, clipTop = false)
    }

    private fun updateGeometryIfNeeded() {
        if (!geometryDirty) return

        val margin = style.outerMarginPx()
        val maxWidth = style.maxBarWidthPx()
        val width = max(0f, min(maxWidth, this.width.toFloat() - margin * 2f))
        val left = (this.width - width) / 2f
        val height = min(style.barHeightPx(), this.height.toFloat())
        barRect.set(left, 0f, left + width, height)

        glassPath.rewind()
        glassPath.addRoundRect(
            barRect,
            cornerRadiusPx(),
            cornerRadiusPx(),
            Path.Direction.CW,
        )
        geometryDirty = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            nativeRenderState?.setPosition(
                max(1, ceil(barRect.width() + style.blurPaddingPx() * 2f).toInt()),
                max(1, ceil(barRect.height() + style.blurPaddingPx() * 2f).toInt()),
            )
        }
    }

    @RequiresApi(31)
    private fun recordSourceRegion() {
        val source = sourceView ?: return
        val renderer = sourceRenderer ?: return
        if (source.width <= 0 || source.height <= 0 || barRect.width() <= 0f || barRect.height() <= 0f) {
            return
        }

        val padding = style.blurPaddingPx()
        val captureLeftInSurface = barRect.left - padding
        val captureTopInSurface = barRect.top - padding
        val captureWidth = max(1, ceil(barRect.width() + padding * 2f).toInt())
        val captureHeight = max(1, ceil(barRect.height() + padding * 2f).toInt())

        val sourceLocation = IntArray(2)
        val surfaceLocation = IntArray(2)
        source.getLocationInWindow(sourceLocation)
        getLocationInWindow(surfaceLocation)
        val captureLeftInSource =
            surfaceLocation[0] + captureLeftInSurface - sourceLocation[0]
        val captureTopInSource =
            surfaceLocation[1] + captureTopInSurface - sourceLocation[1]

        try {
            nativeRenderState?.record(
                width = captureWidth,
                height = captureHeight,
                sourceLeft = captureLeftInSource,
                sourceTop = captureTopInSource,
                renderer = renderer,
            )
            sourceDirty = false
        } catch (_: RuntimeException) {
            // A device-specific hardware renderer failure should degrade to the same translucent
            // material as pre-31 instead of taking down the primary navigation surface.
            nativeRenderFailed = true
        }
    }

    @RequiresApi(31)
    private fun drawNativeBackdrop(canvas: Canvas) {
        val nativeState = nativeRenderState ?: return
        val padding = style.blurPaddingPx()
        canvas.save()
        canvas.clipPath(glassPath)
        canvas.translate(barRect.left - padding, barRect.top - padding)
        canvas.drawRenderNode(nativeState.renderNode)
        canvas.restore()
    }

    private fun drawDirectionalStroke(canvas: Canvas, paint: Paint, clipTop: Boolean) {
        canvas.save()
        val split = barRect.top + barRect.height() / 2f
        if (clipTop) {
            canvas.clipRect(barRect.left, barRect.top, barRect.right, split)
        } else {
            canvas.clipRect(barRect.left, split, barRect.right, barRect.bottom)
        }
        canvas.drawRoundRect(barRect, cornerRadiusPx(), cornerRadiusPx(), paint)
        canvas.restore()
    }

    private fun cornerRadiusPx(): Float = min(style.cornerRadiusPx(), barRect.height() / 2f)

    private fun GlassStyle.blurPaddingPx(): Float = blurRadiusPx()

    private fun GlassStyle.barHeightPx(): Float = barHeight.value * density
    private fun GlassStyle.outerMarginPx(): Float = outerMargin.value * density
    private fun GlassStyle.maxBarWidthPx(): Float = maxBarWidth.value * density
    private fun GlassStyle.cornerRadiusPx(): Float = cornerRadius.value * density
    private fun GlassStyle.blurRadiusPx(): Float = blurRadius.value * density
    private fun GlassStyle.shadowRadiusPx(): Float = shadowRadius.value * density
    private fun GlassStyle.shadowDyPx(): Float = shadowDy.value * density

    @RequiresApi(31)
    private class NativeGlassRenderState(initialBlurRadius: Float) {
        val renderNode = android.graphics.RenderNode("JmGlassBackdropSource")
        private var blurRadius = 0f

        init {
            renderNode.setClipToBounds(true)
            setBlurRadius(initialBlurRadius)
        }

        fun setPosition(width: Int, height: Int) {
            renderNode.setPosition(0, 0, width, height)
        }

        fun setBlurRadius(radius: Float) {
            if (blurRadius == radius) return
            blurRadius = radius
            renderNode.setRenderEffect(
                if (radius > 0f) {
                    android.graphics.RenderEffect.createBlurEffect(
                        radius,
                        radius,
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                } else {
                    null
                },
            )
        }

        fun record(
            width: Int,
            height: Int,
            sourceLeft: Float,
            sourceTop: Float,
            renderer: (Canvas) -> Unit,
        ) {
            renderNode.setPosition(0, 0, width, height)
            val recordingCanvas = renderNode.beginRecording(width, height)
            recordingCanvas.save()
            recordingCanvas.translate(-sourceLeft, -sourceTop)
            renderer(recordingCanvas)
            recordingCanvas.restore()
            renderNode.endRecording()
        }
    }

    private fun createNativeRenderState(style: GlassStyle): NativeGlassRenderState? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            NativeGlassRenderState(style.blurRadiusPx())
        } else {
            null
        }
    }
}

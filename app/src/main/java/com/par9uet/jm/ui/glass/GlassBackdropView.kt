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
 * Draws one native glass surface from the shared source display list. The sharp foreground is
 * rendered by the sibling overlay ComposeView and is therefore never part of this view's source.
 */
@SuppressLint("ViewConstructor")
internal class GlassBackdropView(
    context: Context,
    private var style: GlassSurfaceStyle = GlassSurfaceStyle.Default,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val surfaceRect = RectF()
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
    private var source: GlassCaptureSource? = null
    private var sourceView: View? = null
    private var lastSourceGeneration = -1
    private var sourceRegionDirty = true
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

    fun setSurfaceStyle(newStyle: GlassSurfaceStyle) {
        if (style == newStyle) return
        style = newStyle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            nativeRenderState?.setBlurRadius(style.material.blurRadiusPx())
        }
        updatePaintMetrics()
        sourceRegionDirty = true
        geometryDirty = true
        invalidate()
    }

    fun setSource(newSource: GlassCaptureSource, newSourceView: View) {
        source = newSource
        sourceView = newSourceView
        lastSourceGeneration = -1
        sourceRegionDirty = true
        invalidate()
    }

    fun markSurfacePositionChanged() {
        sourceRegionDirty = true
        invalidate()
    }

    fun setColors(newColors: GlassSurfaceColors) {
        if (colors == newColors) return
        colors = newColors
        tintPaint.color = colors.tint
        topStrokePaint.color = colors.topStroke
        bottomStrokePaint.color = colors.bottomStroke
        shadowPaint.color = Color.argb(1, 0, 0, 0)
        updatePaintMetrics()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        geometryDirty = true
        sourceRegionDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateGeometryIfNeeded()
        if (surfaceRect.isEmpty) return

        canvas.drawRoundRect(surfaceRect, cornerRadiusPx(), cornerRadiusPx(), shadowPaint)

        val sharedSource = source
        if (
            !nativeRenderFailed &&
            nativeRenderState != null &&
            sharedSource != null &&
            sharedSource.nativeCaptureAvailable &&
            canvas.isHardwareAccelerated &&
            (sourceRegionDirty || lastSourceGeneration != sharedSource.generation)
        ) {
            recordSourceRegion(sharedSource)
        }

        if (
            !nativeRenderFailed &&
            nativeRenderState != null &&
            sharedSource?.nativeCaptureAvailable == true &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            canvas.isHardwareAccelerated &&
            !sourceRegionDirty &&
            lastSourceGeneration == sharedSource.generation
        ) {
            drawNativeBackdrop(canvas)
        }

        // On API 23-30 this translucent tint is the complete fallback. On API 31+ it is drawn over
        // the blurred source RenderNode, keeping the same material geometry on every device.
        canvas.drawRoundRect(surfaceRect, cornerRadiusPx(), cornerRadiusPx(), tintPaint)
        drawDirectionalStroke(canvas, topStrokePaint, clipTop = true)
        drawDirectionalStroke(canvas, bottomStrokePaint, clipTop = false)
    }

    private fun updateGeometryIfNeeded() {
        if (!geometryDirty) return

        surfaceRect.set(0f, 0f, width.toFloat(), height.toFloat())
        glassPath.rewind()
        glassPath.addRoundRect(
            surfaceRect,
            cornerRadiusPx(),
            cornerRadiusPx(),
            Path.Direction.CW,
        )
        geometryDirty = false
    }

    @RequiresApi(31)
    private fun recordSourceRegion(sharedSource: GlassCaptureSource) {
        val sourceView = sourceView ?: return
        if (sourceView.width <= 0 || sourceView.height <= 0) return

        val padding = style.material.blurRadiusPx()
        val captureLeftInSurface = -padding
        val captureTopInSurface = -padding
        val captureWidth = max(1, ceil(surfaceRect.width() + padding * 2f).toInt())
        val captureHeight = max(1, ceil(surfaceRect.height() + padding * 2f).toInt())

        val sourceLocation = IntArray(2)
        val surfaceLocation = IntArray(2)
        sourceView.getLocationInWindow(sourceLocation)
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
                sourceNode = sharedSource.renderNode,
            )
            lastSourceGeneration = sharedSource.generation
            sourceRegionDirty = false
        } catch (_: RuntimeException) {
            // A device-specific hardware renderer failure degrades to the same translucent
            // material as pre-31 instead of taking down the screen.
            nativeRenderFailed = true
        }
    }

    @RequiresApi(31)
    private fun drawNativeBackdrop(canvas: Canvas) {
        val nativeState = nativeRenderState ?: return
        val padding = style.material.blurRadiusPx()
        canvas.save()
        canvas.clipPath(glassPath)
        canvas.translate(-padding, -padding)
        canvas.drawRenderNode(nativeState.renderNode)
        canvas.restore()
    }

    private fun drawDirectionalStroke(canvas: Canvas, paint: Paint, clipTop: Boolean) {
        canvas.save()
        val split = surfaceRect.top + surfaceRect.height() / 2f
        if (clipTop) {
            canvas.clipRect(surfaceRect.left, surfaceRect.top, surfaceRect.right, split)
        } else {
            canvas.clipRect(surfaceRect.left, split, surfaceRect.right, surfaceRect.bottom)
        }
        canvas.drawRoundRect(surfaceRect, cornerRadiusPx(), cornerRadiusPx(), paint)
        canvas.restore()
    }

    private fun updatePaintMetrics() {
        val material = style.material
        val strokeWidth = material.borderWidth.value * density
        topStrokePaint.strokeWidth = strokeWidth
        bottomStrokePaint.strokeWidth = strokeWidth
        shadowPaint.setShadowLayer(
            material.shadowRadius.value * density,
            0f,
            material.shadowDy.value * density,
            colors.shadow,
        )
    }

    private fun cornerRadiusPx(): Float = min(
        style.cornerRadius.value * density,
        surfaceRect.height() / 2f,
    )

    private fun GlassMaterialStyle.blurRadiusPx(): Float = blurRadius.value * density

    @RequiresApi(31)
    private class NativeGlassRenderState(initialBlurRadius: Float) {
        val renderNode = android.graphics.RenderNode("JmGlassSurface")
        private var blurRadius = 0f

        init {
            renderNode.setClipToBounds(true)
            setBlurRadius(initialBlurRadius)
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
            sourceNode: android.graphics.RenderNode,
        ) {
            renderNode.setPosition(0, 0, width, height)
            val recordingCanvas = renderNode.beginRecording(width, height)
            recordingCanvas.save()
            recordingCanvas.translate(-sourceLeft, -sourceTop)
            recordingCanvas.drawRenderNode(sourceNode)
            recordingCanvas.restore()
            renderNode.endRecording()
        }
    }

    private fun createNativeRenderState(style: GlassSurfaceStyle): NativeGlassRenderState? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            NativeGlassRenderState(style.material.blurRadius.value * density)
        } else {
            null
        }
    }
}

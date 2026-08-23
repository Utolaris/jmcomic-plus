package com.par9uet.jm.ui.glass

import android.graphics.RenderNode
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Records the one logical source composition shared by every glass surface in a host. It stores
 * only a GPU display list; no bitmap or pixel buffer is allocated.
 */
internal class GlassCaptureSource(
    private val sourceView: View,
    private val onCaptureStart: () -> Unit,
    private val onCaptureEnd: () -> Unit,
) {
    private var dirty = true
    private var captureFailed = false
    private var generationValue = 0
    private var renderNodeValue: RenderNode? = null

    @get:RequiresApi(31)
    val renderNode: RenderNode
        get() = renderNodeValue ?: RenderNode("JmGlassSharedSource").also {
            it.setClipToBounds(true)
            renderNodeValue = it
        }

    val generation: Int get() = generationValue
    val nativeCaptureAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !captureFailed

    fun markDirty() {
        dirty = true
    }

    @RequiresApi(31)
    fun recordIfNeeded() {
        if (!nativeCaptureAvailable || !dirty || sourceView.width <= 0 || sourceView.height <= 0) {
            return
        }

        try {
            renderNode.setPosition(0, 0, sourceView.width, sourceView.height)
            val recordingCanvas = renderNode.beginRecording(sourceView.width, sourceView.height)
            onCaptureStart()
            try {
                sourceView.draw(recordingCanvas)
            } finally {
                onCaptureEnd()
                renderNode.endRecording()
            }
            dirty = false
            generationValue++
        } catch (_: RuntimeException) {
            captureFailed = true
        }
    }
}

package com.par9uet.jm.ui.glass

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView

/**
 * The source composition of a [GlassCaptureHostView]. Instead of re-capturing on every draw,
 * the capture is marked dirty exactly when this view tree reports a real invalidation
 * ([invalidate] from a recomposition / draw change) or a layout request ([requestLayout]).
 * A static source therefore never schedules another capture.
 */
internal class GlassSourceComposeView(context: Context) : AbstractComposeView(context) {
    var contentCallback: (@Composable () -> Unit)? = null
    var onSourceInvalidated: (() -> Unit)? = null

    @Composable
    override fun Content() {
        contentCallback?.invoke()
    }

    override val shouldCreateCompositionOnAttachedToWindow: Boolean
        get() = contentCallback != null

    override fun onDescendantInvalidated(child: View, target: View) {
        onSourceInvalidated?.invoke()
        super.onDescendantInvalidated(child, target)
    }

    override fun requestLayout() {
        onSourceInvalidated?.invoke()
        super.requestLayout()
    }
}

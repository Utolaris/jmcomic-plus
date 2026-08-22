package com.par9uet.jm.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared geometry and material constants for the first glass consumer.
 *
 * The blur value is intentionally an experimental starting point. It is not an exact NagramX
 * constant: NagramX currently performs its glass blur in a larger downscaled pipeline.
 */
@Immutable
data class GlassStyle(
    val barHeight: Dp = 56.dp,
    val outerMargin: Dp = 8.dp,
    val maxBarWidth: Dp = 250.dp,
    val cornerRadius: Dp = 28.dp,
    val blurRadius: Dp = 18.dp,
    val tintAlpha: Float = 0.76f,
    val selectedIndicatorAlpha: Float = 0.09f,
    val borderWidth: Dp = 0.4.dp,
    val shadowRadius: Dp = 2.667.dp,
    val shadowDy: Dp = 0.85.dp,
) {
    companion object {
        val Default = GlassStyle()
    }
}

object GlassCapabilities {
    const val NativeBackdropBlurApi = 31

    fun usesNativeBackdropBlur(apiLevel: Int): Boolean = apiLevel >= NativeBackdropBlurApi
}

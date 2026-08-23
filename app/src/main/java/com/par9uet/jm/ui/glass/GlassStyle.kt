package com.par9uet.jm.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared material constants for every glass consumer.
 *
 * The blur value is intentionally an experimental starting point. It is not an exact NagramX
 * constant: NagramX currently performs its glass blur in a larger downscaled pipeline.
 */
@Immutable
data class GlassMaterialStyle(
    val blurRadius: Dp = 18.dp,
    val tintAlpha: Float = 0.76f,
    val borderWidth: Dp = 0.4.dp,
    val shadowRadius: Dp = 2.667.dp,
    val shadowDy: Dp = 0.85.dp,
) {
    companion object {
        val Default = GlassMaterialStyle()
    }
}

@Immutable
data class GlassSurfaceStyle(
    val cornerRadius: Dp = 28.dp,
    val material: GlassMaterialStyle = GlassMaterialStyle.Default,
) {
    companion object {
        val Default = GlassSurfaceStyle()
    }
}

/**
 * Primary navigation geometry plus the shared material. Other consumers own their size and only
 * use [GlassSurfaceStyle]. The material accessors keep the accepted primary-bar values readable
 * at existing call sites.
 */
@Immutable
data class GlassStyle(
    val barHeight: Dp = 56.dp,
    val outerMargin: Dp = 8.dp,
    val maxBarWidth: Dp = 250.dp,
    val cornerRadius: Dp = 28.dp,
    val material: GlassMaterialStyle = GlassMaterialStyle.Default,
    val selectedIndicatorAlpha: Float = 0.09f,
) {
    val blurRadius: Dp get() = material.blurRadius
    val tintAlpha: Float get() = material.tintAlpha
    val borderWidth: Dp get() = material.borderWidth
    val shadowRadius: Dp get() = material.shadowRadius
    val shadowDy: Dp get() = material.shadowDy

    companion object {
        val Default = GlassStyle()
    }
}

object GlassCapabilities {
    const val NativeBackdropBlurApi = 31

    fun usesNativeBackdropBlur(apiLevel: Int): Boolean = apiLevel >= NativeBackdropBlurApi
}

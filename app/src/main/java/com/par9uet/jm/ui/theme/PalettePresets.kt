package com.par9uet.jm.ui.theme

import androidx.compose.ui.graphics.Color
import com.par9uet.jm.data.models.COLOR_PALETTE_PRESET_DEFAULT

internal data class ColorPreset(
    val id: String,
    val name: String,
    val colors: List<Long>,
    val darkColors: List<Long> = colors,
)

/** Preset colors shared by the settings preview and the active app theme. */
internal val colorPresets = listOf(
    ColorPreset(COLOR_PALETTE_PRESET_DEFAULT, "默认蓝",
        colors = listOf(0xFF4F5F7F, 0xFF5A5D72, 0xFF75546F, 0xFFBA1A1A),
        darkColors = listOf(0xFFB8C7EF, 0xFFC2C5DD, 0xFFE4BAD8, 0xFFFFB4AB),
    ),
    ColorPreset("ocean", "海洋青",
        colors = listOf(0xFF00696D, 0xFF4A6364, 0xFF48607E, 0xFFBA1A1A),
        darkColors = listOf(0xFF37C9CD, 0xFFB1CBCB, 0xFFB0C8E8, 0xFFFFB4AB),
    ),
    ColorPreset("sunset", "日落橙",
        colors = listOf(0xFF8C5000, 0xFF735C2D, 0xFF9C4146, 0xFFBA1A1A),
        darkColors = listOf(0xFFFFB866, 0xFFE0C68F, 0xFFFFB3B5, 0xFFFFB4AB),
    ),
    ColorPreset("forest", "森林绿",
        colors = listOf(0xFF2E6B3E, 0xFF4F6352, 0xFF38656A, 0xFFBA1A1A),
        darkColors = listOf(0xFF7CDFA0, 0xFFB6CCBC, 0xFFA0D0D3, 0xFFFFB4AB),
    ),
    ColorPreset("lavender", "薰衣紫",
        colors = listOf(0xFF6750A4, 0xFF625B71, 0xFF7D5260, 0xFFBA1A1A),
        darkColors = listOf(0xFFD0BCFF, 0xFFCCC2DC, 0xFFEFB8C8, 0xFFFFB4AB),
    ),
)

internal fun String.toColorOrNull(): Color? {
    return runCatching {
        val hex = this.removePrefix("#")
        val long = if (hex.length == 6) "FF$hex".toLong(16) else hex.toLong(16)
        Color(long.toInt())
    }.getOrNull()
}


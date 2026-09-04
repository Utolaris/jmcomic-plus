package com.par9uet.jm.utils

import java.util.Locale

private val BYTE_UNITS = arrayOf("B", "KB", "MB", "GB")

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"

    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < BYTE_UNITS.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, BYTE_UNITS[unitIndex])
}

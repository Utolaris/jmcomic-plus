package com.par9uet.jm.utils

import android.graphics.Bitmap
import java.io.OutputStream

fun Bitmap.compressWebpCompat(quality: Int, stream: OutputStream): Boolean {
    return compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, stream)
}

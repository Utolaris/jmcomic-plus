package com.par9uet.jm.data.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

private const val DEFAULT_READER_ASPECT_RATIO = 9f / 16f

/**
 * Reader metadata owned by the UI list. Decoded Bitmaps live in ReaderImagePipeline rather than
 * in this long-lived chapter item, so a long chapter does not retain every page image.
 */
class ComicPicImageState(
    val index: Int,
    val comicId: Int,
    val originSrc: String,
    val __scrambleId: Int,
    val __speed: String,
    internal val imageFetcher: (suspend () -> ByteArray?)? = null,
) {
    var aspectRatio by mutableFloatStateOf(DEFAULT_READER_ASPECT_RATIO)
        private set

    fun updateAspectRatio(value: Float) {
        if (value.isFinite() && value > 0f) {
            aspectRatio = value.coerceIn(0.05f, 8f)
        }
    }
}

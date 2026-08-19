package com.par9uet.jm.data.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import com.par9uet.jm.reader.ReaderPage
import com.par9uet.jm.reader.ReaderPageKey
import java.io.File

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
    private val imageFetcher: (suspend () -> ByteArray?)? = null,
) {
    var aspectRatio by mutableFloatStateOf(DEFAULT_READER_ASPECT_RATIO)
        private set

    val pageKey: ReaderPageKey
        get() = ReaderPageKey(
            comicId = comicId,
            pageIndex = index,
            sourceIdentity = originSrc,
            scrambleId = __scrambleId,
            speed = __speed,
        )

    fun toReaderPage(): ReaderPage = ReaderPage(
        key = pageKey,
        originSrc = originSrc,
        comicId = comicId,
        pageIndex = index,
        scrambleId = __scrambleId,
        speed = __speed,
        localFile = File(originSrc).takeIf(File::isFile),
        fallbackFetcher = imageFetcher,
    )

    fun updateAspectRatio(value: Float) {
        if (value.isFinite() && value > 0f) {
            aspectRatio = value.coerceIn(0.05f, 8f)
        }
    }
}

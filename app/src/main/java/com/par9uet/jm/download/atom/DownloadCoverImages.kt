package com.par9uet.jm.download.atom

import android.content.Context
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.ImageResult

fun interface DownloadCoverImages {
    suspend fun load(url: String, cacheKey: String): ImageResult
}

class CoilDownloadCoverImages(
    private val context: Context,
    private val imageLoader: ImageLoader,
) : DownloadCoverImages {
    override suspend fun load(url: String, cacheKey: String): ImageResult {
        val request = ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .allowHardware(false)
            .build()
        return imageLoader.execute(request)
    }
}

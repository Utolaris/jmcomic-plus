package com.par9uet.jm.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.request.ImageRequest
import com.par9uet.jm.BuildConfig
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.coil.jmCoverCacheKey
import com.par9uet.jm.coil.nextCoverCandidateUrl
import com.par9uet.jm.image.normalizeJmImageHost
import com.par9uet.jm.utils.log
import org.koin.compose.getKoin

/** Coil-backed JM cover with one-at-a-time CDN fallback and one logical cache identity. */
@Composable
internal fun JmCoverImage(
    comicId: Int,
    remoteHost: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    imageLoader: ImageLoader = getKoin().get(),
    resolver: CoverImageHostResolver = getKoin().get(),
) {
    val context = LocalContext.current
    val preferredHost by resolver.preferredHost.collectAsState()
    val candidateUrls = remember(comicId, remoteHost, preferredHost) {
        resolver.coverUrls(comicId, remoteHost)
    }
    var attemptedUrls by remember(comicId, remoteHost) { mutableStateOf(emptySet<String>()) }
    val candidateUrl = nextCoverCandidateUrl(candidateUrls, attemptedUrls)
    val cacheKey = remember(comicId) { jmCoverCacheKey(comicId) }
    val request = remember(context, candidateUrl, cacheKey) {
        candidateUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .build()
        }
    }

    AsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        onSuccess = { state ->
            val loadedUrl = candidateUrl ?: return@AsyncImage
            if (state.result.dataSource == DataSource.NETWORK) {
                resolver.recordSuccess(loadedUrl)
            }
            if (BuildConfig.DEBUG) {
                log(
                    "CoverImage",
                    "JM$comicId loaded from ${normalizeJmImageHost(loadedUrl)} " +
                        "(${state.result.dataSource})",
                )
            }
        },
        onError = { state ->
            val failedUrl = candidateUrl ?: return@AsyncImage
            resolver.recordFailure(failedUrl)
            val updatedAttempts = attemptedUrls + failedUrl
            val nextUrl = nextCoverCandidateUrl(candidateUrls, updatedAttempts)
            if (BuildConfig.DEBUG) {
                log(
                    "CoverImage",
                    "JM$comicId host ${normalizeJmImageHost(failedUrl)} failed: " +
                        "${state.result.throwable::class.java.simpleName}; " +
                        "fallbackHost=${nextUrl?.let(::normalizeJmImageHost)}; " +
                        "attempts=${updatedAttempts.size}",
                )
            }
            attemptedUrls = updatedAttempts
        },
    )
}

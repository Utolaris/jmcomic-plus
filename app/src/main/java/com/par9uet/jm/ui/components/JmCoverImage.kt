package com.par9uet.jm.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.DataSource
import coil.request.ImageRequest
import com.par9uet.jm.BuildConfig
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.coil.jmCoverCacheKey
import com.par9uet.jm.coil.nextCoverCandidateUrl
import com.par9uet.jm.image.ImageHostFailureKind
import com.par9uet.jm.image.classifyImageHostFailure
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
    isScrolling: Boolean = false,
    imageLoader: ImageLoader = getKoin().get(),
    resolver: CoverImageHostResolver = getKoin().get(),
) {
    val context = LocalContext.current
    val scrollingState = rememberUpdatedState(isScrolling)
    val networkGeneration by resolver.networkGeneration.collectAsState()
    val candidateUrls = remember(comicId, remoteHost, networkGeneration) {
        resolver.coverUrls(comicId, remoteHost)
    }
    var attemptedUrls by remember(comicId, remoteHost, networkGeneration) {
        mutableStateOf(emptySet<String>())
    }
    val candidateUrl = nextCoverCandidateUrl(candidateUrls, attemptedUrls)
    val cacheKey = remember(comicId) { jmCoverCacheKey(comicId) }
    val displayGate = remember(candidateUrl) { CoverImageDisplayGate() }
    var displayRevision by remember(candidateUrl) { mutableIntStateOf(0) }
    val request = remember(context, candidateUrl, cacheKey, displayRevision) {
        candidateUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                // Only changes request identity when a deferred success is released. The
                // logical cover cache identity remains the comic-only key above.
                .setParameter(COVER_DISPLAY_REVISION_PARAMETER, displayRevision)
                .build()
        }
    }

    // A completed request is already in Coil's memory/disk cache. Re-read it once after the
    // scroll settles so the bitmap becomes visible without doing work on every scroll frame.
    LaunchedEffect(isScrolling, displayGate) {
        if (!isScrolling && displayGate.consumeDeferredSuccess()) {
            displayRevision++
        }
    }

    AsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        transform = { state ->
            val loadedUrl = candidateUrl
            if (state is AsyncImagePainter.State.Success && loadedUrl != null) {
                if (state.result.dataSource == DataSource.NETWORK) {
                    // Coil 全量加载耗时（排队/下载/解码/渲染）不是 TTFB，
                    // 只标记主机健康，不写入 Reader 排序使用的延迟 EWMA。
                    resolver.recordHealthy(loadedUrl)
                }
                if (scrollingState.value) {
                    displayGate.deferSuccess()
                    // Do not submit the freshly decoded painter to the grid during a fling.
                    // The request has completed and its cache entry is retained by Coil.
                    return@AsyncImage AsyncImagePainter.State.Empty
                }
            }
            state
        },
        onState = { state ->
            when (state) {
                is AsyncImagePainter.State.Success -> {
                    val loadedUrl = candidateUrl ?: return@AsyncImage
                    if (BuildConfig.DEBUG) {
                        log(
                            "CoverImage",
                            "JM$comicId loaded from ${normalizeJmImageHost(loadedUrl)} " +
                                "(${state.result.dataSource})",
                        )
                    }
                }
                is AsyncImagePainter.State.Error -> {
                    val failedUrl = candidateUrl ?: return@AsyncImage
                    val failureKind = classifyImageHostFailure(state.result.throwable)
                    // 只有主机/网络级失败才全局冷却 CDN；资源级失败仅回退到下一个候选
                    if (failureKind == ImageHostFailureKind.HOST_FAILURE) {
                        resolver.recordHostFailure(failedUrl)
                    }
                    if (failureKind == ImageHostFailureKind.CANCELLED) {
                        return@AsyncImage
                    }
                    val updatedAttempts = attemptedUrls + failedUrl
                    val nextUrl = nextCoverCandidateUrl(candidateUrls, updatedAttempts)
                    if (BuildConfig.DEBUG) {
                        log(
                            "CoverImage",
                            "JM$comicId host ${normalizeJmImageHost(failedUrl)} failed: " +
                                "${state.result.throwable::class.java.simpleName}($failureKind); " +
                                "fallbackHost=${nextUrl?.let(::normalizeJmImageHost)}; " +
                                "attempts=${updatedAttempts.size}",
                        )
                    }
                    attemptedUrls = updatedAttempts
                }
                else -> Unit
            }
        },
    )
}

/** Tracks a completed bitmap without putting it into the draw path until scrolling settles. */
internal class CoverImageDisplayGate {
    private var deferredSuccess = false

    fun deferSuccess() {
        deferredSuccess = true
    }

    fun consumeDeferredSuccess(): Boolean {
        if (!deferredSuccess) return false
        deferredSuccess = false
        return true
    }
}

private const val COVER_DISPLAY_REVISION_PARAMETER = "jm-cover-display-revision"

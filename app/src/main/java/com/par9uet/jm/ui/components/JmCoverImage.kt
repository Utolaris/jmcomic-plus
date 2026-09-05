package com.par9uet.jm.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import coil.size.SizeResolver
import com.par9uet.jm.BuildConfig
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.coil.jmCoverCacheKey
import com.par9uet.jm.coil.nextCoverCandidateUrl
import com.par9uet.jm.image.ImageHostFailureKind
import com.par9uet.jm.image.classifyImageHostFailure
import com.par9uet.jm.image.normalizeJmImageHost
import com.par9uet.jm.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
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
    val displayGate = remember(comicId, candidateUrl) { CoverImageDisplayGate() }
    var displayedPainter by remember(comicId, candidateUrl) { mutableStateOf<Painter?>(null) }
    val sizeResolver = remember { JmCoverSizeResolver() }
    val request = remember(context, candidateUrl, cacheKey, sizeResolver) {
        candidateUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                // Keep the same request size that AsyncImage derives from the grid item.
                .size(sizeResolver)
                .build()
        }
    }

    // The request painter stays remembered so Coil can finish and cache the image while the
    // grid is moving. Only the selected painter below participates in the draw path.
    @Suppress("UNUSED_VARIABLE")
    val requestPainter = rememberAsyncImagePainter(
        model = request,
        imageLoader = imageLoader,
        contentScale = contentScale,
        onState = { state ->
            when (state) {
                is AsyncImagePainter.State.Success -> {
                    val loadedUrl = candidateUrl
                    if (loadedUrl != null) {
                        if (state.result.dataSource == DataSource.NETWORK) {
                            // Coil 全量加载耗时（排队/下载/解码/渲染）不是 TTFB，
                            // 只标记主机健康，不写入 Reader 排序使用的延迟 EWMA。
                            resolver.recordHealthy(loadedUrl)
                        }
                        if (shouldDeferCoverResult(scrollingState.value, state.result.dataSource)) {
                            displayGate.deferSuccess(state.painter)
                        } else {
                            // Memory cache hits are cheap to publish even during a fling.
                            displayGate.clearDeferredPainter()
                            displayedPainter = state.painter
                        }
                        if (BuildConfig.DEBUG) {
                            log(
                                "CoverImage",
                                "JM$comicId loaded from ${normalizeJmImageHost(loadedUrl)} " +
                                    "(${state.result.dataSource})",
                            )
                        }
                    }
                }
                is AsyncImagePainter.State.Error -> {
                    val failedUrl = candidateUrl
                    if (failedUrl != null) {
                        val failureKind = classifyImageHostFailure(state.result.throwable)
                        // 只有主机/网络级失败才全局冷却 CDN；资源级失败仅回退到下一个候选
                        if (failureKind == ImageHostFailureKind.HOST_FAILURE) {
                            resolver.recordHostFailure(failedUrl)
                        }
                        if (failureKind != ImageHostFailureKind.CANCELLED) {
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
                    }
                }
                else -> Unit
            }
        },
    )

    // Publish a completed non-memory result once after the fling. The painter is reused directly,
    // so settling the grid does not create a second request or a burst of cache lookups.
    LaunchedEffect(isScrolling, displayGate) {
        if (!isScrolling) {
            displayGate.takeDeferredPainter()?.let { painter ->
                displayedPainter = painter
            }
        }
    }

    Image(
        painter = displayedPainter ?: remember { ColorPainter(Color.Transparent) },
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.then(sizeResolver),
    )
}

private fun DataSource.isMemorySource(): Boolean =
    this == DataSource.MEMORY_CACHE || this == DataSource.MEMORY

internal fun shouldDeferCoverResult(isScrolling: Boolean, dataSource: DataSource): Boolean =
    isScrolling && !dataSource.isMemorySource()

/** The public AsyncImage API keeps this resolver internal, so mirror its grid-size behavior here. */
private class JmCoverSizeResolver : SizeResolver, LayoutModifier {
    private val currentConstraints = MutableStateFlow(Constraints(0, 0, 0, 0))

    override suspend fun size(): Size = currentConstraints
        .mapNotNull { it.toCoilSizeOrNull() }
        .first()

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        currentConstraints.value = constraints
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
}

private fun Constraints.toCoilSizeOrNull(): Size? {
    if (minWidth == 0 && maxWidth == 0 && minHeight == 0 && maxHeight == 0) {
        return null
    }
    val width = if (hasBoundedWidth) {
        Dimension.Pixels(maxWidth)
    } else {
        Dimension.Undefined
    }
    val height = if (hasBoundedHeight) {
        Dimension.Pixels(maxHeight)
    } else {
        Dimension.Undefined
    }
    return Size(width, height)
}

/** Tracks a completed painter without putting it into the draw path until scrolling settles. */
internal class CoverImageDisplayGate {
    private var deferredPainter: Painter? = null

    fun deferSuccess(painter: Painter) {
        deferredPainter = painter
    }

    fun takeDeferredPainter(): Painter? {
        val painter = deferredPainter
        deferredPainter = null
        return painter
    }

    fun clearDeferredPainter() {
        deferredPainter = null
    }
}

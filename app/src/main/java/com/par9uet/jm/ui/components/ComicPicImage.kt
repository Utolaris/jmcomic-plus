package com.par9uet.jm.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.reader.ReaderImageException
import com.par9uet.jm.reader.ReaderImagePipeline
import kotlinx.coroutines.CancellationException
import org.koin.compose.getKoin

private sealed interface ReaderImageUiState {
    data object Loading : ReaderImageUiState
    data class Success(val bitmap: ImageBitmap, val aspectRatio: Float) : ReaderImageUiState
    data class Failure(val reason: String) : ReaderImageUiState
}

@Composable
fun ComicPicImage(
    modifier: Modifier = Modifier,
    comicPicImageState: ComicPicImageState,
    contentScale: ContentScale = ContentScale.FillBounds,
    readerImagePipeline: ReaderImagePipeline = getKoin().get(),
) {
    var retryToken by remember(comicPicImageState.pageKey) { mutableIntStateOf(0) }
    val page = remember(comicPicImageState.pageKey) { comicPicImageState.toReaderPage() }
    val imageState by produceState<ReaderImageUiState>(
        initialValue = ReaderImageUiState.Loading,
        key1 = page.key,
        key2 = retryToken,
    ) {
        value = ReaderImageUiState.Loading
        try {
            val loaded = readerImagePipeline.loadVisiblePage(page)
            comicPicImageState.updateAspectRatio(loaded.aspectRatio)
            value = ReaderImageUiState.Success(loaded.bitmap.asImageBitmap(), loaded.aspectRatio)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            value = ReaderImageUiState.Failure(readerImageErrorMessage(error))
        }
    }

    Box(modifier = modifier) {
        when (val state = imageState) {
            ReaderImageUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            is ReaderImageUiState.Failure -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(state.reason)
                    TextButton(onClick = { retryToken++ }) { Text("重试") }
                }
            }

            is ReaderImageUiState.Success -> {
                Image(
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    bitmap = state.bitmap,
                    contentDescription = "第${comicPicImageState.index + 1}张图片",
                )
            }
        }
    }
}

private fun readerImageErrorMessage(error: Throwable): String = when (error) {
    is ReaderImageException -> error.message ?: "图片加载失败"
    is OutOfMemoryError -> "内存不足，无法解码图片"
    else -> "图片加载失败：${error.message ?: "未知错误"}"
}

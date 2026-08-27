package com.par9uet.jm.ui.viewModel

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.cache.getComicChapterDownloadDir
import com.par9uet.jm.cache.getDownloadDir
import com.par9uet.jm.cache.listComicImageFiles
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.CollectComicResponse
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.reader.ReaderImagePipeline
import com.par9uet.jm.reader.ReaderPageKey
import com.par9uet.jm.reader.readerPrefetchPlan
import com.par9uet.jm.reader.runReaderPrefetchSchedule
import com.par9uet.jm.store.ReaderPreferences
import com.par9uet.jm.store.ReadHistoryManager
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.ui.models.CommonUIState
import com.par9uet.jm.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ComicReadViewModel(
    private val comicRepository: ComicRepository,
    private val readerImagePipeline: ReaderImagePipeline,
    private val readerPreferences: ReaderPreferences,
    private val downloadComicDao: DownloadComicDao,
    private val toastManager: ToastManager,
    private val readHistoryManager: ReadHistoryManager,
) : ViewModel() {
    var isShowToolBar = mutableStateOf(false)
    var currentIndexState = mutableIntStateOf(0)
    var loadedComicId = mutableIntStateOf(-1)
    var readHistoryComicId = mutableIntStateOf(-1)
    private val _comicPicState = MutableStateFlow(
        CommonUIState<List<ComicPicImageState>>(
            isLoading = true
        )
    )
    val comicPicState = _comicPicState.asStateFlow()
    private val _comicDetailState = MutableStateFlow(CommonUIState<Comic>())
    val comicDetailState = _comicDetailState.asStateFlow()
    private val _localChapterList = MutableStateFlow<List<ComicChapter>>(emptyList())
    val localChapterList = _localChapterList.asStateFlow()

    val size: Int get() = _comicPicState.value.data?.size ?: 0

    private var prefetchScheduleJob: Job? = null
    private var prefetchScheduleKeys = emptyList<ReaderPageKey>()
    private var prefetchScheduleSourceOnly = false
    private var prefetchScheduleParallelism = 1
    private var foregroundLoadJob: Job? = null
    private var foregroundIndex: Int? = null
    private var lastScheduledIndex: Int? = null
    private var lastDirection = 1
    private var directionStreak = 0
    private var lastDirectionAtMillis = 0L
    private var pageVelocity = 0f

    fun getComicDetail(comicId: Int) {
        viewModelScope.launch {
            _comicDetailState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = comicRepository.getComicDetail(comicId)) {
                is NetWorkResult.Error -> {
                    _comicDetailState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<ComicDetailResponse> -> {
                    val comic = data.data.toComic()
                    readHistoryComicId.intValue = readHistoryManager.markRead(comic, comicId)
                    _comicDetailState.update {
                        it.copy(
                            data = comic
                        )
                    }
                }
            }
            _comicDetailState.update {
                it.copy(isLoading = false)
            }
        }
    }

    fun clearComicDetail() {
        _comicDetailState.update { CommonUIState() }
    }

    fun collect(comicId: Int) {
        updateCollectState(comicId, true)
    }

    fun unCollect(comicId: Int) {
        updateCollectState(comicId, false)
    }

    private fun updateCollectState(comicId: Int, targetCollect: Boolean) {
        viewModelScope.launch {
            when (val data: NetWorkResult<CollectComicResponse> = if (targetCollect) {
                comicRepository.collectComic(comicId)
            } else {
                comicRepository.unCollectComic(comicId)
            }) {
                is NetWorkResult.Error -> {
                    toastManager.showAsync(data.message)
                }

                is NetWorkResult.Success<CollectComicResponse> -> {
                    toastManager.showAsync(if (targetCollect) "收藏成功" else "取消收藏成功")
                    _comicDetailState.update {
                        it.copy(
                            data = it.data?.copy(isCollect = targetCollect)
                        )
                    }
                }
            }
        }
    }

    fun getComicPicList(comicId: Int, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            _localChapterList.value = emptyList()
            _comicPicState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            resetReaderRequests()
            when (val data = comicRepository.getComicPicList(comicId)) {
                is NetWorkResult.Error -> {
                    _comicPicState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<ComicPicListResponse> -> {
                    val pages = data.data.list.mapIndexed { index, item ->
                        ComicPicImageState(
                            index,
                            comicId,
                            item,
                            data.data.__scrambleId,
                            data.data.__speed,
                            imageFetcher = {
                                comicRepository.downloadImageBytes(comicId, index)
                            }
                        )
                    }
                    _comicPicState.update {
                        it.copy(
                            data = pages,
                        )
                    }
                    pages.firstOrNull()?.let { page ->
                        readerImagePipeline.warmImageConnections(page.toReaderPage())
                    }
                    onSuccess?.invoke()
                }
            }
            _comicPicState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }

    fun getLocalComicPicList(comicId: Int, context: Context, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            _comicPicState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            resetReaderRequests()
            val downloadComic = downloadComicDao.getById(comicId)
            val groupId = downloadComic?.groupId?.takeIf { it != 0 } ?: comicId
            readHistoryComicId.intValue = readHistoryManager.markRead(groupId, comicId)
            loadLocalChapterList(comicId, downloadComic)
            val imageDir = ensureLocalImageDir(context, comicId, downloadComic)
            val files = imageDir
                ?.let(::listComicImageFiles)
                .orEmpty()

            if (files.isEmpty()) {
                _comicPicState.update {
                    it.copy(
                        isLoading = false,
                        isError = true,
                        errorMsg = "未找到本地缓存图片"
                    )
                }
                return@launch
            }

            _comicPicState.update {
                it.copy(
                    data = files.mapIndexed { index, file ->
                        ComicPicImageState(
                            index = index,
                            comicId = comicId,
                            originSrc = file.absolutePath,
                            __scrambleId = Int.MAX_VALUE,
                            __speed = "1",
                        )
                    },
                    isLoading = false
                )
            }
            onSuccess?.invoke()
        }
    }

    private suspend fun loadLocalChapterList(comicId: Int, currentComic: DownloadComic?) {
        val groupId = currentComic?.groupId?.takeIf { it != 0 } ?: comicId
        val chapters = downloadComicDao.getCompleteByGroupId(groupId)
        _localChapterList.value = chapters.mapIndexed { index, item ->
            ComicChapter(
                id = item.id,
                name = item.chapterName.ifBlank {
                    if (chapters.size > 1) "第 ${index + 1} 章" else item.name
                }
            )
        }
    }

    private fun ensureLocalImageDir(context: Context, comicId: Int, downloadComic: DownloadComic?): File? {
        val zipPath = downloadComic?.zipPath.orEmpty()
        val directDir = zipPath.takeIf { it.isNotBlank() }?.let(::File)
        if (directDir?.isDirectory == true && listComicImageFiles(directDir).isNotEmpty()) {
            return directDir
        }

        if (downloadComic != null) {
            val namedDir = getComicChapterDownloadDir(context, downloadComic)
            if (namedDir.exists() && listComicImageFiles(namedDir).isNotEmpty()) {
                return namedDir
            }
        }

        val dir = File(getDownloadDir(context), "$comicId")
        if (dir.exists() && dir.listFiles()?.isNotEmpty() == true) {
            return dir
        }
        if (zipPath.isBlank()) {
            return dir.takeIf { it.exists() }
        }
        val zipFile = File(zipPath)
        if (!zipFile.exists()) {
            return dir.takeIf { it.exists() }
        }
        dir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zipIn ->
            while (true) {
                val entry = zipIn.nextEntry ?: break
                if (!entry.isDirectory) {
                    val output = File(dir, File(entry.name).name)
                    FileOutputStream(output).use { out ->
                        zipIn.copyTo(out)
                    }
                }
                zipIn.closeEntry()
            }
        }
        return dir
    }

    fun decodeIndex(index: Int, @Suppress("UNUSED_PARAMETER") context: Context) {
        if (size <= 0 || index !in 0 until size) return
        val previous = lastScheduledIndex
        val direction = updateDirection(index)
        val jumpDistance = previous?.let { abs(index - it) } ?: 0
        loadVisible(index)
        schedulePrefetch(index, direction, jumpDistance, index, index)
    }

    fun decodeVisibleRange(
        firstIndex: Int,
        lastIndex: Int,
        @Suppress("UNUSED_PARAMETER") context: Context,
    ) {
        if (size <= 0) return
        val visibleStart = min(firstIndex, lastIndex).coerceIn(0, size - 1)
        val visibleEnd = max(firstIndex, lastIndex).coerceIn(0, size - 1)
        val previous = lastScheduledIndex
        val anchor = when {
            previous == null -> visibleEnd
            visibleEnd > previous -> visibleEnd
            visibleStart < previous -> visibleStart
            lastDirection >= 0 -> visibleEnd
            else -> visibleStart
        }
        val direction = updateDirection(anchor)
        val jumpDistance = previous?.let { abs(anchor - it) } ?: 0
        loadVisible(anchor)
        schedulePrefetch(anchor, direction, jumpDistance, visibleStart, visibleEnd)
    }

    fun prev(context: Context) {
        if (size <= 0) return
        hideToolBar()
        val index = max(0, currentIndexState.intValue - 1)
        currentIndexState.intValue = index
        decodeIndex(index, context)
    }

    fun next(context: Context) {
        if (size <= 0) return
        hideToolBar()
        val index = min(size - 1, currentIndexState.intValue + 1)
        currentIndexState.intValue = index
        decodeIndex(index, context)
    }

    private fun updateDirection(index: Int): Int {
        val previous = lastScheduledIndex
        val now = System.currentTimeMillis()
        if (previous == null) {
            lastDirection = 1
            directionStreak = 0
        } else if (index != previous) {
            val direction = if (index > previous) 1 else -1
            val elapsed = (now - lastDirectionAtMillis).coerceAtLeast(1L)
            val instantVelocity = abs(index - previous).toFloat() * 1_000f / elapsed
            pageVelocity = (pageVelocity * 0.65f + instantVelocity * 0.35f).coerceIn(0f, 8f)
            if (direction == lastDirection) {
                directionStreak++
            } else {
                lastDirection = direction
                directionStreak = 1
            }
        }
        lastDirectionAtMillis = now
        lastScheduledIndex = index
        return lastDirection
    }

    private fun loadVisible(index: Int) {
        val page = comicPicState.value.data?.getOrNull(index) ?: return
        if (foregroundLoadJob?.isActive == true && foregroundIndex == index) return
        readerImagePipeline.warmImageConnections(page.toReaderPage())
        foregroundLoadJob?.cancel()
        foregroundIndex = index
        foregroundLoadJob = viewModelScope.launch {
            try {
                readerImagePipeline.loadVisiblePage(page.toReaderPage())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("load visible index $index failed: ${e.message}")
            }
        }
    }

    private fun schedulePrefetch(
        index: Int,
        direction: Int,
        jumpDistance: Int,
        visibleStart: Int,
        visibleEnd: Int,
    ) {
        val pages = comicPicState.value.data ?: return
        val prefetchCount = readerPreferences.prefetchCount.value
        val policy = readerImagePipeline.adaptivePrefetchPolicy(
            configuredDistance = prefetchCount,
            jumpDistance = jumpDistance,
            directionStreak = directionStreak,
            pageVelocity = pageVelocity,
            turboMode = prefetchCount >= 5,
        )
        val plannedIndices = readerPrefetchPlan(
            currentPageIndex = index,
            pageCount = pages.size,
            distance = policy.distance,
            direction = direction,
            includeOpposite = readerPreferences.readMode.value != "scroll",
            visibleStart = visibleStart,
            visibleEnd = visibleEnd,
        )
        val plannedPages = plannedIndices.mapNotNull { plannedIndex ->
            pages.getOrNull(plannedIndex)?.let { plannedIndex to it }
        }
        val desiredKeys = plannedPages.map { it.second.pageKey }
        if (
            prefetchScheduleJob?.isActive == true &&
            desiredKeys == prefetchScheduleKeys &&
            policy.sourceOnly == prefetchScheduleSourceOnly &&
            policy.parallelism == prefetchScheduleParallelism
        ) {
            return
        }

        val staleKeys = prefetchScheduleKeys.toSet() - desiredKeys.toSet()
        val previousJob = prefetchScheduleJob
        previousJob?.cancel()
        previousJob?.invokeOnCompletion {
            staleKeys.forEach(readerImagePipeline::cancelPrefetch)
        }
        staleKeys.forEach(readerImagePipeline::cancelPrefetch)

        prefetchScheduleKeys = desiredKeys
        prefetchScheduleSourceOnly = policy.sourceOnly
        prefetchScheduleParallelism = policy.parallelism
        val job = viewModelScope.launch {
            runReaderPrefetchSchedule(plannedPages, policy.parallelism) { (plannedIndex, page) ->
                try {
                    if (policy.sourceOnly) {
                        readerImagePipeline.prefetchPageSource(page.toReaderPage())
                    } else {
                        readerImagePipeline.prefetchPage(page.toReaderPage())
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Speculative failures remain silent; a later visible request retries because
                    // the failed in-flight entry is removed by the pipeline.
                    log("prefetch index $plannedIndex failed: ${e.message}")
                }
            }
        }
        prefetchScheduleJob = job
        job.invokeOnCompletion {
            if (prefetchScheduleJob === job) prefetchScheduleJob = null
        }
    }

    private fun resetReaderRequests() {
        foregroundLoadJob?.cancel()
        foregroundLoadJob = null
        foregroundIndex = null
        prefetchScheduleJob?.cancel()
        prefetchScheduleJob = null
        prefetchScheduleKeys.forEach(readerImagePipeline::cancelPrefetch)
        prefetchScheduleKeys = emptyList()
        prefetchScheduleSourceOnly = false
        prefetchScheduleParallelism = 1
        readerImagePipeline.cancelAllPrefetch()
        lastScheduledIndex = null
        lastDirection = 1
        directionStreak = 0
        lastDirectionAtMillis = 0L
        pageVelocity = 0f
    }

    fun triggerToolBar() {
        isShowToolBar.value = !isShowToolBar.value
    }

    fun hideToolBar() {
        isShowToolBar.value = false
    }

    fun showToolBar() {
        isShowToolBar.value = true
    }

    override fun onCleared() {
        resetReaderRequests()
        super.onCleared()
    }
}

package com.par9uet.jm.download.molecule

import androidx.core.graphics.drawable.toBitmap
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.SuccessResult
import com.par9uet.jm.BuildConfig
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.coil.jmCoverCacheKey
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.database.model.UpdateComicStatus
import com.par9uet.jm.database.model.UpdateComicZipPath
import com.par9uet.jm.download.atom.DownloadContentStorage
import com.par9uet.jm.download.atom.DownloadCoverImages
import com.par9uet.jm.download.atom.DownloadPageDecoder
import com.par9uet.jm.image.ImageHostFailureKind
import com.par9uet.jm.image.classifyImageHostFailure
import com.par9uet.jm.image.cancellationExceptionOrNull
import com.par9uet.jm.image.isCancellation
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.utils.DownloadSpeedTracker
import com.par9uet.jm.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val DOWNLOAD_PAGE_TIMEOUT_MS = 180_000L

interface DownloadContentOperations {
    suspend fun downloadCover(downloadTask: DownloadComic, coverOwnerId: Int, remoteHost: String): String
    suspend fun downloadPages(downloadTask: DownloadComic, onProgress: suspend (Float) -> Unit)
    suspend fun complete(downloadTask: DownloadComic)
}

class DeviceDownloadContentOperations(
    private val downloadComicDao: DownloadComicDao,
    private val comicRepository: ComicRepository,
    private val decoder: DownloadPageDecoder,
    private val coverImageHostResolver: CoverImageHostResolver,
    private val coverImages: DownloadCoverImages,
    private val files: DownloadContentStorage,
) : DownloadContentOperations {
    override suspend fun downloadCover(downloadTask: DownloadComic, coverOwnerId: Int, remoteHost: String): String {
        return withContext(Dispatchers.IO) {
            val cacheKey = jmCoverCacheKey(coverOwnerId)
            val candidates = coverImageHostResolver.coverUrls(
                comicId = coverOwnerId,
                remoteHost = remoteHost,
            )
            candidates.forEachIndexed { index, coverUrl ->
                when (val result = coverImages.load(coverUrl, cacheKey)) {
                    is ErrorResult -> {
                        val failureKind = classifyImageHostFailure(result.throwable)
                        // 主机/网络级失败才全局冷却 CDN；资源级失败只尝试下一个候选
                        if (failureKind == ImageHostFailureKind.HOST_FAILURE) {
                            coverImageHostResolver.recordHostFailure(coverUrl)
                        }
                        if (result.throwable.isCancellation()) {
                            throw result.throwable.cancellationExceptionOrNull() ?: result.throwable
                        }
                        if (BuildConfig.DEBUG) {
                            log(
                                "CoverImage",
                                "download JM$coverOwnerId candidate $index failed: " +
                                    "${result.throwable::class.java.simpleName}($failureKind)",
                            )
                        }
                    }
                    is SuccessResult -> {
                        if (result.dataSource == DataSource.NETWORK) {
                            // 全量下载+解码耗时不是 TTFB，只标记主机健康
                            coverImageHostResolver.recordHealthy(coverUrl)
                        }
                        val bitmap = result.drawable.toBitmap()
                        return@withContext files.writeCover(downloadTask, bitmap)
                    }
                }
            }
            ""
        }
    }


    override suspend fun downloadPages(downloadTask: DownloadComic, onProgress: suspend (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val comicId = downloadTask.id
            when (val data = comicRepository.getComicPicList(comicId)) {
                is NetWorkResult.Error -> throw IllegalStateException(data.message)
                is NetWorkResult.Success -> {
                    if (data.data.urls.isEmpty()) throw IllegalStateException("图片列表为空")
                    val chapterPath = files.chapterPath(downloadTask)
                    data.data.urls.forEachIndexed { index, url ->
                        val nextProgress = (index + 1).toFloat() / data.data.urls.size
                        if (!files.pageExists(chapterPath, index)) {
                            val imageState = ComicPicImageState(
                                index = index,
                                comicId = comicId,
                                originSrc = url,
                                __scrambleId = data.data.scrambleId,
                                __speed = data.data.speed,
                            )
                            val bitmap = downloadPageWithinTimeout(DOWNLOAD_PAGE_TIMEOUT_MS) {
                                decoder.decode(imageState)
                            }
                            val bytes = files.writePage(chapterPath, index, bitmap)
                            DownloadSpeedTracker.addBytes(
                                downloadTask.groupId.takeIf { it != 0 } ?: comicId, bytes,
                            )
                        }
                        onProgress(nextProgress)
                    }
                }
            }
        }
    }

    override suspend fun complete(downloadTask: DownloadComic) {
        val comicId = downloadTask.id
        downloadComicDao.updateZipPath(UpdateComicZipPath(comicId, files.chapterPath(downloadTask)))
        downloadComicDao.updateStatus(UpdateComicStatus(comicId, DownloadStatus.COMPLETE))
        val current = downloadComicDao.getById(comicId) ?: return
        val groupId = current.groupId.takeIf { it != 0 } ?: current.id
        val chapters = downloadComicDao.getByGroupId(groupId)
        withContext(Dispatchers.IO) {
            files.writeConfig(current, chapters)
        }
    }
}

// Only this page's deadline becomes a retryable error. Parent cancellation still propagates.
internal suspend fun <T : Any> downloadPageWithinTimeout(timeoutMillis: Long, decode: suspend () -> T): T =
    withTimeoutOrNull(timeoutMillis) { decode() }
        ?: throw IllegalStateException("页面下载或解码超时")

package com.par9uet.jm.repository.impl

import com.par9uet.jm.data.comic.ComicEmbeddedDataSource
import com.par9uet.jm.data.comic.ComicNetworkDataSource
import com.par9uet.jm.data.models.COMIC_API_SOURCE_BUILTIN
import com.par9uet.jm.data.models.COMIC_API_SOURCE_MIXED
import com.par9uet.jm.data.models.COMIC_API_SOURCE_NETWORK
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.CollectComicResponse
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.ComicListResponse
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.CommentComicResponse
import com.par9uet.jm.retrofit.model.CommentListResponse
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.retrofit.model.LikeComicResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.WeekRecommendComicResponse
import com.par9uet.jm.retrofit.model.WeekResponse
import com.par9uet.jm.store.AppLocalSettings

/**
 * Routes comic operations while keeping API-specific implementations in dedicated data sources.
 * Mixed mode intentionally uses the embedded API for metadata and actions, and the network API
 * for image-list HTML, matching the behavior of the previous repository implementation.
 */
class ComicRepositoryImpl(
    private val networkDataSource: ComicNetworkDataSource,
    private val embeddedDataSource: ComicEmbeddedDataSource,
    private val localSettings: AppLocalSettings,
) : ComicRepository {
    override suspend fun getComicDetail(id: Int): NetWorkResult<ComicDetailResponse> =
        if (useEmbeddedApi()) embeddedDataSource.getComicDetail(id)
        else networkDataSource.getComicDetail(id)

    override suspend fun likeComic(id: Int): NetWorkResult<LikeComicResponse> =
        if (useEmbeddedApi()) embeddedDataSource.likeComic(id)
        else networkDataSource.likeComic(id)

    override suspend fun collectComic(id: Int): NetWorkResult<CollectComicResponse> =
        if (useEmbeddedApi()) embeddedDataSource.collectComic(id)
        else networkDataSource.collectComic(id)

    override suspend fun unCollectComic(id: Int): NetWorkResult<CollectComicResponse> =
        if (useEmbeddedApi()) embeddedDataSource.unCollectComic(id)
        else networkDataSource.unCollectComic(id)

    override suspend fun getEmbeddedHomeCategory(
        categoryId: String,
    ): NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>> {
        if (!useEmbeddedApi()) return NetWorkResult.Error("内置 API 分类仅在内置数据源可用")
        return embeddedDataSource.getHomeCategory(categoryId)
    }

    override suspend fun getNetworkHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>> =
        networkDataSource.getHomePage()

    override suspend fun getComicPicList(
        id: Int,
        shunt: String,
    ): NetWorkResult<ComicPicListResponse> =
        if (useEmbeddedApi() && !useNetworkApiForImages()) {
            embeddedDataSource.getComicPicList(id)
        } else {
            networkDataSource.getComicPicList(id, shunt)
        }

    override suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray? =
        embeddedDataSource.downloadImageBytes(comicId, imageIndex)

    override suspend fun getComicList(
        page: Int,
        order: ComicSearchOrderFilter,
        searchContent: String,
    ): NetWorkResult<ComicListResponse> =
        if (useEmbeddedApi()) embeddedDataSource.getComicList(page, order, searchContent)
        else networkDataSource.getComicList(page, order, searchContent)

    override suspend fun getWeekData(): NetWorkResult<WeekResponse> =
        if (useEmbeddedApi()) embeddedDataSource.getWeekData()
        else networkDataSource.getWeekData()

    override suspend fun getWeekRecommendComicList(
        page: Int,
        categoryId: String,
        typeId: String,
    ): NetWorkResult<WeekRecommendComicResponse> =
        if (useEmbeddedApi()) {
            embeddedDataSource.getWeekRecommendComicList(page, categoryId, typeId)
        } else {
            networkDataSource.getWeekRecommendComicList(page, categoryId, typeId)
        }

    override suspend fun getCommentList(
        page: Int,
        comicId: Int,
    ): NetWorkResult<CommentListResponse> =
        if (useEmbeddedApi()) embeddedDataSource.getCommentList(page, comicId)
        else networkDataSource.getCommentList(page, comicId)

    override suspend fun comment(
        content: String,
        comicId: Int,
        commentId: Int?,
    ): NetWorkResult<CommentComicResponse> =
        if (useEmbeddedApi()) embeddedDataSource.comment(content, comicId, commentId)
        else networkDataSource.comment(content, comicId, commentId)

    override suspend fun likeComment(commentId: Int): NetWorkResult<CommentComicResponse> =
        networkDataSource.likeComment(commentId)

    override suspend fun createFavoriteFolder(name: String): NetWorkResult<Unit> {
        if (!useEmbeddedApi()) return NetWorkResult.Error("网络API暂不支持收藏夹管理")
        return embeddedDataSource.createFavoriteFolder(name)
    }

    override suspend fun deleteFavoriteFolder(folderId: String): NetWorkResult<Unit> {
        if (!useEmbeddedApi()) return NetWorkResult.Error("网络API暂不支持收藏夹管理")
        return embeddedDataSource.deleteFavoriteFolder(folderId)
    }

    override suspend fun renameFavoriteFolder(
        folderId: String,
        newName: String,
    ): NetWorkResult<Unit> {
        if (!useEmbeddedApi()) return NetWorkResult.Error("网络API暂不支持收藏夹管理")
        return embeddedDataSource.renameFavoriteFolder(folderId, newName)
    }

    override suspend fun moveComicToFolder(
        comicId: Int,
        folderId: String,
    ): NetWorkResult<Unit> {
        if (!useEmbeddedApi()) return NetWorkResult.Error("网络API暂不支持收藏夹管理")
        return embeddedDataSource.moveComicToFolder(comicId, folderId)
    }

    override suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int> =
        embeddedDataSource.getComicIdsByTag(tagName, maxPages)

    private fun useEmbeddedApi(): Boolean {
        val source = localSettings.localSettingState.value.comicApiSource
        return source == COMIC_API_SOURCE_BUILTIN || source == COMIC_API_SOURCE_MIXED
    }

    private fun useNetworkApiForImages(): Boolean {
        val source = localSettings.localSettingState.value.comicApiSource
        return source == COMIC_API_SOURCE_NETWORK || source == COMIC_API_SOURCE_MIXED
    }
}

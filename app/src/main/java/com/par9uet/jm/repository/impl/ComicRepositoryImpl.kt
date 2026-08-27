package com.par9uet.jm.repository.impl

import com.par9uet.jm.data.comic.ComicEmbeddedDataSource
import com.par9uet.jm.data.comic.NetworkHomeDataSource
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.CollectComicResponse
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.ComicListResponse
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.CommentComicResponse
import com.par9uet.jm.retrofit.model.CommentListResponse
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.WeekRecommendComicResponse
import com.par9uet.jm.retrofit.model.WeekResponse
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient

/**
 * Embedded is the canonical JM business backend. The network data source is an explicit,
 * optional Home recommendation exception and never participates in normal business routing.
 */
class ComicRepositoryImpl(
    private val networkHomeDataSource: NetworkHomeDataSource,
    private val embeddedDataSource: ComicEmbeddedDataSource,
    private val authenticatedEmbeddedClient: AuthenticatedEmbeddedClient,
) : ComicRepository {
    override suspend fun getComicDetail(id: Int): NetWorkResult<ComicDetailResponse> =
        embeddedDataSource.getComicDetail(id)

    override suspend fun <R> withEmbeddedClient(block: (JmApiClient) -> R): R? =
        authenticatedEmbeddedClient.withClient(block)

    override suspend fun collectComic(id: Int): NetWorkResult<CollectComicResponse> =
        embeddedDataSource.collectComic(id)

    override suspend fun unCollectComic(id: Int): NetWorkResult<CollectComicResponse> =
        embeddedDataSource.unCollectComic(id)

    override suspend fun getEmbeddedHomeCategory(
        categoryId: String,
    ): NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>> =
        embeddedDataSource.getHomeCategory(categoryId)

    override suspend fun getNetworkHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>> =
        networkHomeDataSource.getHomePage()

    override suspend fun getComicPicList(id: Int): NetWorkResult<ComicPicListResponse> =
        embeddedDataSource.getComicPicList(id)

    override suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray? =
        embeddedDataSource.downloadImageBytes(comicId, imageIndex)

    override suspend fun getComicList(
        page: Int,
        order: ComicSearchOrderFilter,
        searchContent: String,
    ): NetWorkResult<ComicListResponse> =
        embeddedDataSource.getComicList(page, order, searchContent)

    override suspend fun getWeekData(): NetWorkResult<WeekResponse> =
        embeddedDataSource.getWeekData()

    override suspend fun getWeekRecommendComicList(
        page: Int,
        categoryId: String,
        typeId: String,
    ): NetWorkResult<WeekRecommendComicResponse> =
        embeddedDataSource.getWeekRecommendComicList(page, categoryId, typeId)

    override suspend fun getCommentList(
        page: Int,
        comicId: Int,
    ): NetWorkResult<CommentListResponse> =
        embeddedDataSource.getCommentList(page, comicId)

    override suspend fun comment(
        content: String,
        comicId: Int,
        commentId: Int?,
    ): NetWorkResult<CommentComicResponse> =
        embeddedDataSource.comment(content, comicId, commentId)

    override suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int> =
        embeddedDataSource.getComicIdsByTag(tagName, maxPages)

}

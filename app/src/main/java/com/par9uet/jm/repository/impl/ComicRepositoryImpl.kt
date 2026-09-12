package com.par9uet.jm.repository.impl

import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.map
import com.par9uet.jm.data.comic.ComicEmbeddedDataSource
import com.par9uet.jm.data.comic.NetworkHomeDataSource
import com.par9uet.jm.data.comic.mapper.toActionResult
import com.par9uet.jm.data.comic.mapper.toComic
import com.par9uet.jm.data.comic.mapper.toComicPage
import com.par9uet.jm.data.comic.mapper.toComicPageList
import com.par9uet.jm.data.comic.mapper.toComicSearchPage
import com.par9uet.jm.data.comic.mapper.toCommentPage
import com.par9uet.jm.data.comic.mapper.toHomeComicSwiperItem
import com.par9uet.jm.data.comic.mapper.toWeekData
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.ComicPageList
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.data.models.ComicSearchPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.data.models.HomeComicSwiperItem
import com.par9uet.jm.data.models.WeekData
import com.par9uet.jm.repository.ComicRepository

/**
 * Embedded is the canonical JM business backend. The network data source is an explicit,
 * optional Home recommendation exception and never participates in normal business routing.
 *
 * wire DTO 在这一层就地映射成领域类型后返回，`retrofit/model` 不再越过本类。
 */
class ComicRepositoryImpl(
    private val networkHomeDataSource: NetworkHomeDataSource,
    private val embeddedDataSource: ComicEmbeddedDataSource,
) : ComicRepository {
    override suspend fun getComicDetail(id: Int): NetWorkResult<Comic> =
        embeddedDataSource.getComicDetail(id).map { it.toComic() }

    override suspend fun collectComic(id: Int): NetWorkResult<Unit> =
        embeddedDataSource.collectComic(id).map { }

    override suspend fun unCollectComic(id: Int): NetWorkResult<Unit> =
        embeddedDataSource.unCollectComic(id).map { }

    override suspend fun getEmbeddedHomeCategory(
        categoryId: String,
    ): NetWorkResult<List<Comic>> =
        embeddedDataSource.getHomeCategory(categoryId).map { items -> items.map { it.toComic() } }

    override suspend fun getNetworkHomePage(): NetWorkResult<List<HomeComicSwiperItem>> =
        networkHomeDataSource.getHomePage().map { items -> items.map { it.toHomeComicSwiperItem() } }

    override suspend fun getComicPicList(id: Int): NetWorkResult<ComicPageList> =
        embeddedDataSource.getComicPicList(id).map { it.toComicPageList() }

    override suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray? =
        embeddedDataSource.downloadImageBytes(comicId, imageIndex)

    override suspend fun getComicList(
        page: Int,
        order: ComicSearchOrderFilter,
        searchContent: String,
    ): NetWorkResult<ComicSearchPage> =
        embeddedDataSource.getComicList(page, order, searchContent).map { it.toComicSearchPage() }

    override suspend fun getWeekData(): NetWorkResult<WeekData> =
        embeddedDataSource.getWeekData().map { it.toWeekData() }

    override suspend fun getWeekRecommendComicList(
        page: Int,
        categoryId: String,
        typeId: String,
    ): NetWorkResult<ComicPage> =
        embeddedDataSource.getWeekRecommendComicList(page, categoryId, typeId)
            .map { it.toComicPage() }

    override suspend fun getCommentList(
        page: Int,
        comicId: Int,
    ): NetWorkResult<CommentPage> =
        embeddedDataSource.getCommentList(page, comicId).map { it.toCommentPage() }

    override suspend fun comment(
        content: String,
        comicId: Int,
        commentId: Int?,
    ): NetWorkResult<ActionResult> =
        embeddedDataSource.comment(content, comicId, commentId).map { it.toActionResult() }

    override suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int> =
        embeddedDataSource.getComicIdsByTag(tagName, maxPages)
}

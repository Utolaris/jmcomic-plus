package com.par9uet.jm.data.comic

import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.repository.BaseRepository
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
import com.par9uet.jm.retrofit.parseHtml
import com.par9uet.jm.retrofit.parseRange
import com.par9uet.jm.retrofit.parseSpeed
import com.par9uet.jm.retrofit.service.ComicService

interface ComicNetworkDataSource {
    suspend fun getComicDetail(id: Int): NetWorkResult<ComicDetailResponse>
    suspend fun likeComic(id: Int): NetWorkResult<LikeComicResponse>
    suspend fun collectComic(id: Int): NetWorkResult<CollectComicResponse>
    suspend fun unCollectComic(id: Int): NetWorkResult<CollectComicResponse>
    suspend fun getHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>>
    suspend fun getComicPicList(id: Int, shunt: String): NetWorkResult<ComicPicListResponse>
    suspend fun getComicList(
        page: Int,
        order: ComicSearchOrderFilter,
        searchContent: String,
    ): NetWorkResult<ComicListResponse>

    suspend fun getWeekData(): NetWorkResult<WeekResponse>
    suspend fun getWeekRecommendComicList(
        page: Int,
        categoryId: String,
        typeId: String,
    ): NetWorkResult<WeekRecommendComicResponse>

    suspend fun getCommentList(page: Int, comicId: Int): NetWorkResult<CommentListResponse>
    suspend fun comment(
        content: String,
        comicId: Int,
        commentId: Int?,
    ): NetWorkResult<CommentComicResponse>

    suspend fun likeComment(commentId: Int): NetWorkResult<CommentComicResponse>
}

class NetworkComicDataSource(
    private val service: ComicService,
) : BaseRepository(), ComicNetworkDataSource {
    override suspend fun getComicDetail(id: Int): NetWorkResult<ComicDetailResponse> =
        safeApiCall { service.getComicDetail(id) }

    override suspend fun likeComic(id: Int): NetWorkResult<LikeComicResponse> =
        safeApiCall { service.likeComic(id) }

    override suspend fun collectComic(id: Int): NetWorkResult<CollectComicResponse> =
        safeApiCall { service.collectComic(id) }

    override suspend fun unCollectComic(id: Int): NetWorkResult<CollectComicResponse> =
        safeApiCall { service.collectComic(id) }

    override suspend fun getHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>> =
        safeApiCall { service.getHomeSwiperComicList() }

    override suspend fun getComicPicList(id: Int, shunt: String): NetWorkResult<ComicPicListResponse> {
        return when (val result = safeStringCall { service.getComicPicList(id, shunt) }) {
            is NetWorkResult.Success -> {
                val html = result.data
                val range = parseRange(html)
                NetWorkResult.Success(
                    ComicPicListResponse(
                        list = parseHtml(html),
                        __aId = range.first,
                        __scrambleId = range.second,
                        __speed = parseSpeed(html),
                    )
                )
            }

            else -> NetWorkResult.Error("从 HTML 解析图片列表失败")
        }
    }

    override suspend fun getComicList(
        page: Int,
        order: ComicSearchOrderFilter,
        searchContent: String,
    ): NetWorkResult<ComicListResponse> =
        safeApiCall { service.getComicList(page, order.value, searchContent) }

    override suspend fun getWeekData(): NetWorkResult<WeekResponse> =
        safeApiCall { service.getWeekData() }

    override suspend fun getWeekRecommendComicList(
        page: Int,
        categoryId: String,
        typeId: String,
    ): NetWorkResult<WeekRecommendComicResponse> =
        safeApiCall { service.getWeekRecommendComicList(page, categoryId, typeId) }

    override suspend fun getCommentList(
        page: Int,
        comicId: Int,
    ): NetWorkResult<CommentListResponse> =
        safeApiCall { service.getCommentList(page, comicId, "manhua") }

    override suspend fun comment(
        content: String,
        comicId: Int,
        commentId: Int?,
    ): NetWorkResult<CommentComicResponse> =
        safeApiCall { service.comment(content, comicId, commentId ?: 0) }

    override suspend fun likeComment(commentId: Int): NetWorkResult<CommentComicResponse> =
        safeApiCall { service.likeComment(commentId) }
}

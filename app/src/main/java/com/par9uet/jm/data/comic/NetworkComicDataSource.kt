package com.par9uet.jm.data.comic

import com.par9uet.jm.repository.BaseRepository
import com.par9uet.jm.retrofit.model.CommentComicResponse
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.service.ComicService

/** Explicit network exception for the optional Home recommendation feed. */
interface ComicNetworkDataSource {
    suspend fun getHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>>

    // Removed with the retired comment-vote UI in the follow-up comment cleanup commit.
    suspend fun likeComment(commentId: Int): NetWorkResult<CommentComicResponse>
}

class NetworkComicDataSource(
    private val service: ComicService,
) : BaseRepository(), ComicNetworkDataSource {
    override suspend fun getHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>> =
        safeApiCall { service.getHomeSwiperComicList() }

    override suspend fun likeComment(commentId: Int): NetWorkResult<CommentComicResponse> =
        safeApiCall { service.likeComment(commentId) }
}

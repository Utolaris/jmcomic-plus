package com.par9uet.jm.retrofit.service

import com.par9uet.jm.retrofit.model.CommentComicResponse
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.retrofit.model.ResponseWrapper
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST

interface ComicService {
    @GET("promote")
    suspend fun getHomeSwiperComicList(): ResponseWrapper<List<HomeSwiperComicListItemResponse>>

    // Removed with the retired comment-vote UI in the follow-up comment cleanup commit.
    @POST("comment_vote")
    @FormUrlEncoded
    suspend fun likeComment(
        @Field("comment_id") commentId: Int,
        @Field("vote_type") voteType: String = "up",
    ): ResponseWrapper<CommentComicResponse>
}

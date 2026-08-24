package com.par9uet.jm.retrofit.service

import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.retrofit.model.ResponseWrapper
import retrofit2.http.GET

interface ComicService {
    @GET("promote")
    suspend fun getHomeSwiperComicList(): ResponseWrapper<List<HomeSwiperComicListItemResponse>>
}

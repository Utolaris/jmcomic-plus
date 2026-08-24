package com.par9uet.jm.data.comic

import com.par9uet.jm.repository.BaseRepository
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.service.ComicService

/** The sole network business capability: optional Home recommendation. */
interface NetworkHomeDataSource {
    suspend fun getHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>>
}

class RetrofitNetworkHomeDataSource(
    private val service: ComicService,
) : BaseRepository(), NetworkHomeDataSource {
    override suspend fun getHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>> =
        safeApiCall { service.getHomeSwiperComicList() }
}

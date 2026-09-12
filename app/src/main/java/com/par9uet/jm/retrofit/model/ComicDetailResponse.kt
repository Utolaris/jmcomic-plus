package com.par9uet.jm.retrofit.model

data class ComicDetailResponse(
    val id: Int,
    val name: String,
    val description: String,
    val author: List<String>,
    val total_views: Int,
    val likes: Int,
    val comment_total: Int,
    val tags: List<String>,
    val actors: List<String>,
    val works: List<String>,
    val is_favorite: Boolean,
    val related_list: List<ComicDetailRelatedListItemResponse>,
    val series: List<ComicDetailSeriesListItemResponse>,
    val series_id: String,
    val price: String,
    val purchased: Boolean,
)
